// Layout guard for the invoice PDF.
//
// The payment details and the totals column used to sit side by side, and a
// trading name long enough to wrap ran straight through the Total line. That
// is invisible to a typecheck and easy to miss by eye, so this records every
// piece of text jsPDF is asked to draw and asserts the two blocks never share
// space.
//
//   bun test src/lib/invoice-pdf.test.ts
import { expect, mock, test } from "bun:test";
import jsPDF from "jspdf";
import type { Client, Invoice, InvoiceItem, Settings } from "@/lib/crm";

type Drawn = { text: string; x: number; y: number; align?: string };
let drawn: Drawn[] = [];

// jsPDF defines text() on the instance, so wrap the constructor and patch each
// document as it is created.
const RealPdf = jsPDF as unknown as new (...args: unknown[]) => Record<string, unknown>;
class RecordingPdf extends RealPdf {
  constructor(...args: unknown[]) {
    super(...args);
    const real = (this["text"] as Function).bind(this);
    this["text"] = (txt: string | string[], x: number, y: number, opts?: { align?: string }) => {
      for (const [i, line] of (Array.isArray(txt) ? txt : [txt]).entries()) {
        const at: Drawn = { text: String(line), x, y: y + i * 13 };
        if (opts?.align) at.align = opts.align;
        drawn.push(at);
      }
      return real(txt, x, y, opts);
    };
  }
}
mock.module("jspdf", () => ({ default: RecordingPdf, jsPDF: RecordingPdf }));

const { buildInvoicePdf } = await import("@/lib/invoice-pdf");

const DAY = "2026-08-24";

function render(over: { vat?: boolean; bankDetails?: string } = {}) {
  drawn = [];
  const vat = over.vat ?? false;
  return buildInvoicePdf({
    invoice: {
      id: "i1", user_id: "u", client_id: "c", number: "JW-0001", status: "draft",
      issue_date: DAY, due_date: "2026-09-07", subtotal: 280,
      vat_amount: vat ? 56 : 0, total: vat ? 336 : 280,
      notes: null, sent_at: null, paid_at: null, pdf_path: null,
      created_at: DAY, updated_at: DAY,
    } as unknown as Invoice,
    items: [{
      id: "it1", user_id: "u", invoice_id: "i1",
      description: "24 Aug 2026 — Garden maintenance",
      quantity: 8, unit_price: 35, amount: 280, sort_order: 0, created_at: DAY,
    }] as unknown as InvoiceItem[],
    client: {
      id: "c", user_id: "u", name: "Test Josh", email: "a@b.com", address: null,
      phone: null, notes: null, billing_mode: "hourly", hourly_rate: 35, fixed_price: 0,
      active: true, created_at: DAY, updated_at: DAY,
    } as unknown as Client,
    settings: {
      user_id: "u", business_name: "JW Garden Services",
      address: "24 Portland Close\nBierton\nHP22 7DG",
      email: "info@jw-gardenservices.co.uk", reply_to_email: null, phone: null,
      bank_details: over.bankDetails ??
        "Account name: Joshua Worley trading as JW Garden Services\nSort code: 04-00-03\nAccount number: 64775630",
      payment_terms: "Payment due within 14 days.", invoice_prefix: "JW-", invoice_counter: 1,
      vat_registered: vat, vat_rate: 20, vat_number: vat ? "GB123456789" : null,
      quote_prefix: "Q-", quote_counter: 0, quote_terms: null, quote_validity_days: 30,
      public_site_url: null, enquiry_key: "k",
      email_subject_template: "", email_body_template: "",
      created_at: DAY, updated_at: DAY,
    } as unknown as Settings,
  });
}

/** Rough width; enough to catch a real collision without font metrics. */
const spanOf = (d: Drawn) => {
  const w = d.text.length * 5.2;
  return d.align === "right" ? [d.x - w, d.x] : [d.x, d.x + w];
};

function overlaps() {
  const totals = drawn.filter(
    (d) => ["Total", "Subtotal"].includes(d.text) || d.text.startsWith("VAT (") ||
      (d.align === "right" && d.text.startsWith("£")),
  );
  const start = drawn.findIndex((d) => d.text === "Payment details");
  const payment = start >= 0 ? drawn.slice(start) : [];

  const found: string[] = [];
  for (const t of totals) {
    for (const p of payment) {
      if (Math.abs(t.y - p.y) >= 11) continue;
      const [a1, a2] = spanOf(t);
      const [b1, b2] = spanOf(p);
      if (a1 < b2 && b1 < a2) found.push(`"${p.text}" runs into "${t.text}" at y≈${t.y.toFixed(0)}`);
    }
  }
  return found;
}

test("payment details clear the totals column", async () => {
  await render();
  expect(overlaps()).toEqual([]);
});

test("payment details clear the totals column with VAT lines", async () => {
  await render({ vat: true });
  expect(overlaps()).toEqual([]);
});

test("a very long account name does not reach the totals", async () => {
  await render({
    bankDetails:
      "Account name: Joshua Worley trading as JW Garden Services Grounds and Garden Care Limited\nSort code: 04-00-03\nAccount number: 64775630",
  });
  expect(overlaps()).toEqual([]);
});

test("payment details sit below the total, not beside it", async () => {
  await render();
  const total = drawn.find((d) => d.text === "Total");
  const heading = drawn.find((d) => d.text === "Payment details");
  expect(total).toBeDefined();
  expect(heading).toBeDefined();
  expect(heading!.y).toBeGreaterThan(total!.y);
});
