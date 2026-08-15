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
