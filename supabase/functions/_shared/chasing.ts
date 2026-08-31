// Whether an unpaid invoice should be chased today.
//
// This runs unattended, on a schedule, and its output is an email to somebody
// else's customer. There is nobody to see a mistake and no undo — which is how
// every client with an unpaid invoice came to be chased automatically without
// anyone having decided it, and stayed chased weekly.
//
// So the decision is a pure function with tests rather than a run of `continue`
// statements inside a loop that only executes at 8am on a server.

export type ChaseInputs = {
  /** The client's own switch. Off unless someone turned it on. */
  optedIn: boolean;
  email: string | null;
  /** When the invoice was emailed, ISO. */
  sentAt: string;
  /** yyyy-MM-dd, or null when no due date was set. */
  dueDate: string | null;
  /** When the last chaser for this invoice went out, ISO, or null. */
  lastChasedAt: string | null;
  now: Date;
};

export type ChaseDecision =
  | { chase: false; reason: "opted-out" | "no-email" | "too-soon" | "chased-recently" }
  | {
      chase: true;
      /** The address it checked, so the caller sends to what was approved
       *  rather than re-reading a field this decision has already ruled on. */
      email: string;
      overdue: boolean;
      overdueDays: number;
      daysSinceSent: number;
    };

/** Unpaid this long since sending, with no due date to go by, starts the chasing. */
export const REMINDER_AFTER_DAYS = 14;
/** And then no more often than this, however overdue it gets. */
export const REPEAT_EVERY_DAYS = 7;

const DAY = 86400000;

/** Whole days between two yyyy-MM-dd days, at UTC midnight so DST cannot shift it. */
function daysBetweenDates(fromIso: string, toIso: string): number {
  const from = new Date(`${fromIso}T00:00:00Z`).getTime();
  const to = new Date(`${toIso}T00:00:00Z`).getTime();
  return Math.floor((to - from) / DAY);
}

export function shouldChase(input: ChaseInputs): ChaseDecision {
  // First, and before any arithmetic: nobody who has not been opted in is
  // chased, however overdue they are. Chasing a customer is a decision about
  // a relationship, not a rule about a number.
  if (!input.optedIn) return { chase: false, reason: "opted-out" };
  if (!input.email) return { chase: false, reason: "no-email" };

  const now = input.now.getTime();
  const todayIso = input.now.toISOString().slice(0, 10);

  const overdueDays = input.dueDate ? daysBetweenDates(input.dueDate, todayIso) : 0;
  const daysSinceSent = Math.floor((now - new Date(input.sentAt).getTime()) / DAY);
  const overdue = Boolean(input.dueDate) && overdueDays > 0;

  // Past its due date, or unpaid long enough that there evidently was not one.
  if (!overdue && daysSinceSent < REMINDER_AFTER_DAYS) {
    return { chase: false, reason: "too-soon" };
  }

  if (
    input.lastChasedAt &&
    now - new Date(input.lastChasedAt).getTime() < REPEAT_EVERY_DAYS * DAY
  ) {
    return { chase: false, reason: "chased-recently" };
  }

  return { chase: true, email: input.email, overdue, overdueDays, daysSinceSent };
}
