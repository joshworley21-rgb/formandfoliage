// Chases unpaid invoices. Ported from the /api/public/hooks/payment-reminders
// route, which ran on a schedule.
//
// Deployed with verify_jwt = false and guarded by the service-role key instead,
// so only the scheduled job (or you, with the service key) can trigger a run.
import { adminClient, requireServiceRole } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { isEmailConfigured, sendEmail } from "../_shared/email.ts";
import { DEFAULT_BUSINESS_NAME, NO_SENDER_MESSAGE, textToHtml } from "../_shared/templates.ts";

const REMINDER_AFTER_DAYS = 14;
const REPEAT_EVERY_DAYS = 7;
const REMINDER_TAG = "[reminder]";

type InvoiceRow = {
  id: string;
  user_id: string;
  number: string;
  total: number;
  due_date: string | null;
  sent_at: string;
  client_id: string;
  clients: { name: string; email: string | null } | null;
};

Deno.serve(
  serveJson(async (request) => {
    requireServiceRole(request);

    if (!isEmailConfigured()) {
      return json({ ok: false, reason: "Email is not connected" });
    }

    const supabaseAdmin = adminClient();
    const now = Date.now();
    const todayIso = new Date(now).toISOString().slice(0, 10);

    const { data: invoices, error } = await supabaseAdmin
      .from("invoices")
      .select("id, user_id, number, total, due_date, sent_at, client_id, clients(name, email)")
      .eq("status", "sent")
      .not("sent_at", "is", null);
    if (error) throw error;

    let sent = 0;
    let skipped = 0;

    for (const invoice of (invoices ?? []) as unknown as InvoiceRow[]) {
      const client = invoice.clients;
      if (!client?.email) {
        skipped++;
        continue;
      }

      // Overdue = past its due date. Otherwise fall back to the "unpaid for
      // N days since sending" rule for invoices with no due date set.
      const overdueDays = invoice.due_date
        ? Math.floor(
            (new Date(`${todayIso}T00:00:00Z`).getTime() -
              new Date(`${invoice.due_date}T00:00:00Z`).getTime()) /
              86400000,
          )
        : 0;
      const daysSinceSent = Math.floor((now - new Date(invoice.sent_at).getTime()) / 86400000);
      const isOverdue = Boolean(invoice.due_date) && overdueDays > 0;
      if (!isOverdue && daysSinceSent < REMINDER_AFTER_DAYS) {
        skipped++;
        continue;
      }

      const { data: lastReminders } = await supabaseAdmin
        .from("invoice_email_events")
        .select("created_at, detail, status")
        .eq("invoice_id", invoice.id)
        .eq("status", "sent")
        .like("detail", `${REMINDER_TAG}%`)
        .order("created_at", { ascending: false })
        .limit(1);

      const last = lastReminders?.[0];
      if (last && now - new Date(last.created_at).getTime() < REPEAT_EVERY_DAYS * 86400000) {
        skipped++;
        continue;
      }

      const { data: settings } = await supabaseAdmin
        .from("settings")
        .select("business_name, email, reply_to_email, payment_terms, bank_details")
        .eq("user_id", invoice.user_id)
        .maybeSingle();

      const businessName = settings?.business_name || DEFAULT_BUSINESS_NAME;
      const fromEmail = settings?.email?.trim();
      // A scheduled run has nobody to tell, so it records why and moves on
      // rather than sending from an address that cannot reach the client.
      if (!fromEmail) {
        await supabaseAdmin.from("invoice_email_events").insert({
          user_id: invoice.user_id,
          invoice_id: invoice.id,
          status: "skipped",
          recipient: client.email,
          detail: `${REMINDER_TAG} ${NO_SENDER_MESSAGE}`.slice(0, 500),
        });
        skipped++;
        continue;
      }
      // Replies go wherever the inbox is; the From address only has to be on a
      // domain Resend has verified.
      const replyTo = settings?.reply_to_email || null;
      const statusLine = isOverdue
        ? `Invoice ${invoice.number} for £${Number(invoice.total).toFixed(2)} was due on ${invoice.due_date} and is now ${overdueDays} day${overdueDays === 1 ? "" : "s"} overdue.`
        : `Just a friendly reminder that invoice ${invoice.number} for £${Number(invoice.total).toFixed(2)} is still showing as unpaid (sent ${daysSinceSent} days ago).`;

      const bodyText = [
        `Hi ${client.name},`,
        statusLine,
        settings?.bank_details ? `Payment details:\n${settings.bank_details}` : "",
        "If you have already paid, please ignore this message — and thank you.",
        `Many thanks,\n${businessName}`,
      ]
        .filter(Boolean)
        .join("\n\n");

      const result = await sendEmail({
        from: `${businessName} <${fromEmail}>`,
        replyTo,
        to: client.email,
        subject: isOverdue
          ? `Overdue invoice ${invoice.number} — payment reminder`
          : `Payment reminder — invoice ${invoice.number}`,
        html: textToHtml(bodyText),
      });

      if (!result.ok) {
        await supabaseAdmin.from("invoice_email_events").insert({
          user_id: invoice.user_id,
          invoice_id: invoice.id,
          status: "failed",
          recipient: client.email,
          detail: `${REMINDER_TAG} ${result.detail}`.slice(0, 500),
        });
        continue;
      }

      await supabaseAdmin.from("invoice_email_events").insert({
        user_id: invoice.user_id,
        invoice_id: invoice.id,
        status: "sent",
        recipient: client.email,
        provider_message_id: result.id,
        detail: `${REMINDER_TAG} ${isOverdue ? `Overdue reminder sent to ${client.email} (${overdueDays} days past due)` : `Payment reminder sent to ${client.email} (${daysSinceSent} days unpaid)`}`,
      });
      sent++;
    }

    return json({ ok: true, checked: invoices?.length ?? 0, sent, skipped });
  }),
);
