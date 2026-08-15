import { createFileRoute } from "@tanstack/react-router";

const REMINDER_AFTER_DAYS = 14;
const REPEAT_EVERY_DAYS = 7;
const REMINDER_TAG = "[reminder]";

const escapeHtml = (value: string) =>
  value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export const Route = createFileRoute("/api/public/hooks/payment-reminders")({
  server: {
    handlers: {
      POST: async ({ request }) => {
        const apiKey =
          request.headers.get("apikey") ??
          request.headers.get("authorization")?.replace("Bearer ", "");
        const allowed = [
          process.env["SUPABASE_PUBLISHABLE_KEY"],
          process.env["SUPABASE_ANON_KEY"],
        ].filter(Boolean) as string[];
        if (!apiKey || allowed.length === 0 || !allowed.includes(apiKey)) {
          return Response.json({ error: "Unauthorized" }, { status: 401 });
        }

        const lovableKey = process.env["LOVABLE_API_KEY"];
        const resendKey = process.env["RESEND_API_KEY"];
        if (!lovableKey || !resendKey) {
          return Response.json({ ok: false, reason: "Email is not connected" }, { status: 200 });
        }

        const { supabaseAdmin } = await import("@/integrations/supabase/client.server");

        const now = Date.now();
        const cutoff = new Date(now - REMINDER_AFTER_DAYS * 86400000).toISOString();

        const { data: invoices, error } = await supabaseAdmin
          .from("invoices")
          .select("id, user_id, number, total, due_date, sent_at, client_id, clients(name, email)")
          .eq("status", "sent")
          .not("sent_at", "is", null)
          .lte("sent_at", cutoff);
        if (error) throw error;

        let sent = 0;
        let skipped = 0;

        for (const invoice of invoices ?? []) {
          const client = invoice.clients as unknown as { name: string; email: string | null } | null;
          if (!client?.email) {
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
            .select("business_name, email, payment_terms, bank_details")
            .eq("user_id", invoice.user_id)
            .maybeSingle();

          const businessName = settings?.business_name || "JW Garden Services";
          const fromEmail = settings?.email || "onboarding@resend.dev";
          const daysOutstanding = Math.floor(
            (now - new Date(invoice.sent_at as string).getTime()) / 86400000,
          );

          const bodyText = [
            `Hi ${client.name},`,
            `Just a friendly reminder that invoice ${invoice.number} for £${Number(invoice.total).toFixed(2)} is still showing as unpaid (sent ${daysOutstanding} days ago${invoice.due_date ? `, due ${invoice.due_date}` : ""}).`,
            settings?.bank_details ? `Payment details:\n${settings.bank_details}` : "",
            "If you have already paid, please ignore this message — and thank you.",
            `Many thanks,\n${businessName}`,
          ]
            .filter(Boolean)
            .join("\n\n");

          const html = `<div style="font-family:Arial,sans-serif;font-size:15px;color:#243">
      ${escapeHtml(bodyText)
        .split(/\n{2,}/)
        .map((para) => `<p>${para.replace(/\n/g, "<br/>")}</p>`)
        .join("\n")}
    </div>`;

          const response = await fetch("https://connector-gateway.lovable.dev/resend/emails", {
            method: "POST",
            headers: {
              Authorization: `Bearer ${lovableKey}`,
              "X-Connection-Api-Key": resendKey,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              from: `${businessName} <${fromEmail}>`,
              to: client.email,
              subject: `Payment reminder — invoice ${invoice.number}`,
              html,
            }),
          });

          if (!response.ok) {
            const detail = await response.text();
            await supabaseAdmin.from("invoice_email_events").insert({
              user_id: invoice.user_id,
              invoice_id: invoice.id,
              status: "failed",
              recipient: client.email,
              detail: `${REMINDER_TAG} ${response.status}: ${detail}`.slice(0, 500),
            });
            continue;
          }

          let providerMessageId: string | null = null;
          try {
            const payload = (await response.json()) as { id?: string };
            providerMessageId = payload?.id ?? null;
          } catch {
            providerMessageId = null;
          }

          await supabaseAdmin.from("invoice_email_events").insert({
            user_id: invoice.user_id,
            invoice_id: invoice.id,
            status: "sent",
            recipient: client.email,
            provider_message_id: providerMessageId,
            detail: `${REMINDER_TAG} Payment reminder sent to ${client.email} (${daysOutstanding} days unpaid)`,
          });
          sent++;
        }

        return Response.json({ ok: true, checked: invoices?.length ?? 0, sent, skipped });
      },
    },
  },
});
