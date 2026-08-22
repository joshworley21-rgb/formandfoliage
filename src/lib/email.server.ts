// Direct Resend transport. Email used to go through Lovable's connector gateway
// (connector-gateway.lovable.dev) with a LOVABLE_API_KEY alongside the Resend
// key; it now talks to api.resend.com with your own key and nothing else.
//
// SECURITY: server-only — RESEND_API_KEY must never reach the client bundle.
// Load inside server handlers:
//   const { sendEmail } = await import("@/lib/email.server");
//
// Deliberately dependency-free (fetch + env only) so the same code can be
// lifted into a Supabase Edge Function unchanged.

const RESEND_API = "https://api.resend.com";

export type EmailAttachment = {
  filename: string;
  /** Base64-encoded file contents. */
  content: string;
  contentType?: string;
};

export type SendEmailInput = {
  from: string;
  to: string;
  subject: string;
  html: string;
  replyTo?: string | null;
  attachments?: EmailAttachment[];
};

export type SendEmailResult =
  { ok: true; id: string | null } | { ok: false; status: number; detail: string };

function apiKey(): string | undefined {
  return process.env["RESEND_API_KEY"];
}

/** True when a Resend API key is configured for this deployment. */
export function isEmailConfigured(): boolean {
  return Boolean(apiKey());
}

export async function sendEmail(input: SendEmailInput): Promise<SendEmailResult> {
  const key = apiKey();
  if (!key) return { ok: false, status: 0, detail: "RESEND_API_KEY is not set" };

  const response = await fetch(`${RESEND_API}/emails`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${key}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from: input.from,
      to: input.to,
      subject: input.subject,
      html: input.html,
      ...(input.replyTo ? { reply_to: input.replyTo } : {}),
      ...(input.attachments?.length
        ? {
            attachments: input.attachments.map((file) => ({
              filename: file.filename,
              content: file.content,
              ...(file.contentType ? { content_type: file.contentType } : {}),
            })),
          }
        : {}),
    }),
  });

  if (!response.ok) {
    const detail = await response.text();
    return { ok: false, status: response.status, detail };
  }

  let id: string | null = null;
  try {
    const payload = (await response.json()) as { id?: string };
    id = payload?.id ?? null;
  } catch {
    id = null;
  }
  return { ok: true, id };
}

/**
 * Check the configured key with Resend. A sending-only key is valid but not
 * allowed to list domains, so 403 counts as connected — only 401 (and a missing
 * key) means it is not.
 */
export async function verifyResendKey(): Promise<boolean> {
  const key = apiKey();
  if (!key) return false;

  try {
    const response = await fetch(`${RESEND_API}/domains`, {
      headers: { Authorization: `Bearer ${key}` },
    });
    if (response.ok || response.status === 403) return true;
    if (response.status === 401) return false;
    console.error("Resend verify error", response.status, await response.text());
    return false;
  } catch (error) {
    console.error("Resend verify failed", error);
    return false;
  }
}
