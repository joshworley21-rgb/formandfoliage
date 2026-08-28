// What a visit's money fields become when the form is saved.
//
// Pulled out of the dialog because two of the rules here are quiet ones that
// nothing on screen would catch if they broke: a missed visit is worth
// nothing whatever is left in the rate boxes, and an invoiced visit's figures
// are not writable at all. The invoice keeps its own copy of every line, so a
// visit edited after billing would leave the two disagreeing with nothing to
// say so — and the disagreement would surface as a wrong total months later.

export type VisitMoneyInput = {
  /** The visit is already on an invoice. */
  locked: boolean;
  missed: boolean;
  payMode: "hourly" | "day";
  /** Straight from the form, so strings — "" and "abc" both mean nothing. */
  hours: string;
  materials: string;
  rate: string;
  days: string;
};

const num = (value: string) => Number(value) || 0;

/** Labour plus materials, or nothing at all when the visit did not happen. */
export function visitAmount(input: VisitMoneyInput): number {
  if (input.missed) return 0;
  const labour =
    input.payMode === "day"
      ? num(input.rate) * num(input.days)
      : num(input.rate) * num(input.hours);
  return Math.round((labour + num(input.materials)) * 100) / 100;
}

/**
 * The columns a save should write. An invoiced visit contributes none of
 * them, so the update leaves the billed figures exactly as the invoice has
 * them — the description is written separately and stays editable.
 */
export function visitMoneyFields(input: VisitMoneyInput): {
  hours?: number;
  materials_cost?: number;
  amount?: number;
  missed?: boolean;
} {
  if (input.locked) return {};
  return {
    hours: input.missed ? 0 : num(input.hours),
    materials_cost: input.missed ? 0 : num(input.materials),
    amount: visitAmount(input),
    missed: input.missed,
  };
}
