import jsPDF from "jspdf";
import type { Client, Invoice, InvoiceItem, Settings } from "@/lib/crm";

const GREEN: [number, number, number] = [58, 122, 63];
const BROWN: [number, number, number] = [66, 48, 33];
const CREAM: [number, number, number] = [250, 248, 242];
const MUTED: [number, number, number] = [120, 115, 105];
const RULE: [number, number, number] = [215, 210, 198];

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

  // Header bar
  doc.setFillColor(...GREEN);
  doc.rect(0, 0, pageWidth, 88, "F");
  doc.setTextColor(255, 255, 255);
  doc.setFont("helvetica", "bold");
  doc.setFontSize(20);
  doc.text(business, left, 46);
  doc.setFont("helvetica", "normal");
  doc.setFontSize(9);
  const contact = [settings?.address, settings?.phone, settings?.email].filter(Boolean).join("  ·  ");
  if (contact) doc.text(contact, left, 64);
  doc.setFont("helvetica", "bold");
  doc.setFontSize(17);
  doc.text("INVOICE", right, 46, { align: "right" });
  doc.setFont("helvetica", "normal");
  doc.setFontSize(10);
  doc.text(invoice.number, right, 64, { align: "right" });

  // Client & details
  doc.setTextColor(...BROWN);
  let y = 128;
  doc.setFont("helvetica", "bold");
  doc.setFontSize(10);
  doc.text("Bill to", left, y);
  doc.text("Invoice details", right - 180, y);
  doc.setFont("helvetica", "normal");
  doc.setFontSize(10);
  y += 16;
  doc.text(client.name, left, y);
  doc.text(`Issued: ${invoice.issue_date}`, right - 180, y);
  y += 14;
  if (client.address) {
    const addressLines = doc.splitTextToSize(client.address, 220) as string[];
    doc.text(addressLines, left, y);
    y += addressLines.length > 1 ? (addressLines.length - 1) * 13 : 0;
  }
  doc.text(`Due: ${invoice.due_date ?? "on receipt"}`, right - 180, y);
  if (invoice.period_start && invoice.period_end) {
    y += 14;
    doc.text(`Period: ${invoice.period_start} – ${invoice.period_end}`, right - 180, y);
  }

  // Table
  y += 36;
  const tableTop = y - 16;
  const tableBottom = tableTop + 22;
  doc.setFillColor(...CREAM);
  doc.rect(left, tableTop, right - left, 22, "F");
  doc.setFont("helvetica", "bold");
  doc.setFontSize(9);

  const colDate = left + 6;
  const colDesc = left + 86;
  const colQty = right - 164;
  const colRate = right - 92;
  const colAmount = right - 6;

  doc.text("Date", colDate, y);
  doc.text("Description", colDesc, y);
  doc.text("Qty", colQty, y, { align: "right" });
  doc.text("Rate", colRate, y, { align: "right" });
  doc.text("Amount", colAmount, y, { align: "right" });

  doc.setFont("helvetica", "normal");
  y += 24;

  for (const item of items) {
    const lines = doc.splitTextToSize(item.description, 240) as string[];
    const rowHeight = Math.max(15, lines.length * 12.5);
    if (y + rowHeight > 700) {
      doc.addPage();
      y = 64;
    }
    doc.text(item.item_date ?? "", colDate, y);
    doc.text(lines, colDesc, y);
    doc.text(String(Number(item.quantity)), colQty, y, { align: "right" });
    doc.text(gbp(item.unit_price), colRate, y, { align: "right" });
    doc.text(gbp(item.amount), colAmount, y, { align: "right" });
    y += rowHeight;
  }

  // Totals
  y += 8;
  doc.setDrawColor(...RULE);
  doc.line(right - 220, y, right, y);
  y += 18;
  doc.setFont("helvetica", "normal");
  doc.setFontSize(10);
  doc.text("Subtotal", colRate, y, { align: "right" });
  doc.text(gbp(invoice.subtotal), colAmount, y, { align: "right" });
  if (Number(invoice.vat_amount) > 0) {
    y += 15;
    doc.text(`VAT (${settings?.vat_rate ?? 20}%)`, colRate, y, { align: "right" });
    doc.text(gbp(invoice.vat_amount), colAmount, y, { align: "right" });
  }
  y += 22;
  doc.setFont("helvetica", "bold");
  doc.setFontSize(12);
  doc.text("Total due", colRate, y, { align: "right" });
  doc.text(gbp(invoice.total), colAmount, y, { align: "right" });

  // Footer notes
  doc.setFont("helvetica", "normal");
  doc.setFontSize(9);
  doc.setTextColor(...MUTED);
  y += 36;
  if (settings?.payment_terms) {
    const termsLines = doc.splitTextToSize(settings.payment_terms, right - left) as string[];
    doc.text(termsLines, left, y);
    y += termsLines.length * 12 + 10;
  }
  if (settings?.bank_details) {
    doc.setFont("helvetica", "bold");
    doc.setTextColor(...BROWN);
    doc.text("Payment details", left, y);
    doc.setFont("helvetica", "normal");
    doc.setTextColor(...MUTED);
    y += 13;
    const bankLines = doc.splitTextToSize(settings.bank_details, right - left) as string[];
    doc.text(bankLines, left, y);
    y += bankLines.length * 12 + 10;
  }
  if (invoice.notes) {
    const noteLines = doc.splitTextToSize(invoice.notes, right - left) as string[];
    doc.text(noteLines, left, y);
    y += noteLines.length * 12 + 10;
  }
  if (settings?.vat_registered && settings.vat_number) {
    doc.setTextColor(...BROWN);
    doc.text(`VAT number: ${settings.vat_number}`, left, y);
  }

  return doc;
}

export function pdfToBase64(doc: jsPDF): string {
  const output = doc.output("datauristring");
  return output.slice(output.indexOf(",") + 1);
}
