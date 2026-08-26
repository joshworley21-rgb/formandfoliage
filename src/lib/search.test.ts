// The search box is only worth having if it forgives how people actually type
// on a phone: no capitals, no apostrophes, a postcode with or without its
// space. These tests are mostly about what it still finds.
import { expect, test } from "bun:test";
import { filterBy, matches, searchTerms } from "./search";

const CLIENTS = [
  { name: "Mrs J Smith", address: "12 Oak Lane\nBierton\nHP22 7DG" },
  { name: "Tom Rowe", address: "4 Elm Road\nAylesbury\nHP20 1AB" },
  { name: "José O'Brien", address: "8 Ash Close\nTring\nHP23 4PE" },
];

const fields = (client: (typeof CLIENTS)[number]) => [client.name, client.address];

test("case and punctuation in the query do not matter", () => {
  for (const query of ["smith", "SMITH", "Mrs J Smith", "mrs j. smith!"]) {
    expect(filterBy(CLIENTS, query, fields).map((c) => c.name)).toEqual(["Mrs J Smith"]);
  }
});

test("an apostrophe can be left out", () => {
  expect(filterBy(CLIENTS, "obrien", fields).map((c) => c.name)).toEqual(["José O'Brien"]);
});

test("accents can be left off", () => {
  expect(filterBy(CLIENTS, "jose", fields).map((c) => c.name)).toEqual(["José O'Brien"]);
});

test("a postcode is found with or without its space", () => {
  expect(filterBy(CLIENTS, "HP22 7DG", fields)).toHaveLength(1);
  expect(filterBy(CLIENTS, "hp227dg", fields)).toHaveLength(1);
  expect(filterBy(CLIENTS, "hp22", fields)).toHaveLength(1);
});

test("a town matches even though it is in the middle of the address", () => {
  expect(filterBy(CLIENTS, "aylesbury", fields).map((c) => c.name)).toEqual(["Tom Rowe"]);
});

test("every word has to match, so a second word narrows the list", () => {
  // "hp2" alone is in all three postcodes.
  expect(filterBy(CLIENTS, "hp2", fields)).toHaveLength(3);
  expect(filterBy(CLIENTS, "hp2 tring", fields).map((c) => c.name)).toEqual(["José O'Brien"]);
});

test("words can match across different fields", () => {
  expect(filterBy(CLIENTS, "smith bierton", fields).map((c) => c.name)).toEqual(["Mrs J Smith"]);
});

test("an empty or punctuation-only query leaves the list alone", () => {
  for (const query of ["", "   ", "!!", "--"]) {
    expect(filterBy(CLIENTS, query, fields)).toHaveLength(3);
    expect(searchTerms(query)).toEqual([]);
  }
});

test("something genuinely absent finds nothing, rather than everything", () => {
  expect(filterBy(CLIENTS, "wilkinson", fields)).toHaveLength(0);
});

test("blank fields are skipped rather than matched against", () => {
  expect(matches([null, undefined, ""], ["smith"])).toBe(false);
  expect(matches([null, "Mrs Smith"], ["smith"])).toBe(true);
});

test("invoice numbers survive being typed without the prefix or the dash", () => {
  const invoices = [{ number: "JW-0007", client: "Mrs J Smith" }];
  const on = (i: (typeof invoices)[number]) => [i.number, i.client];
  for (const query of ["JW-0007", "jw0007", "0007", "jw 0007"]) {
    expect(filterBy(invoices, query, on)).toHaveLength(1);
  }
});
