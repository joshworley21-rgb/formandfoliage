// Direct Resend transport (Deno port of src/lib/email.server.ts).
// Set the key once with: supabase secrets set RESEND_API_KEY=re_...

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
  | { ok: true; id: string | null }
  | { ok: false; status: number; detail: string };

function apiKey(): string | undefined {
  return Deno.env.get("RESEND_API_KEY");
}

/** True when a Resend API key is configured for this project. */
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
    return { ok: false, status: response.status, detail: await response.text() };
  }

  try {
    const payload = (await response.json()) as { id?: string };
    return { ok: true, id: payload?.id ?? null };
  } catch {
    return { ok: true, id: null };
  }
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
