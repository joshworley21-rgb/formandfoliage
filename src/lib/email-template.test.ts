// The email a customer receives is written from these tokens, and the box the
// wording is edited in is pre-filled from them. A token that fills in on the
// phone but not on the server would show one thing before sending and put
// another in the customer's inbox, so what is pinned down here is that the
// filled text is finished: real values, no {{braces}} left anywhere.
import { expect, test } from "bun:test";
import {
  DEFAULT_BODY_TEMPLATE,
  DEFAULT_SUBJECT_TEMPLATE,
  draftFromTemplates,
  fillTemplate,
  invoiceEmailTokens,
} from "./email-template";

const invoice = {
  number: "JW-0042",
  total: 240,
  due_date: "2026-09-14",
  issue_date: "2026-08-31",
};

const settings = {
  business_name: "Form & Foliage",
  payment_terms: "14 days",
  bank_details: "Sort code: 00-00-00",
};

test("the tokens are exactly the set send-invoice-email builds", () => {
  // If this list changes, supabase/functions/send-invoice-email/index.ts has to
  // change with it — the two are separate runtimes and cannot share a module.
  expect(
    Object.keys(invoiceEmailTokens({ invoice, clientName: "Mrs Fielding", settings })).sort(),
  ).toEqual([
    "bank_details",
    "business_name",
    "client_name",
    "due_clause",
    "due_date",
    "invoice_number",
    "issue_date",
    "payment_terms",
    "total",
  ]);
});

test("money is formatted the way the invoice states it", () => {
  const tokens = invoiceEmailTokens({ invoice: { total: 240 }, clientName: "A", settings: null });
  expect(tokens["total"]).toBe("£240.00");
});

test("pennies survive, and a total that arrives as a string still formats", () => {
  expect(
    invoiceEmailTokens({ invoice: { total: 17.5 }, clientName: "A", settings: null })["total"],
  ).toBe("£17.50");
  expect(
    invoiceEmailTokens({ invoice: { total: "98.4" }, clientName: "A", settings: null })["total"],
  ).toBe("£98.40");
});

test("the due clause is a sentence fragment, or nothing at all", () => {
  expect(invoiceEmailTokens({ invoice, clientName: "A", settings })["due_clause"]).toBe(
    " by 2026-09-14",
  );
  // No due date means "Total due: £240.00." rather than a dangling "by".
  expect(
    invoiceEmailTokens({ invoice: { total: 240 }, clientName: "A", settings })["due_clause"],
  ).toBe("");
});

test("the business name falls back rather than leaving a gap in the sign-off", () => {
  expect(invoiceEmailTokens({ invoice, clientName: "A", settings: null })["business_name"]).toBe(
    "JW Garden Services",
  );
  expect(
    invoiceEmailTokens({ invoice, clientName: "A", settings: { business_name: "" } })[
      "business_name"
    ],
  ).toBe("JW Garden Services");
});

test("nothing in the default email is left unfilled", () => {
  const tokens = invoiceEmailTokens({ invoice, clientName: "Mrs Fielding", settings });
  const subject = fillTemplate(DEFAULT_SUBJECT_TEMPLATE, tokens);
  const body = fillTemplate(DEFAULT_BODY_TEMPLATE, tokens);
  // The failure this catches is a token renamed on one side only: the box
  // would open showing {{client_name}} to a customer's name's face.
  expect(subject).not.toContain("{{");
  expect(body).not.toContain("{{");
  expect(subject).toBe("Invoice JW-0042 from Form & Foliage");
  expect(body).toContain("Hi Mrs Fielding,");
  expect(body).toContain("Total due: £240.00 by 2026-09-14.");
});

test("an unknown token empties rather than printing itself", () => {
  expect(fillTemplate("Hi {{nonsense}}, thanks", {})).toBe("Hi , thanks");
});

/* -------- what the compose box opens on -------- */

test("the box opens on the finished email, not the template", () => {
  const draft = draftFromTemplates({ invoice, clientName: "Mrs Fielding", settings });
  expect(draft.subject).toBe("Invoice JW-0042 from Form & Foliage");
  expect(draft.message).toContain("Hi Mrs Fielding,");
  // The thing that must never appear in a box someone is about to send from.
  expect(draft.subject).not.toContain("{{");
  expect(draft.message).not.toContain("{{");
});

test("a saved template is used ahead of the built-in one", () => {
  const draft = draftFromTemplates({
    invoice,
    clientName: "Mrs Fielding",
    settings: {
      ...settings,
      email_subject_template: "{{business_name}}: {{invoice_number}}",
      email_body_template: "{{client_name}} — {{total}}{{due_clause}}",
    },
  });
  expect(draft.subject).toBe("Form & Foliage: JW-0042");
  expect(draft.message).toBe("Mrs Fielding — £240.00 by 2026-09-14");
});

test("a saved template left blank falls back rather than opening an empty box", () => {
  const draft = draftFromTemplates({
    invoice,
    clientName: "Mrs Fielding",
    settings: { ...settings, email_subject_template: "", email_body_template: "   " },
  });
  expect(draft.subject).toBe("Invoice JW-0042 from Form & Foliage");
  // Whitespace is not a template either — an all-spaces body would send blank.
  expect(draft.message.trim()).not.toBe("");
});

test("a missing due date leaves no dangling word in the real body", () => {
  const tokens = invoiceEmailTokens({
    invoice: { number: "JW-1", total: 60 },
    clientName: "A",
    settings: null,
  });
  expect(fillTemplate(DEFAULT_BODY_TEMPLATE, tokens)).toContain("Total due: £60.00.");
});
