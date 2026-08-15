import { useMemo, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useClients, useInvoices } from "@/hooks/use-crm";
import {
  DEFAULT_BODY_TEMPLATE,
  DEFAULT_SUBJECT_TEMPLATE,
  fillTemplate,
  templateToHtml,
} from "@/lib/email-template";

type Props = {
  subjectTemplate: string;
  bodyTemplate: string;
  businessName: string;
  fromEmail: string;
  paymentTerms: string;
  bankDetails: string;
  invoicePrefix: string;
};

export function EmailTemplatePreview({
  subjectTemplate,
  bodyTemplate,
  businessName,
  fromEmail,
  paymentTerms,
  bankDetails,
  invoicePrefix,
}: Props) {
  const { data: clients = [] } = useClients();
  const { data: invoices = [] } = useInvoices();
  const [clientId, setClientId] = useState<string>("");

  const selected = clients.find((c) => c.id === clientId) ?? clients[0];
  const invoice = useMemo(
    () => invoices.find((i) => i.client_id === selected?.id),
    [invoices, selected?.id],
  );

  const today = new Date();
  const fallbackDue = new Date(today.getTime() + 14 * 86400000).toISOString().slice(0, 10);
  const dueDate = invoice?.due_date ?? fallbackDue;

  const tokens: Record<string, string> = {
    client_name: selected?.name ?? "Sample Client",
    business_name: businessName || "JW Garden Services",
    invoice_number: invoice?.number ?? `${invoicePrefix || "JW-"}0001`,
    total: `£${Number(invoice?.total ?? 120).toFixed(2)}`,
    due_date: dueDate,
    due_clause: dueDate ? ` by ${dueDate}` : "",
    issue_date: invoice?.issue_date ?? today.toISOString().slice(0, 10),
    payment_terms: paymentTerms,
    bank_details: bankDetails,
  };

  const subject = fillTemplate(subjectTemplate || DEFAULT_SUBJECT_TEMPLATE, tokens);
  const html = templateToHtml(fillTemplate(bodyTemplate || DEFAULT_BODY_TEMPLATE, tokens));

  return (
    <Card className="lg:col-span-2">
      <CardHeader>
        <CardTitle>Email preview</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1.5 sm:max-w-xs">
          <Label>Preview for client</Label>
          <Select value={selected?.id ?? ""} onValueChange={setClientId}>
            <SelectTrigger>
              <SelectValue placeholder="Select a client" />
            </SelectTrigger>
            <SelectContent>
              {clients.map((c) => (
                <SelectItem key={c.id} value={c.id}>
                  {c.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="overflow-hidden rounded-lg border bg-card">
          <div className="space-y-1 border-b bg-muted/40 px-4 py-3 text-sm">
            <p>
              <span className="text-muted-foreground">From: </span>
              {(businessName || "JW Garden Services") + ` <${fromEmail || "onboarding@resend.dev"}>`}
            </p>
            <p>
              <span className="text-muted-foreground">To: </span>
              {selected?.email || "no email saved for this client"}
            </p>
            <p className="font-medium">
              <span className="font-normal text-muted-foreground">Subject: </span>
              {subject}
            </p>
          </div>
          <div
            className="prose-sm space-y-3 px-4 py-4 text-sm leading-relaxed [&_p]:my-2"
            dangerouslySetInnerHTML={{ __html: html }}
          />
          <div className="border-t px-4 py-2 text-xs text-muted-foreground">
            Attachment: {(invoice?.number ?? `${invoicePrefix || "JW-"}0001`)}.pdf
          </div>
        </div>
        <p className="text-xs text-muted-foreground">
          {invoice
            ? "Using this client's most recent invoice for the placeholder values."
            : "No invoice yet for this client — showing sample figures."}
        </p>
      </CardContent>
    </Card>
  );
}
