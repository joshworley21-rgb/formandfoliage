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

  const lines: InvoiceLine[] = [
    {
      visit_id: visit.id,
      item_date: visit.visit_date,
      description:
        visit.description?.trim() ||
        (opts.fixedPrice ? "Garden maintenance visit" : "Garden maintenance"),
      quantity: opts.fixedPrice ? 1 : Number(visit.hours),
      unit_price: opts.fixedPrice ? labour : Number(opts.hourlyRate ?? 0),
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
