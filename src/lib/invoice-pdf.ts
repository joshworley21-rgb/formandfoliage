import jsPDF from "jspdf";
import type { Client, Invoice, InvoiceItem, Settings } from "@/lib/crm";
import { LOGO_URL } from "@/components/Logo";

const INK: [number, number, number] = [23, 33, 30];
const MUTED: [number, number, number] = [110, 118, 114];
const BAND: [number, number, number] = [238, 243, 240];
const RULE: [number, number, number] = [206, 213, 209];

function gbp(n: number | string | null | undefined) {
  return `£${Number(n ?? 0).toLocaleString("en-GB", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

export function prettyDate(value: string | null | undefined) {
  if (!value) return "";
  const date = new Date(`${value}T00:00:00`);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

/** Split "Label: value" lines from the free-text bank details field. */
export function parseBankDetails(text: string | null | undefined): Array<[string, string]> {
  if (!text) return [];
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const idx = line.indexOf(":");
      return idx === -1
        ? (["", line] as [string, string])
        : ([line.slice(0, idx).trim(), line.slice(idx + 1).trim()] as [string, string]);
    });
}

async function loadLogo(): Promise<{ dataUrl: string; width: number; height: number } | null> {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 6000);
    const response = await fetch(LOGO_URL, { signal: controller.signal });
    clearTimeout(timer);
    const blob = await response.blob();
    const bitmap = await createImageBitmap(blob);
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.drawImage(bitmap, 0, 0);
    return { dataUrl: canvas.toDataURL("image/png"), width: bitmap.width, height: bitmap.height };
  } catch {
    return null;
  }
}

export async function buildInvoicePdf({
  invoice,
  items,
  client,
  settings,
}: {
  invoice: Invoice;
  items: InvoiceItem[];
  client: Client;
  settings: Settings | null;
}): Promise<jsPDF> {
  const doc = new jsPDF({ unit: "pt", format: "a4" });
  const pageWidth = doc.internal.pageSize.getWidth();
  const left = 48;
  const right = pageWidth - 48;
  const business = settings?.business_name || "JW Garden Services";

  doc.setTextColor(...INK);

  // Title
  doc.setFont("helvetica", "bold");
  doc.setFontSize(26);
  doc.text("Invoice", left, 78);

  // Logo (top right)
  const logo = await loadLogo();
  if (logo) {
    const logoHeight = 52;
    const logoWidth = (logo.width / logo.height) * logoHeight;
    doc.addImage(logo.dataUrl, "PNG", right - logoWidth, 44, logoWidth, logoHeight);
  }

  // Meta rows
  let y = 102;
  const metaValueX = left + 108;
  const meta: Array<[string, string]> = [
    ["Invoice number", invoice.number],
    ["Invoice date", prettyDate(invoice.issue_date)],
    ["Due date", invoice.due_date ? prettyDate(invoice.due_date) : "On receipt"],
  ];
  doc.setFontSize(9.5);
  for (const [label, value] of meta) {
    doc.setFont("helvetica", "bold");
    doc.text(label, left, y);
    doc.setFont("helvetica", "normal");
    doc.text(value, metaValueX, y);
    y += 15;
  }

  // Billed to / from
  const blockTop = y + 22;
  const rightColX = left + (right - left) / 2 + 10;

  doc.setFont("helvetica", "bold");
  doc.setFontSize(12);
  doc.text("Billed to", left, blockTop);

  doc.setFontSize(10.5);
  const fromLines = doc.splitTextToSize(business, right - rightColX) as string[];
  doc.text(fromLines, rightColX, blockTop);

  doc.setFont("helvetica", "normal");
  doc.setFontSize(9.5);

  let leftY = blockTop + 17;
  doc.text(client.name, left, leftY);
  if (client.address) {
    leftY += 16;
    const lines = client.address
      .split("\n")
      .flatMap((line) => doc.splitTextToSize(line.trim(), 200) as string[]);
    doc.text(lines, left, leftY);
    leftY += (lines.length - 1) * 13;
  }

  let rightY = blockTop + fromLines.length * 15 + 3;
  if (settings?.address) {
    const lines = settings.address
      .split("\n")
      .flatMap((line) => doc.splitTextToSize(line.trim(), right - rightColX) as string[]);
    doc.text(lines, rightColX, rightY);
    rightY += lines.length * 13;
  }
  if (settings?.phone) {
    rightY += 6;
    doc.text(settings.phone, rightColX, rightY);
    rightY += 13;
  }
  // The contact address on the invoice is where a customer should write, which
  // is the reply-to inbox rather than the address the email was sent from.
  const contactEmail = settings?.reply_to_email || settings?.email;
  if (contactEmail) {
    rightY += 6;
    doc.text(contactEmail, rightColX, rightY);
    rightY += 13;
  }

  y = Math.max(leftY, rightY) + 30;

  // Invoice description
  if (invoice.notes) {
    doc.setFont("helvetica", "bold");
    doc.setFontSize(9.5);
    doc.text("Invoice description", left, y);
    doc.setFont("helvetica", "normal");
    y += 14;
    const noteLines = doc.splitTextToSize(invoice.notes, right - left) as string[];
    doc.text(noteLines, left, y);
    y += noteLines.length * 13 + 12;
  }

  // Table
  const colQty = right - 250;
  const colUnit = right - 130;
  const colAmount = right;
  const headerTop = y;
  doc.setFillColor(...BAND);
  doc.rect(left, headerTop, right - left, 26, "F");
  doc.setFont("helvetica", "bold");
  doc.setFontSize(9.5);
  const headerBaseline = headerTop + 17;
  doc.text("Description", left + 12, headerBaseline);
  doc.text("Qty", colQty, headerBaseline, { align: "right" });
  doc.text("Unit price (GBP)", colUnit, headerBaseline, { align: "right" });
  doc.text("Total (GBP)", colAmount - 12, headerBaseline, { align: "right" });

  doc.setFont("helvetica", "normal");
  y = headerTop + 48;

  for (const item of items) {
    // A grouped line carries a date so the invoice can sort on it, but names
    // every day it covers in its own description. Printing the date here as
    // well would put the first of them twice on one line.
    const desc =
      item.item_date && !item.grouped
        ? `${prettyDate(item.item_date)} — ${item.description}`
        : item.description;
    const lines = doc.splitTextToSize(desc, colQty - left - 30) as string[];
    const rowHeight = Math.max(26, lines.length * 13 + 14);
    if (y + rowHeight > 740) {
      doc.addPage();
      y = 72;
    }
    doc.setTextColor(...INK);
    doc.text(lines, left + 12, y);
    doc.text(String(Number(item.quantity)), colQty, y, { align: "right" });
    doc.text(gbp(item.unit_price), colUnit, y, { align: "right" });
    doc.text(gbp(item.amount), colAmount - 12, y, { align: "right" });
    y += rowHeight;
  }

  // Totals first, then payment details beneath them.
  //
  // These used to sit side by side, which only worked while the bank
  // details stayed narrow. A trading name long enough to wrap runs under
  // the totals column and collides with the Total line, so the two blocks
  // are stacked and the payment details get the full width.
  const sectionTop = y + 14;
  let totalsY = sectionTop;
  doc.setDrawColor(...RULE);
  doc.line(colQty - 20, totalsY, right, totalsY);
  totalsY += 20;
  doc.setFont("helvetica", "normal");
  doc.setFontSize(10);
  if (Number(invoice.vat_amount) > 0) {
    doc.text("Subtotal", colQty - 20, totalsY);
    doc.text(gbp(invoice.subtotal), colAmount - 12, totalsY, { align: "right" });
    totalsY += 16;
    doc.text(`VAT (${settings?.vat_rate ?? 20}%)`, colQty - 20, totalsY);
    doc.text(gbp(invoice.vat_amount), colAmount - 12, totalsY, { align: "right" });
    totalsY += 16;
  }
  doc.setFont("helvetica", "bold");
  doc.text("Total", colQty - 20, totalsY);
  doc.text(gbp(invoice.total), colAmount - 12, totalsY, { align: "right" });
  totalsY += 20;

  let payY = totalsY + 16;
  const bankRows = parseBankDetails(settings?.bank_details);
  if (bankRows.length) {
    doc.setFont("helvetica", "bold");
    doc.setFontSize(12);
    doc.setTextColor(...INK);
    doc.text("Payment details", left, payY);
    payY += 20;
    doc.setFontSize(9.5);
    for (const [label, value] of bankRows) {
      doc.setFont("helvetica", "bold");
      if (label) doc.text(`${label}:`, left, payY);
      doc.setFont("helvetica", "normal");
      const valueLines = doc.splitTextToSize(value, right - (left + 110)) as string[];
      doc.text(valueLines, left + 110, payY);
      payY += valueLines.length * 13 + 2;
    }
    doc.setFont("helvetica", "bold");
    doc.text("Payment reference:", left, payY);
    doc.setFont("helvetica", "normal");
    doc.text(invoice.number, left + 110, payY);
    payY += 15;
  }

  // Due banner
  let bannerY = Math.max(payY, totalsY) + 22;
  if (bannerY > 730) {
    doc.addPage();
    bannerY = 80;
  }
  doc.setFillColor(...BAND);
  doc.rect(left, bannerY, right - left, 44, "F");
  doc.setFont("helvetica", "bold");
  doc.setFontSize(13);
  doc.setTextColor(...INK);
  doc.text(
    `${gbp(invoice.total)} due${invoice.due_date ? ` by ${prettyDate(invoice.due_date)}` : " on receipt"}`,
    left + 14,
    bannerY + 28,
  );

  // Footer terms
  let footY = bannerY + 68;
  doc.setFont("helvetica", "normal");
  doc.setFontSize(8.5);
  doc.setTextColor(...MUTED);
  if (settings?.payment_terms) {
    const termsLines = doc.splitTextToSize(settings.payment_terms, right - left) as string[];
    doc.text(termsLines, left, footY);
    footY += termsLines.length * 11 + 6;
  }
  if (settings?.vat_registered && settings.vat_number) {
    doc.text(`VAT number: ${settings.vat_number}`, left, footY);
  }

  return doc;
}

export function pdfToBase64(doc: jsPDF): string {
  const output = doc.output("datauristring");
  return output.slice(output.indexOf(",") + 1);
}
