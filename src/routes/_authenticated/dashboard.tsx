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
} from "date-fns";
import { CalendarDays, ClipboardList, Clock, PoundSterling, Plus } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { Badge } from "@/components/ui/badge";
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
  component: DashboardPage,
});

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

  const stats = [
    { label: "Hours this month", value: hoursLabel(monthHours), icon: Clock },
    { label: "Value logged", value: money(monthValue), icon: ClipboardList },
    { label: "Unbilled work", value: money(unbilledTotal), icon: PoundSterling },
    { label: "Jobs next 7 days", value: String(upcoming.length + todayJobs.length), icon: CalendarDays },
  ];

  return (
    <AppShell title="Dashboard" description="Today's round first, then hours and billing.">
      {/* 1. Today's jobs */}
      <section className="overflow-hidden rounded-3xl border border-border bg-card">
        <div className="flex flex-wrap items-center justify-between gap-3 p-6 pb-4 sm:p-8 sm:pb-5">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">
              {format(today, "EEEE d MMMM")}
            </p>
            <h2 className="mt-2 font-display text-4xl leading-tight sm:text-5xl">
              {todayJobs.length === 0 ? (
                <span className="italic text-primary">Nothing booked today</span>
              ) : (
                <>
                  {todayJobs.length} {todayJobs.length === 1 ? "job" : "jobs"}{" "}
                  <span className="italic text-primary">today</span>
                </>
              )}
            </h2>
            {todayMinutes > 0 ? (
              <p className="mt-2 text-muted-foreground">
                {hoursLabel(todayMinutes / 60)} scheduled on the round.
              </p>
            ) : null}
          </div>
          <Button asChild size="lg">
            <Link to="/visits">
              <Plus className="size-4" /> Log a visit
            </Link>
          </Button>
        </div>

        <div className="divide-y divide-border border-t border-border">
          {todayJobs.length === 0 ? (
            <p className="px-6 py-5 text-sm text-muted-foreground sm:px-8">
              Your day is clear — book something from the calendar.
            </p>
          ) : (
            todayJobs.map((job) => (
              <div key={job.id} className="flex items-center gap-4 px-6 py-3 sm:px-8">
                <p className="w-16 shrink-0 font-display text-xl leading-none">
                  {format(parseISO(job.starts_at), "HH:mm")}
                </p>
                <p className="min-w-0 flex-1 truncate font-medium">{clientName(job.client_id)}</p>
                <Badge variant="secondary">{job.duration_minutes} min</Badge>
              </div>
            ))
          )}
        </div>
      </section>

      {overdue.length > 0 ? (
        <Card className="mt-4 border-destructive/40">
          <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
            <p className="text-sm font-medium">
              {overdue.length} scheduled {overdue.length === 1 ? "job is" : "jobs are"} past their
              date and not marked complete.
            </p>
            <Button asChild size="sm" variant="outline">
              <Link to="/schedule">Review schedule</Link>
            </Button>
          </CardContent>
        </Card>
      ) : null}

      {/* 2. Next 7 days */}
      <Card className="mt-4">
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="font-display text-2xl font-normal">Next 7 days</CardTitle>
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
                <div className="w-14 shrink-0 text-center">
                  <p className="text-xs uppercase tracking-wide text-muted-foreground">
                    {format(parseISO(job.starts_at), "EEE")}
                  </p>
                  <p className="font-display text-xl leading-none">
                    {format(parseISO(job.starts_at), "d")}
                  </p>
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium">{clientName(job.client_id)}</p>
                  <p className="text-sm text-muted-foreground">
                    {format(parseISO(job.starts_at), "HH:mm")}
                  </p>
                </div>
                <Badge variant="secondary">{job.duration_minutes} min</Badge>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      {/* 3. Hours and billing summary */}
      <div className="mt-4 grid grid-cols-2 gap-px overflow-hidden rounded-2xl bg-border lg:grid-cols-4">
        {stats.map((s) => (
          <div key={s.label} className="bg-card p-5">
            <s.icon className="size-5 text-primary" />
            <p className="mt-3 font-display text-2xl">{s.value}</p>
            <p className="text-xs uppercase tracking-wide text-muted-foreground">{s.label}</p>
          </div>
        ))}
      </div>

      {/* 4. Recent invoices with status */}
      <Card className="mt-4">
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="font-display text-2xl font-normal">Billing</CardTitle>
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
                    <p className="text-sm text-muted-foreground">
                      {inv.number} · {format(parseISO(inv.issue_date), "d MMM yyyy")}
                    </p>
                  </div>
                  <span
                    className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold uppercase tracking-wide ${s.className}`}
                  >
                    <StatusIcon className="size-3.5" />
                    {s.label}
                  </span>
                  <p className="w-24 shrink-0 text-right font-display text-lg">
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
