// Email body helpers shared by the invoice, test and reminder functions.

export function escapeHtml(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/**
 * The first candidate that has anything in it, whitespace not counting.
 *
 * A template saved in Settings and later cleared to a stray space is truthy,
 * so a plain `||` chain walks past the default and sends an email with nothing
 * in it. The text is returned untrimmed — a trailing newline someone typed is
 * theirs to keep; it is only the emptiness test that ignores spaces.
 *
 * Mirrored in src/lib/email-template.ts, which decides what the app shows
 * before sending. The two must agree.
 */
export function firstNonBlank(...candidates: Array<string | null | undefined>): string {
  return candidates.find((c) => c && c.trim() !== "") ?? "";
}

/** Replace {{token}} placeholders from a lookup table; unknown tokens become "". */
export function fillTemplate(template: string, tokens: Record<string, string>): string {
  return template.replace(/\{\{\s*(\w+)\s*\}\}/g, (_match, key: string) => tokens[key] ?? "");
}

/** Wrap plain text as the simple paragraph HTML the CRM has always sent. */
export function textToHtml(text: string, extra = ""): string {
  const paragraphs = escapeHtml(text)
    .split(/\n{2,}/)
    .map((para) => `<p>${para.replace(/\n/g, "<br/>")}</p>`)
    .join("\n");
  return `<div style="font-family:Arial,sans-serif;font-size:15px;color:#243">
      ${paragraphs}${extra}
    </div>`;
}

export const DEFAULT_SUBJECT_TEMPLATE = "Invoice {{invoice_number}} from {{business_name}}";

export const DEFAULT_BODY_TEMPLATE =
  "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}";

export const DEFAULT_BUSINESS_NAME = "JW Garden Services";
/**
 * Resend's shared test sender. It can only deliver to the Resend account's own
 * address, so it is never a usable fallback for a real invoice — falling back
 * to it turns "no sending address saved" into a 403 about testing emails.
 * Exported only so a message can name it.
 */
export const RESEND_TEST_SENDER = "onboarding@resend.dev";

/** Raised when nothing is saved in Settings to send from. */
export const NO_SENDER_MESSAGE =
  "No sending address is saved. Open Settings and fill in \u201cEmails are sent from\u201d " +
  "with an address on a domain you have verified with Resend, then try again.";
