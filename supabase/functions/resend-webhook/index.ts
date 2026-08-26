// Records what happened to an email after Resend accepted it.
//
// Resend posts delivery events here: delivered, bounced, complained, opened,
// clicked. The app only knew "sent", which means Resend took the message, not
// that anyone received it — a wrong address looked exactly like an invoice
// waiting to be paid.
//
// Public by necessity (Resend has no JWT), so every request must carry a valid
// Svix signature. Set RESEND_WEBHOOK_SECRET alongside RESEND_API_KEY.
import { adminClient } from "../_shared/supabase.ts";
import { verifySvixSignature } from "../_shared/svix.ts";

/** Resend event name to the status stored against the invoice. */
const STATUS: Record<string, string> = {
  "email.delivered": "delivered",
  "email.opened": "opened",
  "email.clicked": "clicked",
  "email.bounced": "bounced",
  "email.complained": "complained",
  "email.delivery_delayed": "delivery_delayed",
};

const DETAIL: Record<string, string> = {
  delivered: "Delivered to the client's mail server",
  opened: "Opened — see the note about open tracking",
  clicked: "A link in the email was clicked",
  bounced: "Bounced: the address rejected it",
  complained: "Marked as spam by the recipient",
  delivery_delayed: "Delivery delayed, Resend is still trying",
};

type ResendEvent = {
  type?: string;
  created_at?: string;
  data?: { email_id?: string; to?: string[] | string; bounce?: { message?: string } };
};

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });

  const body = await request.text();
  const verified = await verifySvixSignature(
    body,
    {
      id: request.headers.get("svix-id"),
      timestamp: request.headers.get("svix-timestamp"),
      signature: request.headers.get("svix-signature"),
    },
    Deno.env.get("RESEND_WEBHOOK_SECRET") ?? "",
  );
  if (!verified.ok) {
    console.error("Rejected a webhook:", verified.reason);
    return new Response("Invalid signature", { status: 401 });
  }

  let event: ResendEvent;
  try {
    event = JSON.parse(body) as ResendEvent;
  } catch {
    return new Response("Body is not JSON", { status: 400 });
  }

  const status = event.type ? STATUS[event.type] : undefined;
  const emailId = event.data?.email_id;
  // Events this app has no use for are accepted and dropped: answering with an
  // error would make Resend retry something that will never succeed.
  if (!status || !emailId) return new Response("ok", { status: 200 });

  const supabase = adminClient();

  // The send recorded Resend's message id, which is the only way back to the
  // invoice this event belongs to.
  const { data: origin } = await supabase
    .from("invoice_email_events")
    .select("user_id, invoice_id, recipient")
    .eq("provider_message_id", emailId)
    .order("created_at", { ascending: true })
    .limit(1)
    .maybeSingle();

  if (!origin) {
    console.log("No invoice matches Resend message", emailId);
    return new Response("ok", { status: 200 });
  }

  // Resend retries until it gets a 2xx, so the same event can arrive twice.
  const { data: seen } = await supabase
    .from("invoice_email_events")
    .select("id")
    .eq("provider_message_id", emailId)
    .eq("status", status)
    .limit(1)
    .maybeSingle();
  if (seen) return new Response("ok", { status: 200 });

  const bounceReason = event.data?.bounce?.message;
  await supabase.from("invoice_email_events").insert({
    user_id: origin.user_id,
    invoice_id: origin.invoice_id,
    status,
    recipient: Array.isArray(event.data?.to)
      ? event.data?.to[0]
      : (event.data?.to ?? origin.recipient),
    provider_message_id: emailId,
    detail: bounceReason
      ? `${DETAIL[status]}: ${bounceReason}`.slice(0, 500)
      : (DETAIL[status] ?? null),
  });

  return new Response("ok", { status: 200 });
});
