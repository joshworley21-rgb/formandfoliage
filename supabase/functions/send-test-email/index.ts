// Sends a sample invoice email so templates can be checked from Settings.
// Ported from the sendTestEmail server function.
import { requireUser } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { isEmailConfigured, sendEmail } from "../_shared/email.ts";
import {
  DEFAULT_BODY_TEMPLATE,
  DEFAULT_BUSINESS_NAME,
  NO_SENDER_MESSAGE,
  DEFAULT_SUBJECT_TEMPLATE,
  fillTemplate,
  textToHtml,
} from "../_shared/templates.ts";

type SendTestEmailInput = {
  to?: string;
  subjectTemplate?: string | null;
  bodyTemplate?: string | null;
};

const TEST_FOOTER = `
      <hr style="margin-top:24px;border:none;border-top:1px solid #ddd"/>
      <p style="font-size:12px;color:#777">This is a test email sent from your CRM using sample invoice details. No PDF is attached.</p>`;

Deno.serve(
  serveJson(async (request) => {
    const { userId, supabase } = await requireUser(request);
    const input = (await request.json()) as SendTestEmailInput;
    const to = input?.to?.trim();
    if (!to) throw new Error("A recipient address is required");

    if (!isEmailConfigured()) {
      return json({ sent: false, reason: "Resend is not connected yet." });
    }

    const { data: settings } = await supabase
      .from("settings")
      .select("*")
      .eq("user_id", userId)
      .maybeSingle();

    const businessName = settings?.business_name || DEFAULT_BUSINESS_NAME;
    const fromEmail = settings?.email?.trim();
    if (!fromEmail) return json({ sent: false, reason: NO_SENDER_MESSAGE });
    // Replies go wherever the inbox is; the From address only has to be on a
    // domain Resend has verified.
    const replyTo = settings?.reply_to_email || null;
    const today = new Date();
    const due = new Date(today.getTime() + 14 * 86400000).toISOString().slice(0, 10);

    const tokens: Record<string, string> = {
      client_name: "Sample Client",
      business_name: businessName,
      invoice_number: `${settings?.invoice_prefix ?? "INV-"}0001`,
      total: "£120.00",
      due_date: due,
      due_clause: ` by ${due}`,
      issue_date: today.toISOString().slice(0, 10),
      payment_terms: settings?.payment_terms ?? "",
      bank_details: settings?.bank_details ?? "",
    };

    const subject = `[Test] ${fillTemplate(
      input.subjectTemplate || settings?.email_subject_template || DEFAULT_SUBJECT_TEMPLATE,
      tokens,
    )}`;
    const textBody = fillTemplate(
      input.bodyTemplate || settings?.email_body_template || DEFAULT_BODY_TEMPLATE,
      tokens,
    );
    const html = textToHtml(textBody, TEST_FOOTER);

    const result = await sendEmail({
      from: `${businessName} <${fromEmail}>`,
      replyTo,
      to,
      subject,
      html,
      text: `${textBody}\n\n---\nThis was a test from the JW Garden Services CRM.`,
    });

    if (!result.ok) {
      console.error("Resend test send error", result.status, result.detail);
      return json({
        sent: false,
        reason: `${result.detail} (tried to send from ${fromEmail})`.slice(0, 500),
      });
    }

    return json({ sent: true, to, subject });
  }),
);
