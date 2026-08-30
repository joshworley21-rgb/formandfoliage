// These lines are printed on a document a customer is asked to pay, so the
// property worth pinning down is not the wording but the arithmetic: every
// line must multiply out, and the lines for a visit must still add up to what
// that visit was always worth. Break the second and the invoice total stops
// matching the books.
import { expect, test } from "bun:test";
import {
  datesLabel,
  groupLines,
  invoiceTotals,
  linesForVisit,
  type VisitForInvoice,
} from "./invoice-lines.ts";

const visit = (over: Partial<VisitForInvoice> = {}): VisitForInvoice => ({
  id: "v1",
  visit_date: "2026-08-12",
  hours: 3,
  description: "Mow, edge, hedge front",
  materials_cost: 0,
  materials_note: null,
  amount: 90,
  ...over,
});

const HOURLY = { fixedPrice: false, hourlyRate: 30 };
const FIXED = { fixedPrice: true, hourlyRate: 0 };

test("an hourly visit with no materials is one line that multiplies out", () => {
  const [labour, ...rest] = linesForVisit(visit(), HOURLY);
  expect(rest).toEqual([]);
  expect(labour!.quantity * labour!.unit_price).toBe(labour!.amount);
  expect(labour!.amount).toBe(90);
});

test("materials come out as their own line", () => {
  const lines = linesForVisit(visit({ materials_cost: 5, amount: 95 }), HOURLY);
  expect(lines).toHaveLength(2);
  expect(lines[1]!.description).toBe("Materials");
  expect(lines[1]!.quantity).toBe(1);
  expect(lines[1]!.unit_price).toBe(5);
  expect(lines[1]!.amount).toBe(5);
});

test("the labour line goes back to multiplying out once materials are split off", () => {
  // This is the bug: one line said 3 x £30.00 = £95.00.
  const [labour] = linesForVisit(visit({ materials_cost: 5, amount: 95 }), HOURLY);
  expect(labour!.quantity).toBe(3);
  expect(labour!.unit_price).toBe(30);
  expect(labour!.amount).toBe(90);
  expect(labour!.quantity * labour!.unit_price).toBe(labour!.amount);
});

test("the lines still add up to the visit, so no total moves", () => {
  const v = visit({ materials_cost: 12.5, amount: 102.5 });
  const total = linesForVisit(v, HOURLY).reduce((sum, l) => sum + l.amount, 0);
  expect(Math.round(total * 100) / 100).toBe(v.amount);
});

test("the note names the materials on the invoice", () => {
  const lines = linesForVisit(
    visit({ materials_cost: 18, amount: 108, materials_note: "2 bags compost, bark" }),
    HOURLY,
  );
  expect(lines[1]!.description).toBe("Materials — 2 bags compost, bark");
});

test("a blank note falls back to plain Materials rather than a dangling dash", () => {
  for (const note of ["", "   ", null]) {
    const lines = linesForVisit(
      visit({ materials_cost: 4, amount: 94, materials_note: note }),
      HOURLY,
    );
    expect(lines[1]!.description).toBe("Materials");
  }
});

test("a day-rate visit bills one unit at what is left after materials", () => {
  const lines = linesForVisit(visit({ materials_cost: 20, amount: 200 }), FIXED);
  expect(lines[0]!.quantity).toBe(1);
  expect(lines[0]!.unit_price).toBe(180);
  expect(lines[0]!.amount).toBe(180);
  expect(lines[1]!.amount).toBe(20);
  expect(lines[0]!.amount + lines[1]!.amount).toBe(200);
});

test("a visit with no description still says what the work was", () => {
  expect(linesForVisit(visit({ description: null }), HOURLY)[0]!.description).toBe(
    "Garden maintenance",
  );
  expect(linesForVisit(visit({ description: "  " }), FIXED)[0]!.description).toBe(
    "Garden maintenance visit",
  );
});

test("a materials figure of zero adds no line", () => {
  expect(linesForVisit(visit({ materials_cost: 0 }), HOURLY)).toHaveLength(1);
});

test("a negative materials figure is ignored rather than credited", () => {
  // Nothing should be able to put a negative line on an invoice.
  const lines = linesForVisit(visit({ materials_cost: -10, amount: 90 }), HOURLY);
  expect(lines).toHaveLength(1);
  expect(lines[0]!.amount).toBe(90);
});

test("pennies do not drift when materials are split off", () => {
  const v = visit({ hours: 2.5, materials_cost: 7.35, amount: 82.35 });
  const [labour] = linesForVisit(v, HOURLY);
  expect(labour!.amount).toBe(75);
});

test("both lines carry the visit they came from", () => {
  const lines = linesForVisit(visit({ materials_cost: 5, amount: 95 }), HOURLY);
  expect(lines.every((l) => l.visit_id === "v1")).toBe(true);
  expect(lines.every((l) => l.item_date === "2026-08-12")).toBe(true);
});

test("the totals are the lines, plus VAT when registered", () => {
  expect(invoiceTotals([90, 5], 20)).toEqual({ subtotal: 95, vatAmount: 19, total: 114 });
});

test("no VAT rate means no VAT", () => {
  expect(invoiceTotals([90, 5], 0)).toEqual({ subtotal: 95, vatAmount: 0, total: 95 });
});

test("splitting a visit into two lines does not change what it totals", () => {
  // The whole safety property of the materials split, stated as a sum.
  const v = visit({ materials_cost: 12.5, amount: 102.5 });
  const split = invoiceTotals(
    linesForVisit(v, HOURLY).map((l) => l.amount),
    20,
  );
  const whole = invoiceTotals([v.amount], 20);
  expect(split).toEqual(whole);
});

test("an empty invoice totals nothing rather than NaN", () => {
  expect(invoiceTotals([], 20)).toEqual({ subtotal: 0, vatAmount: 0, total: 0 });
});

test("a rate that no longer accounts for the visit is not printed against it", () => {
  // The client's rate went up to £35 after this visit was logged at £30.
  // Printing 3 x £35.00 = £90.00 would be worse than printing no rate at all.
  const [labour] = linesForVisit(visit({ hours: 3, amount: 90 }), {
    fixedPrice: false,
    hourlyRate: 35,
  });
  expect(labour!.quantity).toBe(1);
  expect(labour!.unit_price).toBe(90);
  expect(labour!.amount).toBe(90);
});

test("an overridden total falls back to one unit rather than lying about the hours", () => {
  // Completing a job lets the total be set by hand; it need not be rate x hours.
  const [labour] = linesForVisit(visit({ hours: 3, amount: 100 }), HOURLY);
  expect(labour!.quantity).toBe(1);
  expect(labour!.unit_price).toBe(100);
});

test("a visit with no hours never divides by them", () => {
  const [labour] = linesForVisit(visit({ hours: 0, amount: 40 }), HOURLY);
  expect(labour!.quantity).toBe(1);
  expect(labour!.unit_price).toBe(40);
  expect(labour!.amount).toBe(40);
});

test("every line multiplies out, whatever the visit and whatever the rate", () => {
  const rates = [0, 30, 35, 12.5];
  const cases = [
    visit({ hours: 3, amount: 90 }),
    visit({ hours: 2.5, materials_cost: 7.35, amount: 82.35 }),
    visit({ hours: 0, amount: 40 }),
    visit({ hours: 1, materials_cost: 100, amount: 130 }),
    visit({ hours: 3.25, amount: 40.63 }),
  ];
  for (const rate of rates) {
    for (const v of cases) {
      for (const fixedPrice of [false, true]) {
        for (const line of linesForVisit(v, { fixedPrice, hourlyRate: rate })) {
          const product = Math.round(line.quantity * line.unit_price * 100) / 100;
          expect(Math.abs(product - line.amount)).toBeLessThan(0.005);
        }
      }
    }
  }
});

/* ---------------- grouping repeat visits ---------------- */

const labour = (id: string, date: string, amount: number, description = "Mow, edge") => ({
  visit_id: id,
  item_date: date,
  description,
  quantity: 1,
  unit_price: amount,
  amount,
  grouped: false,
});

test("visits that cost the same and read the same become one line", () => {
  const out = groupLines([
    labour("a", "2026-08-05", 60),
    labour("b", "2026-08-12", 60),
    labour("c", "2026-08-19", 60),
    labour("d", "2026-08-26", 60),
  ]);
  expect(out).toHaveLength(1);
  expect(out[0]!.quantity).toBe(4);
  expect(out[0]!.unit_price).toBe(60);
  expect(out[0]!.amount).toBe(240);
  expect(out[0]!.description).toBe("Mow, edge — 4 visits: 5, 12, 19, 26 Aug");
});

test("a full day at a different price stays its own line", () => {
  // The case that must not be swallowed: one visit worth more than the rest.
  const out = groupLines([
    labour("a", "2026-08-05", 60),
    labour("b", "2026-08-12", 60),
    labour("c", "2026-08-19", 240, "Full day: hedges and clearance"),
  ]);
  expect(out).toHaveLength(2);
  expect(out[0]!.quantity).toBe(2);
  expect(out[1]!.amount).toBe(240);
  expect(out[1]!.description).toBe("Full day: hedges and clearance");
  expect(out[1]!.item_date).toBe("2026-08-19");
});

test("same money but different work is not merged", () => {
  const out = groupLines([
    labour("a", "2026-08-05", 60, "Mow, edge"),
    labour("b", "2026-08-12", 60, "Hedge cut"),
  ]);
  expect(out).toHaveLength(2);
});

test("a single visit is left exactly as it was", () => {
  const one = labour("a", "2026-08-05", 60);
  expect(groupLines([one])).toEqual([one]);
});

test("a grouped line keeps a date to sort on but is flagged not to print it", () => {
  // Both halves matter. Without the date the round sinks to the bottom of the
  // invoice; without the flag the fifth of August is printed twice on one line.
  const out = groupLines([labour("a", "2026-08-05", 60), labour("b", "2026-08-12", 60)]);
  expect(out[0]!.grouped).toBe(true);
  expect(out[0]!.item_date).toBe("2026-08-05");
  expect(out[0]!.description).toContain("5, 12 Aug");
});

test("a line that stayed on its own still prints its date", () => {
  const out = groupLines([
    labour("a", "2026-08-05", 60),
    labour("b", "2026-08-12", 60),
    labour("c", "2026-08-19", 240, "Full day"),
  ]);
  expect(out[1]!.grouped).toBe(false);
  expect(out[1]!.item_date).toBe("2026-08-19");
});

test("the lines for one visit are never flagged as grouped", () => {
  const lines = linesForVisit(
    visit({ hours: 3, materials_cost: 12, materials_note: "compost", amount: 102 }),
    { fixedPrice: false, hourlyRate: 30 },
  );
  expect(lines).toHaveLength(2);
  expect(lines.every((l) => l.grouped === false)).toBe(true);
});

test("grouping never changes what the invoice comes to", () => {
  const lines = [
    labour("a", "2026-08-05", 60),
    labour("b", "2026-08-12", 60),
    labour("c", "2026-08-19", 240, "Full day"),
    labour("d", "2026-08-26", 60),
    labour("e", "2026-08-28", 17.33, "Extra hour"),
  ];
  const before = invoiceTotals(
    lines.map((l) => l.amount),
    20,
  );
  const after = invoiceTotals(
    groupLines(lines).map((l) => l.amount),
    20,
  );
  expect(after).toEqual(before);
});

test("a grouped line still multiplies out", () => {
  const out = groupLines([labour("a", "2026-08-05", 17.33), labour("b", "2026-08-12", 17.33)]);
  expect(out[0]!.quantity * out[0]!.unit_price).toBeCloseTo(out[0]!.amount, 2);
  expect(out[0]!.amount).toBe(34.66);
});

test("a grouped line is dated the first day it covers, so it sorts with the rest", () => {
  // item_date is the only column the lines are ordered by, and Postgres puts
  // nulls last: a null here would drop a whole round below later work.
  const out = groupLines([labour("b", "2026-08-12", 60), labour("a", "2026-08-05", 60)]);
  expect(out[0]!.item_date).toBe("2026-08-05");
});

test("a grouped line keeps a real visit id", () => {
  // A null one is how a typed retainer line is recognised; a grouped line that
  // looked typed would survive a rebuild and be duplicated by it.
  const out = groupLines([labour("a", "2026-08-05", 60), labour("b", "2026-08-12", 60)]);
  expect(out[0]!.visit_id).toBe("a");
});

test("the lines stay in date order", () => {
  const out = groupLines([
    labour("c", "2026-09-02", 90, "Hedge"),
    labour("a", "2026-08-05", 60),
    labour("b", "2026-08-12", 60),
  ]);
  expect(out.map((l) => l.quantity)).toEqual([2, 1]);
});

test("materials lines group by their note as well as their price", () => {
  const materials = (id: string, date: string, note: string, amount: number) => ({
    visit_id: id,
    item_date: date,
    description: `Materials — ${note}`,
    quantity: 1,
    unit_price: amount,
    amount,
    grouped: false,
  });
  const out = groupLines([
    materials("a", "2026-08-05", "compost", 12),
    materials("b", "2026-08-12", "compost", 12),
    materials("c", "2026-08-19", "bark", 12),
  ]);
  expect(out).toHaveLength(2);
  expect(out[0]!.description).toBe("Materials — compost — 2 visits: 5, 12 Aug");
});

test("a long run of visits gets a range rather than a list of dates", () => {
  const many = Array.from({ length: 9 }, (_, i) =>
    labour(String(i), `2026-0${i < 4 ? "8" : "9"}-0${(i % 4) + 1}`, 60),
  );
  expect(groupLines(many)[0]!.description).toContain("9 visits: 1 Aug to 4 Sep");
});

test("dates spanning two months name both", () => {
  expect(datesLabel(["2026-08-26", "2026-09-02"])).toBe("26 Aug, 2 Sep");
});

test("dates in one month name it once", () => {
  expect(datesLabel(["2026-08-05", "2026-08-12"])).toBe("5, 12 Aug");
});
