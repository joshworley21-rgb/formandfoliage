// This decides whether an email goes to somebody else's customer, at 8am, with
// nobody watching. The test that matters most is the first one.
import { expect, test } from "bun:test";
import {
  REMINDER_AFTER_DAYS,
  REPEAT_EVERY_DAYS,
  shouldChase,
  type ChaseInputs,
} from "./chasing.ts";

const NOW = new Date("2026-09-01T08:00:00Z");
const daysAgo = (n: number) => new Date(NOW.getTime() - n * 86400000).toISOString();

const input = (over: Partial<ChaseInputs> = {}): ChaseInputs => ({
  optedIn: true,
  email: "fielding@example.com",
  sentAt: daysAgo(30),
  dueDate: "2026-08-15",
  lastChasedAt: null,
  now: NOW,
  ...over,
});

test("a client who has not been opted in is never chased", () => {
  // Thirty days past due, no reminder ever sent: every other rule says chase.
  const decision = shouldChase(input({ optedIn: false }));
  expect(decision.chase).toBe(false);
  expect(decision).toEqual({ chase: false, reason: "opted-out" });
});

test("opting out beats every other reason to send", () => {
  // Whatever the numbers say, the switch wins. Written as its own test because
  // the bug was an email nobody chose to send, not an email sent too early.
  for (const dueDate of [null, "2026-01-01", "2027-01-01"]) {
    for (const sentAt of [daysAgo(0), daysAgo(14), daysAgo(400)]) {
      expect(shouldChase(input({ optedIn: false, dueDate, sentAt })).chase).toBe(false);
    }
  }
});

test("an opted-in client past their due date is chased", () => {
  const decision = shouldChase(input());
  expect(decision).toEqual({
    chase: true,
    email: "fielding@example.com",
    overdue: true,
    overdueDays: 17,
    daysSinceSent: 30,
  });
});

test("no email address is not chased, opted in or not", () => {
  expect(shouldChase(input({ email: null }))).toEqual({ chase: false, reason: "no-email" });
  expect(shouldChase(input({ email: "" }))).toEqual({ chase: false, reason: "no-email" });
});

test("the day a due date passes is the day chasing can start", () => {
  // Due today is not overdue; due yesterday is, by one day.
  expect(shouldChase(input({ dueDate: "2026-09-01", sentAt: daysAgo(2) })).chase).toBe(false);
  const yesterday = shouldChase(input({ dueDate: "2026-08-31", sentAt: daysAgo(2) }));
  expect(yesterday).toMatchObject({ chase: true, overdue: true, overdueDays: 1 });
});

test("with no due date, an invoice waits the full fortnight", () => {
  expect(shouldChase(input({ dueDate: null, sentAt: daysAgo(13) }))).toEqual({
    chase: false,
    reason: "too-soon",
  });
  expect(shouldChase(input({ dueDate: null, sentAt: daysAgo(REMINDER_AFTER_DAYS) }))).toMatchObject(
    { chase: true, overdue: false, daysSinceSent: 14 },
  );
});

test("a client is not chased twice inside a week", () => {
  expect(shouldChase(input({ lastChasedAt: daysAgo(6) }))).toEqual({
    chase: false,
    reason: "chased-recently",
  });
  expect(shouldChase(input({ lastChasedAt: daysAgo(REPEAT_EVERY_DAYS) })).chase).toBe(true);
});

test("a due date is measured in whole days, not hours since midnight", () => {
  // The run happens at 08:00. Comparing timestamps rather than dates would make
  // "due yesterday" come out as 0 days overdue and skip a real chaser.
  const lateInDay = shouldChase(input({ now: new Date("2026-09-01T23:30:00Z") }));
  const earlyInDay = shouldChase(input({ now: new Date("2026-09-01T00:30:00Z") }));
  expect((lateInDay as { overdueDays: number }).overdueDays).toBe(17);
  expect((earlyInDay as { overdueDays: number }).overdueDays).toBe(17);
});

test("nothing is chased before it has been sent", () => {
  // A due date in the future and sent this morning: neither rule fires.
  expect(shouldChase(input({ dueDate: "2026-09-30", sentAt: daysAgo(0) }))).toEqual({
    chase: false,
    reason: "too-soon",
  });
});
