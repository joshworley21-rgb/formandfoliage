// Emails a quote to the customer with a link to accept or decline it.
//
// No PDF attachment: the whole point is the link, and a quote the customer can
// accept in two taps beats one they have to open, read and reply to.
import { requireUser } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { isEmailConfigured, sendEmail } from "../_shared/email.ts";
import { DEFAULT_BUSINESS_NAME, DEFAULT_FROM_EMAIL, escapeHtml } from "../_shared/templates.ts";

type SendQuoteInput = {
  quoteId?: string;
  /** Public origin the accept link should point at, e.g. https://example.com */
  origin?: string;
  message?: string | null;
};

function money(value: number | string | null | undefined): string {
  return `£${Number(value ?? 0).toFixed(2)}`;
}

Deno.serve(
  serveJson(async (request) => {
    const { userId, supabase } = await requireUser(request);
    const input = (await request.json()) as SendQuoteInput;
    if (!input?.quoteId) throw new Error("quoteId is required");
    const origin = (input.origin ?? "").replace(/\/$/, "");
    if (!origin) throw new Error("An origin for the accept link is required");

    const { data: quote, error } = await supabase
      .from("quotes")
      .select("*")
      .eq("id", input.quoteId)
      .single();
    if (error || !quote) throw new Error("Quote not found");
    if (!quote.contact_email) throw new Error("This quote has no email address to send to");

    if (!isEmailConfigured()) {
      return json({ sent: false, reason: "Email is not connected yet — set up Resend in Settings." });
    }

    const { data: items } = await supabase
      .from("quote_items")
      .select("description, quantity, unit_price, amount")
      .eq("quote_id", quote.id)
      .order("sort_order", { ascending: true });

    const { data: settings } = await supabase
      .from("settings")
      .select("business_name, email, vat_registered")
      .eq("user_id", userId)
      .maybeSingle();

    const businessName = settings?.business_name || DEFAULT_BUSINESS_NAME;
    const fromEmail = settings?.email || DEFAULT_FROM_EMAIL;
    const link = `${origin}/quote/${quote.public_key}`;

    type ItemRow = { description: string; quantity: number; unit_price: number; amount: number };
    const rows = ((items ?? []) as ItemRow[])
      .map(
        (item) => `<tr>
          <td style="padding:8px 0;border-bottom:1px solid #e6ebe8">${escapeHtml(item.description)}</td>
          <td style="padding:8px 0;border-bottom:1px solid #e6ebe8;text-align:right;white-space:nowrap">${Number(item.quantity)} × ${money(item.unit_price)}</td>
          <td style="padding:8px 0;border-bottom:1px solid #e6ebe8;text-align:right;white-space:nowrap">${money(item.amount)}</td>
        </tr>`,
      )
      .join("\n");

    const vatLine = settings?.vat_registered
      ? `<tr><td colspan="2" style="padding:4px 0;text-align:right">VAT</td><td style="padding:4px 0;text-align:right">${money(quote.vat_amount)}</td></tr>`
      : "";

    const intro = input.message?.trim()
      ? escapeHtml(input.message.trim()).replace(/\n/g, "<br/>")
      : `Thanks for getting in touch. Here is our quote for the work at ${escapeHtml(quote.contact_address || "your garden")}.`;

    const html = `<div style="font-family:Arial,sans-serif;font-size:15px;color:#243;max-width:600px">
      <p>Hi ${escapeHtml(quote.contact_name)},</p>
      <p>${intro}</p>
      <table style="width:100%;border-collapse:collapse;margin:20px 0">
        <thead>
          <tr>
            <th style="text-align:left;padding-bottom:8px;border-bottom:2px solid #243">Work</th>
            <th style="text-align:right;padding-bottom:8px;border-bottom:2px solid #243">Rate</th>
            <th style="text-align:right;padding-bottom:8px;border-bottom:2px solid #243">Amount</th>
          </tr>
        </thead>
        <tbody>
          ${rows}
          <tr><td colspan="2" style="padding:8px 0 4px;text-align:right">Subtotal</td><td style="padding:8px 0 4px;text-align:right">${money(quote.subtotal)}</td></tr>
          ${vatLine}
          <tr><td colspan="2" style="padding:4px 0;text-align:right;font-weight:bold">Total</td><td style="padding:4px 0;text-align:right;font-weight:bold">${money(quote.total)}</td></tr>
        </tbody>
      </table>
      ${quote.valid_until ? `<p style="color:#6e7672;font-size:14px">This quote is valid until ${escapeHtml(quote.valid_until)}.</p>` : ""}
      <p style="margin:28px 0">
        <a href="${link}" style="background:#2f6b46;color:#fff;padding:12px 22px;border-radius:10px;text-decoration:none;display:inline-block;font-weight:bold">View and accept your quote</a>
      </p>
      <p style="color:#6e7672;font-size:13px">Or copy this link into your browser:<br/>${link}</p>
      ${quote.terms ? `<p style="color:#6e7672;font-size:13px;margin-top:24px">${escapeHtml(quote.terms).replace(/\n/g, "<br/>")}</p>` : ""}
      <p>Many thanks,<br/>${escapeHtml(businessName)}</p>
    </div>`;

    const result = await sendEmail({
      from: `${businessName} <${fromEmail}>`,
      to: quote.contact_email,
      subject: `Quote ${quote.number} from ${businessName}`,
      html,
    });

    if (!result.ok) {
      console.error("Quote send error", result.status, result.detail);
      throw new Error(`Resend could not send the quote. ${result.status}: ${result.detail}`);
    }

    // Sending is what moves a draft along; a quote already decided stays put.
    if (quote.status === "draft") {
      await supabase
        .from("quotes")
        .update({ status: "sent", sent_at: new Date().toISOString() })
        .eq("id", quote.id);
    }

    return json({ sent: true, to: quote.contact_email });
  }),
);
