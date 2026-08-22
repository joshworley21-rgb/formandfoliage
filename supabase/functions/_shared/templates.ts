// Email body helpers shared by the invoice, test and reminder functions.

export function escapeHtml(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
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
export const DEFAULT_FROM_EMAIL = "onboarding@resend.dev";
