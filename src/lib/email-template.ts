export const DEFAULT_SUBJECT_TEMPLATE = "Invoice {{invoice_number}} from {{business_name}}";
export const DEFAULT_BODY_TEMPLATE =
  "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}";

export function fillTemplate(template: string, tokens: Record<string, string>) {
  return template.replace(/\{\{\s*(\w+)\s*\}\}/g, (_m, key: string) => tokens[key] ?? "");
}

export function escapeHtml(value: string) {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

export function templateToHtml(body: string) {
  return escapeHtml(body)
    .split(/\n{2,}/)
    .map((para) => `<p>${para.replace(/\n/g, "<br/>")}</p>`)
    .join("\n");
}

/**
 * The {{tokens}} an invoice email is written against, for a real invoice.
 *
 * send-invoice-email builds the same set on the server, and has to: the app
 * shows what is about to be sent, the server sends it. The two cannot share a
 * module across the runtime boundary, so they are kept deliberately identical
 * — a token that fills in here and not there would show one thing on the phone
 * and put another in a customer's inbox.
 *
 * Any editing done in the app is sent as finished text rather than a template,
 * so this only decides what the box is pre-filled with.
 */
export function invoiceEmailTokens({
  invoice,
  clientName,
  settings,
}: {
  invoice: {
    number?: string | null;
    total?: number | string | null;
    due_date?: string | null;
    issue_date?: string | null;
  };
  clientName: string;
  settings: {
    business_name?: string | null;
    payment_terms?: string | null;
    bank_details?: string | null;
  } | null;
}): Record<string, string> {
  const due = invoice.due_date ?? "";
  return {
    client_name: clientName,
    business_name: settings?.business_name || "JW Garden Services",
    invoice_number: invoice.number ?? "",
    total: `£${Number(invoice.total ?? 0).toFixed(2)}`,
    due_date: due,
    due_clause: due ? ` by ${due}` : "",
    issue_date: invoice.issue_date ?? "",
    payment_terms: settings?.payment_terms ?? "",
    bank_details: settings?.bank_details ?? "",
  };
}

export type InvoiceEmailDraft = { subject: string; message: string };

/**
 * The first candidate that has anything in it, whitespace not counting.
 *
 * A saved template cleared to a stray space is truthy, so a plain `||` chain
 * walks straight past the default and sends an email with nothing in it. The
 * text itself is returned untrimmed — a trailing newline someone typed is
 * theirs to keep; it is only the emptiness test that ignores spaces.
 */
export function firstNonBlank(...candidates: Array<string | null | undefined>): string {
  return candidates.find((c) => c && c.trim() !== "") ?? "";
}

/**
 * The email as the saved template would have sent it, with nothing typed.
 *
 * This is what the compose box opens on. It is the finished text rather than
 * the template, because an editor that opens on {{client_name}} is a template
 * editor, and there is already one of those in Settings.
 */
export function draftFromTemplates({
  invoice,
  clientName,
  settings,
}: {
  invoice: Parameters<typeof invoiceEmailTokens>[0]["invoice"];
  clientName: string;
  settings:
    | (Parameters<typeof invoiceEmailTokens>[0]["settings"] & {
        email_subject_template?: string | null;
        email_body_template?: string | null;
      })
    | null;
}): InvoiceEmailDraft {
  const tokens = invoiceEmailTokens({ invoice, clientName, settings });
  return {
    subject: fillTemplate(
      firstNonBlank(settings?.email_subject_template, DEFAULT_SUBJECT_TEMPLATE),
      tokens,
    ),
    message: fillTemplate(
      firstNonBlank(settings?.email_body_template, DEFAULT_BODY_TEMPLATE),
      tokens,
    ),
  };
}
