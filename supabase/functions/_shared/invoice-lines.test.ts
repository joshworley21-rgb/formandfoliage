// These lines are printed on a document a customer is asked to pay, so the
// property worth pinning down is not the wording but the arithmetic: every
// line must multiply out, and the lines for a visit must still add up to what
// that visit was always worth. Break the second and the invoice total stops
// matching the books.
import { expect, test } from "bun:test";
import { invoiceTotals, linesForVisit, type VisitForInvoice } from "./invoice-lines.ts";

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
