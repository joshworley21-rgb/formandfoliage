// The matcher decides what the review list says about each payment, so these
// tests are mostly about the sentence being true — a suggestion that overstates
// its confidence is the failure mode that costs money.
import { expect, test } from "bun:test";
import { suggestMatch, type MatchCandidate } from "./matching.ts";

const SEVEN: MatchCandidate = { id: "i7", number: "JW-0007", total: 45, clientName: "Jane Bell" };
const EIGHT: MatchCandidate = { id: "i8", number: "JW-0008", total: 120, clientName: "Tom Rowe" };

test("a reference naming the invoice matches however it was typed", () => {
  for (const reference of ["JW-0007", "jw0007", "JW 0007", "inv jw-0007 thanks"]) {
    const match = suggestMatch({ amount: 45, reference }, [SEVEN, EIGHT]);
    expect(match?.invoiceId).toBe("i7");
  }
});

test("the reference wins even when the amount is wrong, and the sentence says so", () => {
  const match = suggestMatch({ amount: 25, reference: "JW-0007" }, [SEVEN, EIGHT]);
  expect(match?.invoiceId).toBe("i7");
  expect(match?.reason).toBe(
    "The reference says JW-0007, but £25.00 is £20.00 short of the £45.00 owed",
  );
});

test("an overpayment against a named invoice reads as more, not short", () => {
  const match = suggestMatch({ amount: 50, reference: "JW-0007" }, [SEVEN]);
  expect(match?.reason).toContain("£5.00 more than the £45.00 owed");
});

test("a bare number finds the invoice when only one answers to it", () => {
  const match = suggestMatch({ amount: 45, reference: "payment 7" }, [SEVEN, EIGHT]);
  expect(match?.invoiceId).toBe("i7");
});

test("a bare number does not reach inside a longer number", () => {
  const seventeen: MatchCandidate = { id: "i17", number: "JW-0017", total: 60 };
  // The digits have to be the whole number, not a piece of one: "7" is invoice
  // seven and "17" is invoice seventeen, and neither is the other.
  expect(suggestMatch({ amount: 99, reference: "7" }, [SEVEN, seventeen])?.invoiceId).toBe("i7");
  expect(suggestMatch({ amount: 99, reference: "17" }, [SEVEN, seventeen])?.invoiceId).toBe("i17");
});

test("a bare number is ignored when two invoices answer to it", () => {
  // Same digits behind different prefixes — nothing here says which was meant,
  // and the amount matches neither.
  const otherSeven: MatchCandidate = { id: "i7b", number: "INV-7", total: 60 };
  const match = suggestMatch({ amount: 99, reference: "ref 7" }, [SEVEN, otherSeven]);
  expect(match).toBeNull();
});

test("the reference is read from the description too, not just the reference field", () => {
  const match = suggestMatch({ amount: 45, description: "FASTER PAYMENT JW-0007" }, [SEVEN]);
  expect(match?.invoiceId).toBe("i7");
});

test("with no reference, an amount only one invoice is owed is the match", () => {
  const match = suggestMatch({ amount: 120 }, [SEVEN, EIGHT]);
  expect(match?.invoiceId).toBe("i8");
  expect(match?.reason).toBe("£120.00 is exactly what JW-0008 is owed");
});

test("an amount two invoices share suggests neither and names both", () => {
  const twin: MatchCandidate = { id: "i9", number: "JW-0009", total: 45 };
  const match = suggestMatch({ amount: 45 }, [SEVEN, twin]);
  expect(match?.invoiceId).toBeNull();
  expect(match?.reason).toBe("£45.00 matches 2 unpaid invoices: JW-0007, JW-0009");
});

test("the payer's name matches when they owe exactly one thing", () => {
  const match = suggestMatch({ amount: 30, counterparty: "MRS JANE BELL" }, [SEVEN, EIGHT]);
  expect(match?.invoiceId).toBe("i7");
  expect(match?.reason).toBe(
    "From MRS JANE BELL, whose only unpaid invoice is JW-0007, though that is £15.00 short of the £45.00 owed",
  );
});

test("the payer's name is not used when they owe two invoices", () => {
  const other: MatchCandidate = { id: "i9", number: "JW-0009", total: 30, clientName: "Jane Bell" };
  const match = suggestMatch({ amount: 12, counterparty: "Jane Bell" }, [SEVEN, other]);
  expect(match).toBeNull();
});

test("a short name inside a longer unrelated one is not a person", () => {
  const lee: MatchCandidate = { id: "i9", number: "JW-0009", total: 30, clientName: "Lee" };
  const match = suggestMatch({ amount: 12, counterparty: "LEEDS CITY COUNCIL" }, [lee]);
  expect(match).toBeNull();
});

test("nothing owed means nothing to suggest", () => {
  expect(suggestMatch({ amount: 45, reference: "JW-0007" }, [])).toBeNull();
});

test("half a penny of currency rounding still counts as exact", () => {
  const match = suggestMatch({ amount: 45.001 }, [SEVEN, EIGHT]);
  expect(match?.invoiceId).toBe("i7");
});
