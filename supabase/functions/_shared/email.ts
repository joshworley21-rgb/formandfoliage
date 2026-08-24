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

/**
 * Turn a Resend error body into a sentence worth showing someone.
 *
 * Resend answers with a JSON blob. Handing that to whoever is trying to send
 * an invoice tells them nothing they can act on, and on a phone it fills the
 * screen twice over. The raw body still goes to the function logs, where it
 * belongs.
 */
export function explainResendError(status: number, body: string): string {
  let message = "";
  try {
    const parsed = JSON.parse(body) as { message?: unknown };
    if (typeof parsed?.message === "string") message = parsed.message;
  } catch {
    message = body.slice(0, 300);
  }

  // The one almost everyone meets first. Until a domain is verified Resend
  // delivers only to the address the account was opened with, and says so by
  // naming that address in brackets.
  if (/only send testing emails to your own email address/i.test(message)) {
    const own = /\(([^()\s]+@[^()\s]+)\)/.exec(message)?.[1];
    return (
      "Your sending domain is not verified with Resend yet, so it will only deliver to " +
      (own ?? "the address the Resend account was opened with") +
      ". Verify the domain at resend.com/domains — until then, send test invoices to that address."
    );
  }

  if (status === 401 || /api key is invalid/i.test(message)) {
    return "Resend rejected the API key. Check RESEND_API_KEY in Supabase under Edge Functions → Secrets.";
  }

  if (/domain is not verified/i.test(message)) {
    return "The domain in your sending address is not verified with Resend. Verify it at resend.com/domains, then send again.";
  }

  if (status === 429) {
    return "Resend is rate limiting — too many emails at once. Wait a minute and send again.";
  }

  return message || `Resend refused the email (${status}).`;
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
    const body = await response.text();
    // The blob goes to the logs; the caller gets something a person can act on.
    console.error("Resend rejected the send", response.status, body);
    return { ok: false, status: response.status, detail: explainResendError(response.status, body) };
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
