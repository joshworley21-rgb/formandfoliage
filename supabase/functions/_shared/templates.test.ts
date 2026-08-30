// The fallback chain that decides what an invoice email actually says.
//
// It is one line of `a || b || c` in three functions, which is exactly the
// sort of line nobody looks at twice — and the sort where a stray space in a
// settings field sends a customer a blank email that the app reports as sent.
import { expect, test } from "bun:test";
import {
  DEFAULT_BODY_TEMPLATE,
  DEFAULT_SUBJECT_TEMPLATE,
  fillTemplate,
  firstNonBlank,
  textToHtml,
} from "./templates.ts";

test("a template cleared to a stray space falls through to the next one", () => {
  // The bug this exists for: " " is truthy, so `||` stops here and sends it.
  expect(firstNonBlank(" ", DEFAULT_BODY_TEMPLATE)).toBe(DEFAULT_BODY_TEMPLATE);
  expect(firstNonBlank("\n\t  ", DEFAULT_BODY_TEMPLATE)).toBe(DEFAULT_BODY_TEMPLATE);
});

test("empty, null and undefined all fall through", () => {
  expect(firstNonBlank("", null, undefined, "used")).toBe("used");
});

test("real text wins, and is handed back exactly as it was written", () => {
  // Untrimmed on purpose: a trailing newline someone typed is theirs to keep.
  expect(firstNonBlank("Hi there\n\n", DEFAULT_BODY_TEMPLATE)).toBe("Hi there\n\n");
  expect(firstNonBlank("  padded  ", "next")).toBe("  padded  ");
});

test("nothing anywhere is an empty string rather than undefined", () => {
  expect(firstNonBlank(null, undefined, "")).toBe("");
});

test("the built-in templates fill in with no placeholder left behind", () => {
  const tokens = {
    client_name: "Mrs Fielding",
    business_name: "Form & Foliage",
    invoice_number: "JW-0042",
    total: "£240.00",
    due_clause: " by 2026-09-14",
  };
  expect(fillTemplate(DEFAULT_SUBJECT_TEMPLATE, tokens)).toBe(
    "Invoice JW-0042 from Form & Foliage",
  );
  expect(fillTemplate(DEFAULT_BODY_TEMPLATE, tokens)).not.toContain("{{");
});

test("a customer's name with an ampersand in it is escaped, not injected", () => {
  const html = textToHtml("Hi Ben & Co,\n\nThanks.");
  expect(html).toContain("Ben &amp; Co");
  expect(html).not.toContain("Ben & Co,");
});

test("wording typed into the app is still filled, so a hand-typed token resolves", () => {
  // The compose box sends finished text, but someone typing {{total}} into it
  // means the figure, not the characters.
  expect(fillTemplate("Owed: {{total}}", { total: "£60.00" })).toBe("Owed: £60.00");
});
