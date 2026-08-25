// Pairing up the two halves of the same piece of work.
//
// A job on the schedule and a visit in the log are the same afternoon in the
// garden, recorded from two directions. The app had no idea of that: logging a
// visit left the job sitting there as still to do, and ticking the job off
// afterwards asked for the hours a second time and wrote a second visit.
//
// Both of these answer the same question — is the other half of this already
// recorded? — and both are deliberately pure, so the rule can be tested without
// a database.

/** Only the fields the pairing depends on, so tests need not build a whole row. */
type JobLike = {
  id: string;
  client_id: string;
  starts_at: string;
  status: string;
  visit_id?: string | null;
};

type VisitLike = {
  id: string;
  client_id: string;
  visit_date: string;
};

/** The calendar day of a timestamp, in the phone's own timezone. */
export function localDay(timestamp: string): string {
  const d = new Date(timestamp);
  if (Number.isNaN(d.getTime())) return "";
  // Not toISOString(): that is UTC, and an 08:00 job in British Summer Time
  // would land on the day before for anyone east of Greenwich.
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${month}-${day}`;
}

/**
 * The job a newly logged visit finishes off, or null.
 *
 * Matches on client and day only: the time a job was booked for and the time it
 * was actually done are rarely the same, and holding out for that would mean it
 * never matched.
 *
 * Earliest first when a client has two jobs on one day, so a morning and an
 * afternoon visit tick off the morning and afternoon jobs in that order rather
 * than both landing on the same one.
 */
export function findJobForVisit<T extends JobLike>(
  jobs: T[],
  clientId: string,
  visitDate: string,
): T | null {
  const candidates = jobs
    .filter(
      (job) =>
        job.client_id === clientId &&
        job.status === "scheduled" &&
        !job.visit_id &&
        localDay(job.starts_at) === visitDate,
    )
    .sort((a, b) => a.starts_at.localeCompare(b.starts_at));

  return candidates[0] ?? null;
}

/**
 * The visit already logged for a job being marked complete, or null.
 *
 * This is the same question from the other side, and it is what stops the
 * Complete button asking for hours that are already written down.
 *
 * `linkedVisitIds` are visits already claimed by another job, so two jobs on one
 * day cannot both point at the same visit.
 */
export function findVisitForJob<T extends VisitLike>(
  visits: T[],
  clientId: string,
  jobStartsAt: string,
  linkedVisitIds: Iterable<string> = [],
): T | null {
  const claimed = new Set(linkedVisitIds);
  const day = localDay(jobStartsAt);

  return (
    visits.find(
      (visit) =>
        visit.client_id === clientId && visit.visit_date === day && !claimed.has(visit.id),
    ) ?? null
  );
}
