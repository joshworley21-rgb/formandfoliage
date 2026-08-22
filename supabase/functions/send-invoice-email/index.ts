// Stores the invoice PDF and emails it to the client.
//
// Ported from the sendInvoiceEmail server function. The PDF is still rendered on
// the device with jsPDF and arrives here base64-encoded; this function holds the
// Resend key, which must never reach the app.
import { requireUser } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { isEmailConfigured, sendEmail } from "../_shared/email.ts";
import {
  DEFAULT_BODY_TEMPLATE,
  DEFAULT_BUSINESS_NAME,
  DEFAULT_FROM_EMAIL,
  DEFAULT_SUBJECT_TEMPLATE,
  fillTemplate,
  textToHtml,
} from "../_shared/templates.ts";

type SendInvoiceInput = {
  invoiceId?: string;
  pdfBase64?: string;
  message?: string | null;
};

Deno.serve(
  serveJson(async (request) => {
    const { userId, supabase } = await requireUser(request);
    const input = (await request.json()) as SendInvoiceInput;
    if (!input?.invoiceId) throw new Error("invoiceId is required");
    if (!input?.pdfBase64) throw new Error("Invoice PDF is missing");
    const data = {
      invoiceId: input.invoiceId,
      pdfBase64: input.pdfBase64,
      message: input.message ?? null,
    };

    const { data: invoice, error } = await supabase
      .from("invoices")
      .select("*, clients(name, email)")
      .eq("id", data.invoiceId)
      .single();
    if (error || !invoice) throw new Error("Invoice not found");

    const client = invoice.clients as { name: string; email: string | null } | null;

    const logEvent = async (
      status: "sent" | "failed" | "skipped",
      detail: string | null,
      providerMessageId?: string | null,
    ) => {
      await supabase.from("invoice_email_events").insert({
        user_id: userId,
        invoice_id: data.invoiceId,
        status,
        recipient: client?.email ?? null,
        provider_message_id: providerMessageId ?? null,
        detail,
      });
    };

    if (!client?.email) {
      await logEvent("failed", "This client has no email address saved");
      throw new Error("This client has no email address saved");
    }

    const { data: settings } = await supabase
      .from("settings")
      .select("*")
      .eq("user_id", userId)
      .maybeSingle();

    const businessName = settings?.business_name || DEFAULT_BUSINESS_NAME;
    const fromEmail = settings?.email || DEFAULT_FROM_EMAIL;
    const path = `${userId}/${invoice.id}.pdf`;
    const bytes = Uint8Array.from(atob(data.pdfBase64), (c) => c.charCodeAt(0));

    await supabase.storage.from("invoices").upload(path, bytes, {
      contentType: "application/pdf",
      upsert: true,
    });

    if (!isEmailConfigured()) {
      await logEvent("skipped", "Email is not connected — Resend is not set up yet.");
      return json({
        sent: false,
        reason: "Email is not connected yet — connect Resend in Settings to send invoices by email.",
      });
    }

    const tokens: Record<string, string> = {
      client_name: client.name ?? "",
      business_name: businessName,
      invoice_number: invoice.number ?? "",
      total: `£${Number(invoice.total).toFixed(2)}`,
      due_date: invoice.due_date ?? "",
      due_clause: invoice.due_date ? ` by ${invoice.due_date}` : "",
      issue_date: invoice.issue_date ?? "",
      payment_terms: settings?.payment_terms ?? "",
      bank_details: settings?.bank_details ?? "",
    };

    const subject = fillTemplate(
      settings?.email_subject_template || DEFAULT_SUBJECT_TEMPLATE,
      tokens,
    );
    const htmlBody = textToHtml(
      fillTemplate(data.message || settings?.email_body_template || DEFAULT_BODY_TEMPLATE, tokens),
    );

    const result = await sendEmail({
      from: `${businessName} <${fromEmail}>`,
      to: client.email,
      subject,
      html: htmlBody,
      attachments: [
        {
          filename: `${invoice.number}.pdf`,
          content: data.pdfBase64,
          contentType: "application/pdf",
        },
      ],
    });

    if (!result.ok) {
      console.error("Resend send error", result.status, result.detail);
      await logEvent("failed", `${result.status}: ${result.detail}`.slice(0, 500));
      throw new Error(`Resend could not send the email. ${result.status}: ${result.detail}`);
    }

    await supabase
      .from("invoices")
      .update({ status: "sent", sent_at: new Date().toISOString(), pdf_path: path })
      .eq("id", invoice.id);

    await logEvent("sent", `Delivered to ${client.email} via Resend`, result.id);

    return json({ sent: true, to: client.email });
  }),
);
