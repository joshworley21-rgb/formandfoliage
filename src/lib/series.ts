// Working out the dates for a repeating run of jobs.
//
// A "series" is not a rule the server evaluates — booking with Repeats ticked
// creates every job up front and tags them with a shared series_id. So editing
// a series means rewriting the jobs it already made, and extending one means
// carrying on from where the last of them landed. That arithmetic lives here,
// away from the dialog, because it is the part worth testing.
import { addMonths, addWeeks, format, parseISO } from "date-fns";
import type { Frequency, ScheduledJob } from "@/lib/crm";

/** How far the i-th visit of a run sits from the first one. */
export const FREQ_STEP: Record<Frequency, (d: Date, i: number) => Date> = {
  weekly: (d, i) => addWeeks(d, i),
  fortnightly: (d, i) => addWeeks(d, i * 2),
  four_weekly: (d, i) => addWeeks(d, i * 4),
  monthly: (d, i) => addMonths(d, i),
  quarterly: (d, i) => addMonths(d, i * 3),
};

/**
 * A local wall-clock time on a given day.
 *
 * Deliberately built from the formatted local date rather than by setting
 * hours on the Date, so a job stays at 09:00 across a clock change instead of
 * drifting to 08:00 or 10:00 — which is what the customer is expecting, and
 * what date-fns's addWeeks already does to the day.
 */
export function atTime(day: Date, time: string): Date {
  return new Date(`${format(day, "yyyy-MM-dd")}T${time}`);
}

/**
 * The visits a series-wide edit is allowed to move: still to come, and not
 * already ticked off.
 *
 * Everything behind `now` is history — a job done last month, or one that was
 * missed and never marked. Rewriting either would change the record of what
 * happened, and a series edit is about what is still going to happen.
 */
export function editableVisits(jobs: ScheduledJob[], now: Date): ScheduledJob[] {
  return jobs
    .filter((job) => job.status !== "done" && parseISO(job.starts_at) >= now)
    .sort((a, b) => a.starts_at.localeCompare(b.starts_at));
}

/**
 * Where each remaining visit should sit after an edit.
 *
 * `respace` is the difference between changing the time of the round and
 * changing its rhythm. Without it every visit keeps its own day and only the
 * clock moves. With it the first remaining visit is the anchor — it keeps its
 * day, and the rest are re-laid from there at the new frequency, so switching
 * fortnightly to weekly does not shunt the whole run into next month.
 */
export function retimedVisits(
  visits: ScheduledJob[],
  opts: { startTime: string; frequency: Frequency; respace: boolean },
): { id: string; starts_at: string }[] {
  if (visits.length === 0) return [];
  const anchor = parseISO(visits[0]!.starts_at);
  return visits.map((visit, index) => {
    const day = opts.respace ? FREQ_STEP[opts.frequency](anchor, index) : parseISO(visit.starts_at);
    return { id: visit.id, starts_at: atTime(day, opts.startTime).toISOString() };
  });
}

/**
 * When the next `count` visits should fall, carrying on past the end of the run.
 *
 * Measured from the last visit in the series whatever its state: a run that has
 * been worked to the end is exactly the one that needs extending, and its final
 * job is done by then.
 */
export function nextVisits(
  jobs: ScheduledJob[],
  opts: { startTime: string; frequency: Frequency; count: number },
): string[] {
  const last = [...jobs].sort((a, b) => a.starts_at.localeCompare(b.starts_at)).at(-1);
  if (!last || opts.count <= 0) return [];
  const from = parseISO(last.starts_at);
  return Array.from({ length: opts.count }, (_, i) =>
    atTime(FREQ_STEP[opts.frequency](from, i + 1), opts.startTime).toISOString(),
  );
}
