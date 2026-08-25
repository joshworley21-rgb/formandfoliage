import { createFileRoute, Link } from "@tanstack/react-router";
import {
  addDays,
  endOfMonth,
  format,
  isBefore,
  isSameDay,
  parseISO,
  startOfDay,
  startOfMonth,
  startOfWeek,
} from "date-fns";
import {
  CalendarDays,
  CheckCircle2,
  ClipboardList,
  Clock,
  FileText,
  PoundSterling,
  Plus,
  Send,
} from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { InvoiceReminders } from "@/components/InvoiceReminders";
import { OwnerOnly } from "@/components/OwnerOnly";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useClients, useInvoices, useJobs, useVisits } from "@/hooks/use-crm";
import { hoursLabel, money } from "@/lib/crm";

export const Route = createFileRoute("/_authenticated/dashboard")({
  head: () => ({
    meta: [
      { title: "Dashboard — JW Garden Services CRM" },
      { name: "description", content: "Today's round, hours this month and work waiting to be billed." },
      { property: "og:title", content: "Dashboard — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Today's round, hours this month and work waiting to be billed.",
      },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <DashboardPage />
    </OwnerOnly>
  ),
});

const STATUS_STYLES = {
  draft: {
    label: "Draft",
    icon: FileText,
    className: "border-border bg-muted text-muted-foreground",
  },
  sent: {
    label: "Sent",
    icon: Send,
    className: "border-primary/30 bg-primary/10 text-primary",
  },
  paid: {
    label: "Paid",
    icon: CheckCircle2,
    className: "border-accent/40 bg-accent/15 text-accent-foreground",
  },
} as const;

/**
 * The week at a glance. A dot means that day carries work — enough to answer
 * "is tomorrow busy?" without opening the calendar, which is the only question
 * the dashboard is ever asked about the week.
 */
function WeekStrip({ today, busyDays }: { today: Date; busyDays: Set<string> }) {
  const monday = startOfWeek(today, { weekStartsOn: 1 });
  const days = Array.from({ length: 7 }, (_, i) => addDays(monday, i));

  return (
    <div className="mt-4 grid grid-cols-7 gap-1">
      {days.map((day) => {
        const isToday = isSameDay(day, today);
        const busy = busyDays.has(format(day, "yyyy-MM-dd"));
        return (
          <div
            key={day.toISOString()}
            className={
              isToday
                ? "rounded-lg bg-band-foreground py-1.5 text-center text-band"
                : "rounded-lg py-1.5 text-center"
            }
          >
            <p className="figure text-[0.55rem] uppercase tracking-[0.1em] opacity-70">
              {format(day, "EEEEE")}
            </p>
            <p className="figure text-sm font-semibold leading-tight">{format(day, "d")}</p>
            <span
              aria-hidden
              className={
                busy
                  ? "mx-auto mt-0.5 block size-1 rounded-full bg-current opacity-90"
                  : "mx-auto mt-0.5 block size-1 rounded-full bg-current opacity-0"
              }
            />
          </div>
        );
      })}
    </div>
  );
}

function DashboardPage() {
  const today = startOfDay(new Date());
  const monthStart = startOfMonth(today);
  const monthEnd = endOfMonth(today);

  const { data: clients = [] } = useClients();
  const { data: visits = [] } = useVisits();
  const { data: invoices = [] } = useInvoices();
  const { data: jobs = [] } = useJobs({
    from: addDays(today, -30).toISOString(),
    to: addDays(today, 8).toISOString(),
  });

  const clientName = (id: string) => clients.find((c) => c.id === id)?.name ?? "Client";

  const scheduled = jobs.filter((j) => j.status === "scheduled");
  const todayJobs = scheduled.filter((j) => isSameDay(parseISO(j.starts_at), today));
  const upcoming = scheduled
    .filter((j) => parseISO(j.starts_at) > today && !isSameDay(parseISO(j.starts_at), today))
    .slice(0, 8);
  const overdue = scheduled.filter((j) => isBefore(parseISO(j.starts_at), today));

  const todayMinutes = todayJobs.reduce((s, j) => s + Number(j.duration_minutes ?? 0), 0);

  // Which days this week carry work, for the dots under the band.
  const busyDays = new Set(
    scheduled.map((j) => format(parseISO(j.starts_at), "yyyy-MM-dd")),
  );

  const monthVisits = visits.filter((v) => {
    const d = parseISO(v.visit_date);
    return d >= monthStart && d <= monthEnd;
  });
  const monthHours = monthVisits.reduce((s, v) => s + Number(v.hours ?? 0), 0);
  const monthValue = monthVisits.reduce((s, v) => s + Number(v.amount), 0);

  const unbilled = visits.filter((v) => !v.billed);
  const unbilledTotal = unbilled.reduce((s, v) => s + Number(v.amount), 0);
  const outstandingTotal = invoices
    .filter((i) => i.status !== "paid")
    .reduce((s, i) => s + Number(i.total), 0);

  const recentInvoices = [...invoices]
    .sort((a, b) => b.issue_date.localeCompare(a.issue_date))
    .slice(0, 6);


  const stats = [
    { label: "Hours this month", value: hoursLabel(monthHours), icon: Clock },
    { label: "Value logged", value: money(monthValue), icon: ClipboardList },
    { label: "Unbilled work", value: money(unbilledTotal), icon: PoundSterling },
    { label: "Jobs next 7 days", value: String(upcoming.length + todayJobs.length), icon: CalendarDays },
  ];

  return (
    <AppShell title="Dashboard" description="Today's round first, then hours and billing.">
      {/*
        1. Today. This is the question actually being asked at 7am, so it leads
        the page as a solid block — countable at arm's length, in daylight.
      */}
      <section className="rounded-3xl bg-band p-5 text-band-foreground">
        <div className="flex items-baseline justify-between gap-3">
          <p className="figure text-[0.65rem] uppercase tracking-[0.14em] opacity-75">
            {format(today, "EEE d MMMM")}
          </p>
          {todayMinutes > 0 ? (
            <p className="figure text-[0.65rem] tracking-[0.14em] opacity-75">
              {hoursLabel(todayMinutes / 60)}
            </p>
          ) : null}
        </div>

        <h2 className="mt-2 font-display text-4xl font-extrabold leading-none">
          {todayJobs.length === 0 ? (
            "Nothing booked"
          ) : (
            <>
              {todayJobs.length}{" "}
              <span className="text-xl font-semibold opacity-85">
                {todayJobs.length === 1 ? "job" : "jobs"} today
              </span>
            </>
          )}
        </h2>

        <WeekStrip today={today} busyDays={busyDays} />
      </section>

      {/* Money that needs doing, before anything else competes for attention. */}
      <div className="mt-3">
        <InvoiceReminders />
      </div>

      {/* Today's jobs, in order, straight under the band. */}
      <section className="mt-3 overflow-hidden rounded-2xl border border-border bg-card">
        <div className="flex items-center justify-between gap-3 px-4 py-2.5">
          <p className="figure text-[0.65rem] uppercase tracking-[0.14em] text-muted-foreground">
            Next up
          </p>
          <Button asChild size="sm" variant="ghost" className="-mr-2 h-8">
            <Link to="/visits">
              <Plus className="size-4" /> Log a visit
            </Link>
          </Button>
        </div>
        <div className="divide-y divide-border border-t border-border">
          {todayJobs.length === 0 ? (
            <p className="px-4 py-4 text-sm text-muted-foreground">
              Your day is clear — book something from the calendar.
            </p>
          ) : (
            todayJobs.map((job, index) => (
              <div
                key={job.id}
                className="stripe flex items-center gap-3 py-3 pl-4 pr-3"
                style={{
                  ["--stripe" as string]:
                    index === 0 ? "var(--color-lime)" : "var(--color-border)",
                }}
              >
                <p className="figure w-12 shrink-0 text-base font-semibold leading-none">
                  {format(parseISO(job.starts_at), "HH:mm")}
                </p>
                <p className="min-w-0 flex-1 truncate font-medium">{clientName(job.client_id)}</p>
                <span className="figure shrink-0 text-xs text-muted-foreground">
                  {job.duration_minutes} min
                </span>
              </div>
            ))
          )}
        </div>
      </section>

      {/*
        Names them. "1 scheduled job is past its date" says nothing about which
        one, so a job finished this morning and a job forgotten last week read
        identically — and the natural assumption is that the app has failed to
        notice the one you just did.
      */}
      {overdue.length > 0 ? (
        <Card className="mt-4 border-destructive/40">
          <CardContent className="space-y-3 py-4">
            <p className="text-sm font-medium">
              {overdue.length === 1
                ? "This job is past its date"
                : `These ${overdue.length} jobs are past their date`}{" "}
              and not marked complete:
            </p>
            <ul className="space-y-1">
              {overdue.slice(0, 5).map((job) => (
                <li key={job.id} className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="min-w-0 truncate font-medium">
                    {clientName(job.client_id)}
                  </span>
                  <span className="figure shrink-0 text-xs text-muted-foreground">
                    {format(parseISO(job.starts_at), "EEE d MMM")}
                  </span>
                </li>
              ))}
              {overdue.length > 5 ? (
                <li className="figure text-xs text-muted-foreground">
                  and {overdue.length - 5} more
                </li>
              ) : null}
            </ul>
            <Button asChild size="sm" variant="outline" className="h-10 w-full">
              <Link to="/schedule">Review schedule</Link>
            </Button>
          </CardContent>
        </Card>
      ) : null}

      {/* 2. Next 7 days */}
      <Card className="mt-4">
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="font-display text-xl font-bold tracking-tight">Next 7 days</CardTitle>
          <Button asChild size="sm" variant="ghost">
            <Link to="/schedule">Open calendar</Link>
          </Button>
        </CardHeader>
        <CardContent className="divide-y divide-border border-t border-border p-0">
          {upcoming.length === 0 ? (
            <p className="p-5 text-sm text-muted-foreground">Nothing booked yet.</p>
          ) : (
            upcoming.map((job) => (
              <div key={job.id} className="flex items-center gap-4 px-5 py-3">
                <div className="w-12 shrink-0 text-center">
                  <p className="figure text-[0.6rem] uppercase tracking-[0.12em] text-muted-foreground">
                    {format(parseISO(job.starts_at), "EEE")}
                  </p>
                  <p className="figure text-lg font-semibold leading-none">
                    {format(parseISO(job.starts_at), "d")}
                  </p>
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium">{clientName(job.client_id)}</p>
                  <p className="figure text-xs text-muted-foreground">
                    {format(parseISO(job.starts_at), "HH:mm")} · {job.duration_minutes} min
                  </p>
                </div>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      {/* 3. Hours and billing summary */}
      <div className="mt-4 grid grid-cols-2 gap-px overflow-hidden rounded-2xl bg-border lg:grid-cols-4">
        {stats.map((s) => (
          <div key={s.label} className="bg-card p-4">
            <s.icon className="size-4 text-primary" />
            <p className="figure mt-3 text-xl font-semibold">{s.value}</p>
            <p className="figure text-[0.6rem] uppercase tracking-[0.12em] text-muted-foreground">
              {s.label}
            </p>
          </div>
        ))}
      </div>

      {/* 4. Recent invoices with status */}
      <Card className="mt-4">
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="font-display text-xl font-bold tracking-tight">Billing</CardTitle>
          <Button asChild size="sm" variant="ghost">
            <Link to="/invoices">All invoices</Link>
          </Button>
        </CardHeader>
        <CardContent className="divide-y divide-border border-t border-border p-0">
          {recentInvoices.length === 0 ? (
            <p className="p-5 text-sm text-muted-foreground">No invoices yet.</p>
          ) : (
            recentInvoices.map((inv) => {
              const s = STATUS_STYLES[inv.status];
              const StatusIcon = s.icon;
              return (
                <div key={inv.id} className="flex items-center gap-3 px-5 py-3">
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">{clientName(inv.client_id)}</p>
                    <p className="figure truncate text-xs text-muted-foreground">
                      {/* No year: it never fits beside the chip, and a
                          truncated one reads worse than none. */}
                      {inv.number} · {format(parseISO(inv.issue_date), "d MMM")}
                    </p>
                  </div>
                  <span
                    className={`figure inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-0.5 text-[0.6rem] uppercase tracking-[0.08em] ${s.className}`}
                  >
                    <StatusIcon className="size-3.5" />
                    {s.label}
                  </span>
                  <p className="figure w-24 shrink-0 text-right text-base font-semibold">
                    {money(inv.total)}
                  </p>
                </div>
              );
            })
          )}
        </CardContent>
      </Card>


      {unbilledTotal > 0 || outstandingTotal > 0 ? (
        <p className="mt-3 text-sm text-muted-foreground">
          {money(unbilledTotal)} ready to bill · {money(outstandingTotal)} awaiting payment.{" "}
          <Link to="/invoices" className="font-medium text-primary underline-offset-4 hover:underline">
            Go to invoices
          </Link>
        </p>
      ) : null}
    </AppShell>
  );
}
