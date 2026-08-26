import { expect, test } from "bun:test";
import { findJobForVisit, findVisitForJob, localDay } from "./job-visit-link";

/** Builds an ISO timestamp for a local time, so tests read as wall-clock. */
const at = (day: string, time: string) => new Date(`${day}T${time}`).toISOString();

const job = (over: Partial<Parameters<typeof findJobForVisit>[0][number]> = {}) => ({
  id: "j1",
  client_id: "c1",
  starts_at: at("2026-08-25", "09:30"),
  status: "scheduled",
  visit_id: null,
  ...over,
});

test("localDay reads the day in local time, not UTC", () => {
  // 00:30 local on the 25th is still the 25th, even where UTC has rolled back.
  expect(localDay(at("2026-08-25", "00:30"))).toBe("2026-08-25");
  // And late evening has not yet become tomorrow.
  expect(localDay(at("2026-08-25", "23:30"))).toBe("2026-08-25");
});

test("a visit finishes off the job booked for that client that day", () => {
  const found = findJobForVisit([job()], "c1", "2026-08-25");
  expect(found?.id).toBe("j1");
});

test("the booked time and the time it was actually done need not agree", () => {
  const found = findJobForVisit(
    [job({ starts_at: at("2026-08-25", "07:00") })],
    "c1",
    "2026-08-25",
  );
  expect(found?.id).toBe("j1");
});

test("another client's job that day is not touched", () => {
  expect(findJobForVisit([job({ client_id: "c2" })], "c1", "2026-08-25")).toBeNull();
});

test("the same client on a different day is not touched", () => {
  expect(findJobForVisit([job()], "c1", "2026-08-26")).toBeNull();
});

test("a job already done is not completed twice", () => {
  expect(findJobForVisit([job({ status: "done" })], "c1", "2026-08-25")).toBeNull();
});

test("a job already carrying a visit is left alone", () => {
  expect(findJobForVisit([job({ visit_id: "v9" })], "c1", "2026-08-25")).toBeNull();
});

test("two jobs in one day are taken earliest first", () => {
  const morning = job({ id: "morning", starts_at: at("2026-08-25", "09:00") });
  const afternoon = job({ id: "afternoon", starts_at: at("2026-08-25", "14:00") });

  // Listed out of order on purpose: the sort must decide, not the array.
  expect(findJobForVisit([afternoon, morning], "c1", "2026-08-25")?.id).toBe("morning");

  // Once the morning is linked, the next visit takes the afternoon.
  const afterFirst = [{ ...morning, status: "done", visit_id: "v1" }, afternoon];
  expect(findJobForVisit(afterFirst, "c1", "2026-08-25")?.id).toBe("afternoon");
});

test("nothing booked means nothing to complete", () => {
  expect(findJobForVisit([], "c1", "2026-08-25")).toBeNull();
});

// ------------------------------------------------------- the other side ---

const visit = (over: Partial<{ id: string; client_id: string; visit_date: string }> = {}) => ({
  id: "v1",
  client_id: "c1",
  visit_date: "2026-08-25",
  ...over,
});

test("completing a job finds the visit already logged that day", () => {
  const found = findVisitForJob([visit()], "c1", at("2026-08-25", "09:30"));
  expect(found?.id).toBe("v1");
});

test("a visit for another client, or another day, is not offered", () => {
  expect(findVisitForJob([visit({ client_id: "c2" })], "c1", at("2026-08-25", "09:30"))).toBeNull();
  expect(
    findVisitForJob([visit({ visit_date: "2026-08-24" })], "c1", at("2026-08-25", "09:30")),
  ).toBeNull();
});

test("a visit another job already claimed is not offered again", () => {
  expect(findVisitForJob([visit()], "c1", at("2026-08-25", "09:30"), ["v1"])).toBeNull();
});

test("with one visit claimed, the second job takes the other", () => {
  const visits = [visit({ id: "v1" }), visit({ id: "v2" })];
  expect(findVisitForJob(visits, "c1", at("2026-08-25", "14:00"), ["v1"])?.id).toBe("v2");
});
