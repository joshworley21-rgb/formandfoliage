// Turning visits into the lines a customer reads on an invoice.
//
// Pulled out of create-invoice because it is arithmetic that appears on a
// document someone is asked to pay, where being wrong is both visible and
// expensive to be wrong about twice.
//
// The rule that matters: a visit's amount is labour PLUS materials, but a line
// prints as quantity x unit price = amount. Putting the whole visit on one
// line therefore printed "3 x £30.00 = £95.00" whenever anything was bought —
// sums that do not work, next to a figure being claimed. Materials get their
// own line instead, so both multiply out and the materials line can say what
// was actually bought.

export type VisitForInvoice = {
  id: string;
  visit_date: string;
  hours: number;
  description: string | null;
  materials_cost: number;
  materials_note: string | null;
  amount: number;
};

export type InvoiceLine = {
  visit_id: string | null;
  item_date: string;
  description: string;
  quantity: number;
  unit_price: number;
  amount: number;
};

const round2 = (n: number) => Math.round(n * 100) / 100;

/**
 * The lines one visit becomes: labour, and materials when there were any.
 *
 * `fixedPrice` clients are billed a price for the day rather than by the hour,
 * so their labour line is one unit at whatever is left after materials.
 */
export function linesForVisit(
  visit: VisitForInvoice,
  opts: { fixedPrice: boolean; hourlyRate: number },
): InvoiceLine[] {
  const materials = Math.max(0, Number(visit.materials_cost) || 0);
  const labour = round2(Number(visit.amount) - materials);

  // What a visit is worth is decided when it is logged and stored on the visit.
  // The hourly rate lives on the client and can be put up next spring. Print
  // the two together and the line stops multiplying out — the same fault the
  // materials split was fixing, arriving through a different door, and one
  // rebuilding an old draft after a price rise would walk straight into.
  //
  // So the rate is only shown when it genuinely accounts for the figure. When
  // it does not — the rate has moved, the visit was priced by the day, the
  // total was overridden on completion — the line is one unit at what it came
  // to. Less detail, and true.
  const hours = Number(visit.hours) || 0;
  const rate = Number(opts.hourlyRate) || 0;
  const rateExplainsIt =
    !opts.fixedPrice && hours > 0 && Math.abs(round2(hours * rate) - labour) < 0.005;

  const lines: InvoiceLine[] = [
    {
      visit_id: visit.id,
      item_date: visit.visit_date,
      description:
        visit.description?.trim() ||
        (opts.fixedPrice ? "Garden maintenance visit" : "Garden maintenance"),
      quantity: rateExplainsIt ? hours : 1,
      unit_price: rateExplainsIt ? rate : labour,
      amount: labour,
    },
  ];

  if (materials > 0) {
    const note = visit.materials_note?.trim();
    lines.push({
      visit_id: visit.id,
      item_date: visit.visit_date,
      // Named on the invoice, so "materials £18" is never a figure the
      // customer has to ring up and ask about.
      description: note ? `Materials — ${note}` : "Materials",
      quantity: 1,
      unit_price: materials,
      amount: materials,
    });
  }

  return lines;
}

/**
 * What an invoice adds up to, given every line on it.
 *
 * Shared so that rebuilding a draft cannot drift from how it was first
 * costed — two implementations of the same sum is how a total starts
 * disagreeing with the lines above it.
 */
export function invoiceTotals(
  amounts: number[],
  vatRate: number,
): { subtotal: number; vatAmount: number; total: number } {
  const subtotal = round2(amounts.reduce((sum, n) => sum + (Number(n) || 0), 0));
  const vatAmount = round2(subtotal * ((Number(vatRate) || 0) / 100));
  return { subtotal, vatAmount, total: round2(subtotal + vatAmount) };
}
