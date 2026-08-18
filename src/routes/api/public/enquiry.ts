import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "content-type",
};

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
          .select("user_id")
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

        return Response.json({ ok: true }, { headers: CORS });
      },
    },
  },
});
