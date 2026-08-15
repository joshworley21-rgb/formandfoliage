import jsPDF from "jspdf";
import type { Client, Invoice, InvoiceItem, Settings } from "@/lib/crm";

const GREEN: [number, number, number] = [58, 122, 63];
const BROWN: [number, number, number] = [66, 48, 33];

function gbp(n: number | string | null | undefined) {
  return `£${Number(n ?? 0).toFixed(2)}`;
}

export function buildInvoicePdf({
  invoice,
  items,
  client,
  settings,
}: {
  invoice: Invoice;
  items: InvoiceItem[];
  client: Client;
  settings: Settings | null;
}): jsPDF {
  const doc = new jsPDF({ unit: "pt", format: "a4" });
  const pageWidth = doc.internal.pageSize.getWidth();
  const left = 48;
  const right = pageWidth - 48;
  const business = settings?.business_name || "JW Garden Services";

  doc.setFillColor(...GREEN);
  doc.rect(0, 0, pageWidth, 96, "F");
  doc.setTextColor(255, 255, 255);
  doc.setFont("helvetica", "bold");
  doc.setFontSize(22);
  doc.text(business, left, 48);
  doc.setFont("helvetica", "normal");
  doc.setFontSize(10);
  const contact = [settings?.address, settings?.phone, settings?.email].filter(Boolean).join("  ·  ");
  if (contact) doc.text(contact, left, 68);
  doc.setFont("helvetica", "bold");
  doc.setFontSize(18);
  doc.text("INVOICE", right, 48, { align: "right" });
  doc.setFontSize(11);
  doc.setFont("helvetica", "normal");
  doc.text(invoice.number, right, 68, { align: "right" });

  doc.setTextColor(...BROWN);
  let y = 140;
  doc.setFont("helvetica", "bold");
  doc.setFontSize(11);
  doc.text("Bill to", left, y);
  doc.text("Details", right - 180, y);
  doc.setFont("helvetica", "normal");
  doc.setFontSize(10);
  y += 16;
  doc.text(client.name, left, y);
  doc.text(`Issued: ${invoice.issue_date}`, right - 180, y);
  y += 14;
  if (client.address) {
    doc.text(doc.splitTextToSize(client.address, 220), left, y);
  }
  doc.text(`Due: ${invoice.due_date ?? "on receipt"}`, right - 180, y);
  y += 14;
  if (invoice.period_start && invoice.period_end) {
    doc.text(`Period: ${invoice.period_start} – ${invoice.period_end}`, right - 180, y);
  }

  y = 232;
  doc.setFillColor(240, 238, 230);
  doc.rect(left, y - 14, right - left, 22, "F");
  doc.setFont("helvetica", "bold");
  doc.text("Date", left + 6, y);
  doc.text("Description", left + 80, y);
  doc.text("Qty", right - 170, y, { align: "right" });
  doc.text("Rate", right - 90, y, { align: "right" });
  doc.text("Amount", right - 6, y, { align: "right" });
  doc.setFont("helvetica", "normal");
  y += 22;

  for (const item of items) {
    const lines = doc.splitTextToSize(item.description, 230) as string[];
    if (y > 700) {
      doc.addPage();
      y = 80;
    }
    doc.text(item.item_date ?? "", left + 6, y);
    doc.text(lines, left + 80, y);
    doc.text(String(Number(item.quantity)), right - 170, y, { align: "right" });
    doc.text(gbp(item.unit_price), right - 90, y, { align: "right" });
    doc.text(gbp(item.amount), right - 6, y, { align: "right" });
    y += Math.max(16, lines.length * 13);
  }

  y += 10;
  doc.setDrawColor(215, 210, 198);
  doc.line(right - 220, y, right, y);
  y += 18;
  doc.text("Subtotal", right - 120, y, { align: "right" });
  doc.text(gbp(invoice.subtotal), right - 6, y, { align: "right" });
  if (Number(invoice.vat_amount) > 0) {
    y += 16;
    doc.text(`VAT (${settings?.vat_rate ?? 20}%)`, right - 120, y, { align: "right" });
    doc.text(gbp(invoice.vat_amount), right - 6, y, { align: "right" });
  }
  y += 22;
  doc.setFont("helvetica", "bold");
  doc.setFontSize(13);
  doc.text("Total due", right - 120, y, { align: "right" });
  doc.text(gbp(invoice.total), right - 6, y, { align: "right" });

  doc.setFont("helvetica", "normal");
  doc.setFontSize(10);
  y += 40;
  if (settings?.payment_terms) {
    doc.text(doc.splitTextToSize(settings.payment_terms, right - left), left, y);
    y += 26;
  }
  if (settings?.bank_details) {
    doc.setFont("helvetica", "bold");
    doc.text("Payment details", left, y);
    doc.setFont("helvetica", "normal");
    y += 14;
    doc.text(doc.splitTextToSize(settings.bank_details, right - left), left, y);
    y += 30;
  }
  if (invoice.notes) {
    doc.text(doc.splitTextToSize(invoice.notes, right - left), left, y);
    y += 24;
  }
  if (settings?.vat_registered && settings.vat_number) {
    doc.text(`VAT number: ${settings.vat_number}`, left, y);
  }

  return doc;
}

export function pdfToBase64(doc: jsPDF): string {
  const output = doc.output("datauristring");
  return output.slice(output.indexOf(",") + 1);
}
