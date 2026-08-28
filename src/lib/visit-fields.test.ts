// The two rules worth guarding are the ones nothing on screen would catch:
// a missed visit charges nothing, and an invoiced visit's money cannot be
// rewritten from the form at all.
import { expect, test } from "bun:test";
import { visitAmount, visitMoneyFields, type VisitMoneyInput } from "./visit-fields";

const form = (over: Partial<VisitMoneyInput> = {}): VisitMoneyInput => ({
  locked: false,
  missed: false,
  payMode: "hourly",
  hours: "2",
  materials: "0",
  rate: "30",
  days: "1",
  ...over,
});

test("hourly work is the rate times the hours", () => {
  expect(visitAmount(form())).toBe(60);
});

test("materials are added on top", () => {
  expect(visitAmount(form({ materials: "12.50" }))).toBe(72.5);
});

test("a day rate ignores the hours entirely", () => {
  expect(visitAmount(form({ payMode: "day", rate: "180", days: "0.5", hours: "99" }))).toBe(90);
});

test("a missed visit is worth nothing however the form is filled in", () => {
  // The rate and hours boxes keep whatever the last visit left in them.
  expect(visitAmount(form({ missed: true, rate: "200", hours: "8", materials: "40" }))).toBe(0);
});

test("empty and nonsense figures count as zero rather than NaN", () => {
  expect(visitAmount(form({ hours: "", rate: "" }))).toBe(0);
  expect(visitAmount(form({ hours: "abc", materials: "oops" }))).toBe(0);
});

test("a third of an hour does not leave a fraction of a penny", () => {
  expect(visitAmount(form({ rate: "35", hours: "0.33" }))).toBe(11.55);
});

test("an ordinary save writes every money column", () => {
  expect(visitMoneyFields(form({ hours: "3", materials: "5" }))).toEqual({
    hours: 3,
    materials_cost: 5,
    amount: 95,
    missed: false,
  });
});

test("a missed save zeroes the hours and materials, not just the charge", () => {
  expect(visitMoneyFields(form({ missed: true, hours: "3", materials: "5" }))).toEqual({
    hours: 0,
    materials_cost: 0,
    amount: 0,
    missed: true,
  });
});

test("an invoiced visit writes no money at all", () => {
  // Not zeroes — nothing. The columns must keep what the invoice was built
  // from, and an update that names them would overwrite exactly that.
  expect(visitMoneyFields(form({ locked: true, hours: "999", materials: "999" }))).toEqual({});
});

test("an invoiced visit stays locked even when the form says missed", () => {
  expect(visitMoneyFields(form({ locked: true, missed: true }))).toEqual({});
});
