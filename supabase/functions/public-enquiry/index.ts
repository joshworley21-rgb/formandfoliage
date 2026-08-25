// Public enquiry intake — the endpoint the customer-facing form posts to.
//
// Ported from the /api/public/enquiry route. Deployed with verify_jwt = false so
// a plain HTML form on any website can post to it; the enquiry_key identifies
// the owner and a honeypot field absorbs bots.
import { adminClient } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { isEmailConfigured, sendEmail } from "../_shared/email.ts";
import { DEFAULT_BUSINESS_NAME, escapeHtml } from "../_shared/templates.ts";

type EnquiryInput = Record<string, unknown>;

function text(value: unknown, max: number): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function validate(raw: EnquiryInput) {
  const key = text(raw.key, 64);
  if (key.length < 4) throw new Error("Invalid enquiry link");

  const name = text(raw.name, 120);
  if (!name) throw new Error("A name is required");

  const email = text(raw.email, 255);
  if (email && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new Error("Email is invalid");

  return {
    key,
    name,
    email,
    phone: text(raw.phone, 40),
    address: text(raw.address, 400),
    message: text(raw.message, 4000),
    source: text(raw.source, 60),
    website: text(raw.website, 200),
    // A forwarder that has already emailed the owner sets this false, so one
    // enquiry does not arrive twice. Anything else — a plain HTML form — omits
    // it and is notified as before.
    notify: raw.notify !== false && raw.notify !== "false",
  };
}

Deno.serve(
  serveJson(async (request) => {
    let raw: EnquiryInput;
    try {
      const contentType = request.headers.get("content-type") ?? "";
      raw = contentType.includes("application/json")
        ? ((await request.json()) as EnquiryInput)
        : (Object.fromEntries((await request.formData()).entries()) as EnquiryInput);
    } catch {
      return json({ error: "Invalid body" }, 400);
    }

    let data: ReturnType<typeof validate>;
    try {
      data = validate(raw);
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : "Invalid enquiry" }, 400);
    }

    // Silently accept spam so bots do not learn anything.
    if (data.website) return json({ ok: true });

    const supabaseAdmin = adminClient();

    const { data: owner } = await supabaseAdmin
      .from("settings")
      .select("user_id, email, reply_to_email, business_name")
      .eq("enquiry_key", data.key)
      .maybeSingle();

    if (!owner?.user_id) return json({ error: "Unknown enquiry link" }, 404);

    const { error } = await supabaseAdmin.from("enquiries").insert({
      user_id: owner.user_id,
      name: data.name,
      email: data.email || null,
      phone: data.phone || null,
      address: data.address || null,
      message: data.message || null,
      source: data.source || "website",
    });
    if (error) return json({ error: "Could not save enquiry" }, 500);

    // Notify the owner by email (best effort — never blocks the enquiry).
    let notified = false;
    try {
      // A new enquiry belongs in the inbox that is actually read, which is
      // the reply-to address — not the send-only address invoices go out from.
      const notifyTo = owner.reply_to_email || owner.email;
      const notifyFrom = owner.email || notifyTo;
      if (data.notify && isEmailConfigured() && notifyTo) {
        const businessName = owner.business_name || DEFAULT_BUSINESS_NAME;
        const rows: [string, string][] = [
          ["Name", data.name],
          ["Address", data.address || "—"],
          ["Email", data.email || "—"],
          ["Phone", data.phone || "—"],
          ["Job description", data.message || "—"],
        ];
        const html = `<div style="font-family:Arial,sans-serif;font-size:15px;color:#243">
      <p><strong>New website enquiry</strong></p>
      ${rows
        .map(
          ([label, value]) =>
            `<p style="margin:4px 0"><strong>${label}:</strong> ${escapeHtml(value).replace(/\n/g, "<br/>")}</p>`,
        )
        .join("\n")}
      <p style="margin-top:16px">Open the Enquiries inbox in your CRM to reply or add them as a client.</p>
    </div>`;

        await sendEmail({
          from: `${businessName} <${notifyFrom}>`,
          to: notifyTo,
          replyTo: data.email || null,
          subject: `New enquiry — ${data.name}`,
          html,
        });
        notified = true;
      }
    } catch {
      // ignore notification failures
    }

    // The caller is told whether an email actually went out, so a forwarder
    // that suppressed it can log what happened rather than assume.
    return json({ ok: true, notified });
  }),
);
