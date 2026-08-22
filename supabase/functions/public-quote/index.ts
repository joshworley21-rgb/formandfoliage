// The customer's side of a quote: read it by its link, then accept or decline.
//
// Deployed with verify_jwt = false — the customer has no account. The quotes
// table has no anon policy, so this function is the only way in, and it returns
// just the fields the accept page needs.
import { adminClient } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { isEmailConfigured, sendEmail } from "../_shared/email.ts";
import { DEFAULT_BUSINESS_NAME, escapeHtml, textToHtml } from "../_shared/templates.ts";

type Body = {
  action?: "get" | "decide";
  key?: string;
  decision?: "accepted" | "declined";
  reason?: string | null;
};

type ItemRow = { description: string; quantity: number; unit_price: number; amount: number };

function isExpired(quote: { valid_until: string | null }): boolean {
  if (!quote.valid_until) return false;
  return quote.valid_until < new Date().toISOString().slice(0, 10);
}

Deno.serve(
  serveJson(async (request) => {
    const body = (await request.json()) as Body;
    const key = typeof body.key === "string" ? body.key.trim() : "";
    if (!key) return json({ error: "This quote link is not valid" }, 400);

    const supabaseAdmin = adminClient();

    const { data: quote } = await supabaseAdmin
      .from("quotes")
      .select(
        "id, user_id, number, contact_name, contact_address, issue_date, valid_until, subtotal, vat_amount, total, status, notes, terms",
      )
      .eq("public_key", key)
      .maybeSingle();

    if (!quote) return json({ error: "This quote link is not valid" }, 404);

    // A draft has not been sent yet; treat its link as not live.
    if (quote.status === "draft") return json({ error: "This quote is not available" }, 404);

    const { data: items } = await supabaseAdmin
      .from("quote_items")
      .select("description, quantity, unit_price, amount")
      .eq("quote_id", quote.id)
      .order("sort_order", { ascending: true });

    const { data: settings } = await supabaseAdmin
      .from("settings")
      .select("business_name, email, phone, vat_registered")
      .eq("user_id", quote.user_id)
      .maybeSingle();

    const businessName = settings?.business_name || DEFAULT_BUSINESS_NAME;

    const view = {
      number: quote.number,
      contactName: quote.contact_name,
      contactAddress: quote.contact_address,
      issueDate: quote.issue_date,
      validUntil: quote.valid_until,
      subtotal: Number(quote.subtotal),
      vatAmount: Number(quote.vat_amount),
      total: Number(quote.total),
      status: quote.status,
      expired: isExpired(quote),
      notes: quote.notes,
      terms: quote.terms,
      vatRegistered: Boolean(settings?.vat_registered),
      business: { name: businessName, email: settings?.email ?? null, phone: settings?.phone ?? null },
      items: ((items ?? []) as ItemRow[]).map((item) => ({
        description: item.description,
        quantity: Number(item.quantity),
        unitPrice: Number(item.unit_price),
        amount: Number(item.amount),
      })),
    };

    if (body.action !== "decide") return json({ quote: view });

    const decision = body.decision;
    if (decision !== "accepted" && decision !== "declined") {
      return json({ error: "Choose accept or decline" }, 400);
    }
    if (quote.status !== "sent") {
      return json({ error: "This quote has already been answered" }, 409);
    }
    if (isExpired(quote)) {
      return json({ error: "This quote has expired — please ask for a new one" }, 409);
    }

    const reason = typeof body.reason === "string" ? body.reason.trim().slice(0, 1000) : "";

    const { error } = await supabaseAdmin
      .from("quotes")
      .update({
        status: decision,
        decided_at: new Date().toISOString(),
        decline_reason: decision === "declined" ? reason || null : null,
      })
      .eq("id", quote.id)
      // Only move a quote that is still awaiting an answer, so two taps in
      // quick succession cannot overwrite each other.
      .eq("status", "sent");
    if (error) return json({ error: "Could not record your answer" }, 500);

    // Tell the owner (best effort — the customer's answer is already saved).
    try {
      if (isEmailConfigured() && settings?.email) {
        const heading =
          decision === "accepted"
            ? `${quote.contact_name} accepted quote ${quote.number}`
            : `${quote.contact_name} declined quote ${quote.number}`;
        const lines = [
          heading,
          `Total: £${Number(quote.total).toFixed(2)}`,
          quote.contact_address ? `Address: ${quote.contact_address}` : "",
          decision === "declined" && reason ? `Reason given: ${reason}` : "",
          decision === "accepted"
            ? "Open Quotes in your CRM to turn them into a client and book the work."
            : "",
        ].filter(Boolean);

        await sendEmail({
          from: `${businessName} <${settings.email}>`,
          to: settings.email,
          subject: heading,
          html: textToHtml(lines.join("\n\n")),
        });
      }
    } catch {
      // ignore notification failures
    }

    return json({
      ok: true,
      status: decision,
      business: escapeHtml(businessName),
    });
  }),
);
