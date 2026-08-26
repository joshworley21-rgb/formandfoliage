// Works out which invoice an incoming payment is most likely to be for.
//
// This only ever produces a suggestion and the sentence explaining it. Nothing
// here marks anything paid — a person confirms every match in the app. That is
// what lets the rules below be generous: the cost of a wrong guess is a glance,
// not a debt that stops being chased.
//
// Kept free of Supabase and Monzo so it can be tested as what it is: text and
// money in, one sentence out.

export type MatchCandidate = {
  id: string;
  /** As printed on the invoice, e.g. "JW-0007". */
  number: string;
  /** Pounds, not pence. */
  total: number;
  /** Used when the payer's name is the only clue. */
  clientName?: string | null;
};

export type IncomingPayment = {
  /** Pounds, positive — money in. */
  amount: number;
  /** Whatever the payer typed as the reference, if the bank passed it on. */
  reference?: string | null;
  /** Monzo's own description of the transaction. */
  description?: string | null;
  /** The name on the account the money came from. */
  counterparty?: string | null;
};

export type Match = {
  /** Null when there is something worth saying but nothing worth pointing at. */
  invoiceId: string | null;
  /** Shown next to the payment in the review list. Always readable English. */
  reason: string;
};

/** Half a penny — rounding either side of a currency conversion, nothing more. */
const PENNY = 0.005;

function money(value: number): string {
  return new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" }).format(value);
}

/**
 * Strips everything that varies between how a number is printed and how someone
 * types it into their banking app: case, spaces, dashes, punctuation. "JW-0007"
 * and "jw 0007" both become "JW0007".
 */
function normalise(value: string | null | undefined): string {
  return (value ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
}

/** Every scrap of text the payment arrived with, as one string to search. */
function haystack(payment: IncomingPayment): string {
  return normalise([payment.reference, payment.description, payment.counterparty].join(" "));
}

/**
 * The digits of an invoice number, without its prefix and leading zeros: the
 * part someone types when they shorten "JW-0007" to "7".
 */
function bareNumber(invoiceNumber: string): string {
  const digits = normalise(invoiceNumber).replace(/^[A-Z]+/, "");
  return digits.replace(/^0+/, "");
}

function sameAmount(a: number, b: number): boolean {
  return Math.abs(a - b) < PENNY;
}

/**
 * Names match when one contains the other, so "Mrs J Smith" pays "J Smith" and
 * a client saved as "Smith" is still found. Short names are left alone — "Lee"
 * inside "Leeds City Council" is not a person.
 */
function namesMatch(counterparty: string, clientName: string): boolean {
  const a = normalise(counterparty);
  const b = normalise(clientName);
  if (a.length < 4 || b.length < 4) return false;
  return a.includes(b) || b.includes(a);
}

/**
 * @param payment  The money that arrived.
 * @param unpaid   Every invoice still owed, in any order.
 */
export function suggestMatch(payment: IncomingPayment, unpaid: MatchCandidate[]): Match | null {
  if (unpaid.length === 0) return null;
  const text = haystack(payment);

  // 1. The reference names an invoice. Nothing else comes close to this, so it
  //    wins even when the amount disagrees — a short payment against a named
  //    invoice is still that invoice's payment.
  const named = unpaid.filter((invoice) => text.includes(normalise(invoice.number)));
  if (named.length === 1) {
    const invoice = named[0]!;
    return { invoiceId: invoice.id, reason: referenceReason(invoice, payment.amount) };
  }

  // 2. The reference names one only by its digits — "7" rather than "JW-0007".
  //    Only trusted when exactly one invoice answers to it, because "7" would
  //    otherwise also find invoice 17.
  const looselyNamed = unpaid.filter((invoice) => {
    const bare = bareNumber(invoice.number);
    return bare.length > 0 && new RegExp(`(^|[^0-9])0*${bare}([^0-9]|$)`).test(text);
  });
  if (looselyNamed.length === 1) {
    const invoice = looselyNamed[0]!;
    return { invoiceId: invoice.id, reason: referenceReason(invoice, payment.amount) };
  }

  // 3. No usable reference — which is the common case, because most people type
  //    nothing. Fall back to the amount.
  const exact = unpaid.filter((invoice) => sameAmount(invoice.total, payment.amount));
  if (exact.length === 1) {
    const invoice = exact[0]!;
    return {
      invoiceId: invoice.id,
      reason: `${money(payment.amount)} is exactly what ${invoice.number} is owed`,
    };
  }
  if (exact.length > 1) {
    // Pointing at one of several identical invoices would be a coin toss
    // wearing a suggestion's clothes. Say what is ambiguous instead.
    const numbers = exact.map((invoice) => invoice.number).join(", ");
    return {
      invoiceId: null,
      reason: `${money(payment.amount)} matches ${exact.length} unpaid invoices: ${numbers}`,
    };
  }

  // 4. Nothing but the payer's name. Useful only when that person owes exactly
  //    one thing — otherwise it says nothing about which.
  if (payment.counterparty) {
    const theirs = unpaid.filter(
      (invoice) => invoice.clientName && namesMatch(payment.counterparty!, invoice.clientName),
    );
    if (theirs.length === 1) {
      const invoice = theirs[0]!;
      const difference = describeDifference(invoice.total, payment.amount);
      return {
        invoiceId: invoice.id,
        reason: `From ${payment.counterparty}, whose only unpaid invoice is ${invoice.number}${difference}`,
      };
    }
  }

  return null;
}

/** "…, though that is £20.00 short of the £45.00 owed" — or nothing if it adds up. */
function describeDifference(owed: number, paid: number): string {
  if (sameAmount(owed, paid)) return "";
  const gap = owed - paid;
  const direction = gap > 0 ? "short of" : "more than";
  return `, though that is ${money(Math.abs(gap))} ${direction} the ${money(owed)} owed`;
}

function referenceReason(invoice: MatchCandidate, paid: number): string {
  if (sameAmount(invoice.total, paid)) {
    return `The reference says ${invoice.number}, and ${money(paid)} is exactly what is owed`;
  }
  const gap = invoice.total - paid;
  const direction = gap > 0 ? "short of" : "more than";
  return `The reference says ${invoice.number}, but ${money(paid)} is ${money(Math.abs(gap))} ${direction} the ${money(invoice.total)} owed`;
}
