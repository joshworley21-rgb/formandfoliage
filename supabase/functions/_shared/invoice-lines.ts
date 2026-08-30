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
  /** Null on a grouped line, which covers several days and names them in its
   *  description instead. */
  item_date: string | null;
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

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "5 Aug" from a yyyy-MM-dd. No date library: this runs on the edge. */
function dayMonth(iso: string): string {
  const [, month, day] = iso.split("-");
  return `${Number(day)} ${MONTHS[Number(month) - 1] ?? ""}`.trim();
}

/**
 * Which days a grouped line covers, in the fewest words that still answer
 * "did he actually come?".
 *
 * Listed while a list is readable, and a range once it is not. Someone
 * checking a month of visits wants the dates; someone looking at a year of
 * them wants to know it is a year.
 */
export function datesLabel(dates: string[], maxListed = 6): string {
  const sorted = [...dates].filter(Boolean).sort();
  if (sorted.length === 0) return "";
  if (sorted.length > maxListed) {
    return `${dayMonth(sorted[0]!)} to ${dayMonth(sorted[sorted.length - 1]!)}`;
  }
  // All within one month reads better as "5, 12, 19 Aug" than the month thrice.
  const months = new Set(sorted.map((d) => d.slice(0, 7)));
  if (months.size === 1) {
    const [, month] = sorted[0]!.split("-");
    const days = sorted.map((d) => Number(d.split("-")[2]));
    return `${days.join(", ")} ${MONTHS[Number(month) - 1] ?? ""}`.trim();
  }
  return sorted.map(dayMonth).join(", ");
}

/**
 * Collapses repeat visits that cost the same into one line with a quantity.
 *
 * A fortnightly round produced a line per visit, all saying the same thing for
 * the same money: six rows to say "six mows". They become "4 visits" at the
 * price of one, which is how anybody would write it out by hand.
 *
 * Two lines merge only when their wording and their figures both match. Same
 * money is not enough on its own — a full day and a normal visit that happen
 * to come to the same total are different work, and so are two visits
 * described differently. Anything that does not match is left exactly as it
 * was, which is what keeps the odd one out visible.
 *
 * The grouped line keeps one of its visits' ids. It has to keep a real one: a
 * null visit_id is how a typed retainer line is told apart from a computed
 * one, and a grouped line that looked typed would survive a rebuild while its
 * replacements were inserted alongside it.
 */
export function groupLines(lines: InvoiceLine[]): InvoiceLine[] {
  const groups = new Map<string, InvoiceLine[]>();
  for (const line of lines) {
    const key = `${line.description}|${line.amount}|${line.unit_price}|${line.quantity}`;
    const existing = groups.get(key);
    if (existing) existing.push(line);
    else groups.set(key, [line]);
  }

  const merged = [...groups.values()].map((group) => {
    const first = group[0]!;
    if (group.length === 1) return first;
    // Sorted, because item_date below takes the first of these and "first"
    // has to mean earliest rather than whichever visit was loaded first.
    const dates = group
      .map((l) => l.item_date)
      .filter((d): d is string => Boolean(d))
      .sort();
    const label = datesLabel(dates);
    return {
      visit_id: first.visit_id,
      // The earliest of the days it covers, rather than nothing. A grouped
      // line has no single date, but item_date is the only thing an invoice's
      // lines are ordered by — and Postgres sorts nulls last, so a null here
      // would drop the whole round to the bottom of the invoice, underneath
      // work that happened after it. Every date is named in the description
      // regardless.
      item_date: dates[0] ?? first.item_date,
      description: `${first.description} — ${group.length} visits${label ? `: ${label}` : ""}`,
      quantity: group.length,
      unit_price: first.amount,
      amount: round2(first.amount * group.length),
    };
  });

  // Still in date order, so the invoice reads down the month as it did before.
  const sortKeys = [...groups.values()].map(
    (group) => group.map((l) => l.item_date ?? "").sort()[0] ?? "",
  );
  return merged
    .map((line, i) => ({ line, at: sortKeys[i] ?? "" }))
    .sort((a, b) => a.at.localeCompare(b.at))
    .map((entry) => entry.line);
}
