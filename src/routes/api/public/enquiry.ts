import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "content-type",
};

const escapeHtml = (value: string) =>
  value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

const schema = z.object({
  key: z.string().trim().min(4).max(64),
  name: z.string().trim().min(1).max(120),
  email: z.string().trim().email().max(255).optional().or(z.literal("")),
  phone: z.string().trim().max(40).optional().or(z.literal("")),
  address: z.string().trim().max(400).optional().or(z.literal("")),
  message: z.string().trim().max(4000).optional().or(z.literal("")),
  source: z.string().trim().max(60).optional(),
  // honeypot — bots fill hidden fields
  website: z.string().max(200).optional(),
});

export const Route = createFileRoute("/api/public/enquiry")({
  server: {
    handlers: {
      OPTIONS: async () => new Response(null, { status: 204, headers: CORS }),
      POST: async ({ request }) => {
        let raw: unknown;
        try {
          const contentType = request.headers.get("content-type") ?? "";
          raw = contentType.includes("application/json")
            ? await request.json()
            : Object.fromEntries((await request.formData()).entries());
        } catch {
          return Response.json({ error: "Invalid body" }, { status: 400, headers: CORS });
        }

        const parsed = schema.safeParse(raw);
        if (!parsed.success) {
          return Response.json(
            { error: parsed.error.issues[0]?.message ?? "Invalid enquiry" },
            { status: 400, headers: CORS },
          );
        }
        const data = parsed.data;
        if (data.website) {
          // silently accept spam
          return Response.json({ ok: true }, { headers: CORS });
        }

        const { supabaseAdmin } = await import("@/integrations/supabase/client.server");

        const { data: owner } = await supabaseAdmin
          .from("settings")
          .select("user_id, email, business_name")
          .eq("enquiry_key", data.key)
          .maybeSingle();

        if (!owner?.user_id) {
          return Response.json({ error: "Unknown enquiry link" }, { status: 404, headers: CORS });
        }

        const { error } = await supabaseAdmin.from("enquiries").insert({
          user_id: owner.user_id,
          name: data.name,
          email: data.email || null,
          phone: data.phone || null,
          address: data.address || null,
          message: data.message || null,
          source: data.source || "website",
        });
        if (error) {
          return Response.json({ error: "Could not save enquiry" }, { status: 500, headers: CORS });
        }

        // Notify the owner by email (best effort — never blocks the enquiry)
        try {
          const lovableKey = process.env["LOVABLE_API_KEY"];
          const resendKey = process.env["RESEND_API_KEY"];
          const notifyTo = owner.email;
          if (lovableKey && resendKey && notifyTo) {
            const businessName = owner.business_name || "JW Garden Services";
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

            await fetch("https://connector-gateway.lovable.dev/resend/emails", {
              method: "POST",
              headers: {
                Authorization: `Bearer ${lovableKey}`,
                "X-Connection-Api-Key": resendKey,
                "Content-Type": "application/json",
              },
              body: JSON.stringify({
                from: `${businessName} <${notifyTo}>`,
                to: notifyTo,
                ...(data.email ? { reply_to: data.email } : {}),
                subject: `New enquiry — ${data.name}`,
                html,
              }),
            });
          }
        } catch {
          // ignore notification failures
        }

        return Response.json({ ok: true }, { headers: CORS });
      },
    },
  },
});
