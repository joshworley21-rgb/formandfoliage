// Chases unpaid invoices. Ported from the /api/public/hooks/payment-reminders
// route, which ran on a schedule.
//
// Deployed with verify_jwt = false and guarded by the service-role key instead,
// so only the scheduled job (or you, with the service key) can trigger a run.
//
// Only clients with payment_reminders switched on are chased. It used to be
// everybody: this runs unattended and emails somebody else's customer, and the
// first anyone knew of it was a client mentioning they had had a reminder. The
// decision itself lives in _shared/chasing, where it is tested, because there
// is nobody watching a scheduled run to catch it getting that wrong.
import { adminClient, requireServiceRole } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { isEmailConfigured, sendEmail } from "../_shared/email.ts";
import { DEFAULT_BUSINESS_NAME, NO_SENDER_MESSAGE, textToHtml } from "../_shared/templates.ts";
import { shouldChase } from "../_shared/chasing.ts";

const REMINDER_TAG = "[reminder]";

type InvoiceRow = {
  id: string;
  user_id: string;
  number: string;
  total: number;
  due_date: string | null;
  sent_at: string;
  client_id: string;
  clients: { name: string; email: string | null; payment_reminders: boolean } | null;
};

Deno.serve(
  serveJson(async (request) => {
    requireServiceRole(request);

    if (!isEmailConfigured()) {
      return json({ ok: false, reason: "Email is not connected" });
    }

    const supabaseAdmin = adminClient();
    const now = new Date();

    const { data: invoices, error } = await supabaseAdmin
      .from("invoices")
      .select(
        "id, user_id, number, total, due_date, sent_at, client_id, clients(name, email, payment_reminders)",
      )
      .eq("status", "sent")
      .not("sent_at", "is", null);
    if (error) throw error;

    let sent = 0;
    let skipped = 0;
    let optedOut = 0;

    for (const invoice of (invoices ?? []) as unknown as InvoiceRow[]) {
      const client = invoice.clients;

      // Checked before the reminder history is looked up: a client nobody
      // opted in is not a candidate, so there is no reason to ask the database
      // when they were last chased.
      if (!client?.payment_reminders) {
        if (client) optedOut++;
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

      const decision = shouldChase({
        optedIn: client.payment_reminders,
        email: client.email,
        sentAt: invoice.sent_at,
        dueDate: invoice.due_date,
        lastChasedAt: lastReminders?.[0]?.created_at ?? null,
        now,
      });
      if (!decision.chase) {
        skipped++;
        continue;
      }
      const { email: to, overdue: isOverdue, overdueDays, daysSinceSent } = decision;

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
          recipient: to,
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
        to,
        subject: isOverdue
          ? `Overdue invoice ${invoice.number} — payment reminder`
          : `Payment reminder — invoice ${invoice.number}`,
        html: textToHtml(bodyText),
        text: bodyText,
      });

      if (!result.ok) {
        await supabaseAdmin.from("invoice_email_events").insert({
          user_id: invoice.user_id,
          invoice_id: invoice.id,
          status: "failed",
          recipient: to,
          detail: `${REMINDER_TAG} ${result.detail}`.slice(0, 500),
        });
        continue;
      }

      await supabaseAdmin.from("invoice_email_events").insert({
        user_id: invoice.user_id,
        invoice_id: invoice.id,
        status: "sent",
        recipient: to,
        provider_message_id: result.id,
        detail: `${REMINDER_TAG} ${isOverdue ? `Overdue reminder sent to ${to} (${overdueDays} days past due)` : `Payment reminder sent to ${to} (${daysSinceSent} days unpaid)`}`,
      });
      sent++;
    }

    // optedOut is reported separately from skipped so a run that sends nothing
    // says why: "none of them are opted in" and "none of them are due yet" are
    // different states, and only one of them is a setting.
    return json({ ok: true, checked: invoices?.length ?? 0, sent, skipped, optedOut });
  }),
);
