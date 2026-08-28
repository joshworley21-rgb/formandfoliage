// A series edit rewrites jobs that are already booked in, so the arithmetic
// has to be right about two things above all: which visits it is allowed to
// touch, and where they land afterwards. Getting either wrong moves a
// customer's appointment without anyone asking them.
import { expect, test } from "bun:test";
import type { ScheduledJob } from "@/lib/crm";
import { atTime, editableVisits, nextVisits, retimedVisits } from "./series";

/** Local time, so the tests read as the clock the gardener actually sees. */
const job = (
  id: string,
  local: string,
  status: ScheduledJob["status"] = "scheduled",
): ScheduledJob =>
  ({
    id,
    starts_at: new Date(local).toISOString(),
    status,
    series_id: "s1",
    client_id: "c1",
    duration_minutes: 60,
  }) as ScheduledJob;

const days = (jobs: { starts_at: string }[]) =>
  jobs.map((j) => new Date(j.starts_at).toLocaleString("en-GB", { hour12: false }));

const NOW = new Date("2026-03-10T12:00:00");

test("a visit still to come is editable", () => {
  const visits = editableVisits([job("a", "2026-03-17T09:00:00")], NOW);
  expect(visits.map((v) => v.id)).toEqual(["a"]);
});

test("a visit already done is left alone", () => {
  expect(editableVisits([job("a", "2026-03-17T09:00:00", "done")], NOW)).toEqual([]);
});

test("a visit in the past is left alone even when it was never ticked off", () => {
  // A missed job is a record of what happened. Moving it would rewrite that.
  expect(editableVisits([job("a", "2026-03-03T09:00:00")], NOW)).toEqual([]);
});

test("earlier today, before now, counts as past", () => {
  expect(editableVisits([job("a", "2026-03-10T09:00:00")], NOW)).toEqual([]);
});

test("editable visits come back in date order however they arrive", () => {
  const visits = editableVisits(
    [
      job("c", "2026-03-31T09:00:00"),
      job("a", "2026-03-17T09:00:00"),
      job("b", "2026-03-24T09:00:00"),
    ],
    NOW,
  );
  expect(visits.map((v) => v.id)).toEqual(["a", "b", "c"]);
});

test("changing the time moves the clock and leaves every day where it was", () => {
  const visits = [job("a", "2026-03-17T09:00:00"), job("b", "2026-03-31T09:00:00")];
  const out = retimedVisits(visits, {
    startTime: "14:30",
    frequency: "fortnightly",
    respace: false,
  });
  expect(days(out)).toEqual(["17/03/2026, 14:30:00", "31/03/2026, 14:30:00"]);
});

test("a run with a gap in it keeps that gap when only the time changes", () => {
  // Someone has already moved one visit by hand. A time change must not
  // quietly tidy it back onto the rhythm.
  const visits = [job("a", "2026-03-17T09:00:00"), job("b", "2026-04-02T09:00:00")];
  const out = retimedVisits(visits, {
    startTime: "08:00",
    frequency: "fortnightly",
    respace: false,
  });
  expect(days(out)).toEqual(["17/03/2026, 08:00:00", "02/04/2026, 08:00:00"]);
});

test("changing the frequency re-spaces from the first visit still to come", () => {
  const visits = [
    job("a", "2026-03-17T09:00:00"),
    job("b", "2026-03-31T09:00:00"),
    job("c", "2026-04-14T09:00:00"),
  ];
  const out = retimedVisits(visits, { startTime: "09:00", frequency: "weekly", respace: true });
  // The anchor keeps its day, so the run does not slide off into next month.
  expect(days(out)).toEqual([
    "17/03/2026, 09:00:00",
    "24/03/2026, 09:00:00",
    "31/03/2026, 09:00:00",
  ]);
});

test("re-spacing keeps each visit's own id", () => {
  const visits = [job("a", "2026-03-17T09:00:00"), job("b", "2026-03-31T09:00:00")];
  const out = retimedVisits(visits, { startTime: "09:00", frequency: "monthly", respace: true });
  expect(out.map((v) => v.id)).toEqual(["a", "b"]);
});

test("nothing to edit produces no writes", () => {
  expect(retimedVisits([], { startTime: "09:00", frequency: "weekly", respace: true })).toEqual([]);
});

test("extending carries on from the last visit in the run", () => {
  const jobs = [job("a", "2026-03-17T09:00:00"), job("b", "2026-03-31T09:00:00")];
  const out = nextVisits(jobs, { startTime: "09:00", frequency: "fortnightly", count: 2 });
  expect(days(out.map((starts_at) => ({ starts_at })))).toEqual([
    "14/04/2026, 09:00:00",
    "28/04/2026, 09:00:00",
  ]);
});

test("a run worked to the end still extends from its last visit", () => {
  // Every job done is exactly when someone reaches for "add more visits".
  const jobs = [job("a", "2026-03-17T09:00:00", "done"), job("b", "2026-03-31T09:00:00", "done")];
  const out = nextVisits(jobs, { startTime: "09:00", frequency: "weekly", count: 1 });
  expect(days(out.map((starts_at) => ({ starts_at })))).toEqual(["07/04/2026, 09:00:00"]);
});

test("extending is measured from the latest visit, not the last one listed", () => {
  const jobs = [job("b", "2026-03-31T09:00:00"), job("a", "2026-03-17T09:00:00")];
  const out = nextVisits(jobs, { startTime: "09:00", frequency: "weekly", count: 1 });
  expect(days(out.map((starts_at) => ({ starts_at })))).toEqual(["07/04/2026, 09:00:00"]);
});

test("extending an empty series adds nothing", () => {
  expect(nextVisits([], { startTime: "09:00", frequency: "weekly", count: 4 })).toEqual([]);
});

test("asking for no extra visits adds nothing", () => {
  const jobs = [job("a", "2026-03-17T09:00:00")];
  expect(nextVisits(jobs, { startTime: "09:00", frequency: "weekly", count: 0 })).toEqual([]);
});

test("a monthly run holds its date rather than drifting by four weeks", () => {
  const jobs = [job("a", "2026-01-31T09:00:00")];
  const out = nextVisits(jobs, { startTime: "09:00", frequency: "monthly", count: 2 });
  // February has no 31st: date-fns clamps to the last day rather than
  // spilling into March.
  expect(days(out.map((starts_at) => ({ starts_at })))).toEqual([
    "28/02/2026, 09:00:00",
    "31/03/2026, 09:00:00",
  ]);
});

test("a visit keeps its wall-clock time across the spring clock change", () => {
  // 29 March 2026 is when the UK clocks go forward. A 09:00 job stays 09:00.
  const jobs = [job("a", "2026-03-22T09:00:00")];
  const out = nextVisits(jobs, { startTime: "09:00", frequency: "weekly", count: 1 });
  expect(days(out.map((starts_at) => ({ starts_at })))).toEqual(["29/03/2026, 09:00:00"]);
});

test("atTime puts the given clock time on the given day", () => {
  expect(
    atTime(new Date("2026-03-17T23:15:00"), "07:45").toLocaleString("en-GB", { hour12: false }),
  ).toBe("17/03/2026, 07:45:00");
});
