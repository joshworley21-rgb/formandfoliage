import { createFileRoute, Link } from "@tanstack/react-router";
import {
  addDays,
  endOfMonth,
  endOfWeek,
  format,
  isBefore,
  isSameDay,
  parseISO,
  startOfDay,
  startOfMonth,
  startOfWeek,
  subDays,
} from "date-fns";
import {
  ArrowRight,
  CalendarDays,
  ChevronRight,
  Clock,
  PoundSterling,
  TrendingDown,
  TrendingUp,
} from "lucide-react";
import { useState } from "react";
import { AppShell } from "@/components/AppShell";
import { useInvoiceReminders } from "@/components/InvoiceReminders";
import { OwnerOnly } from "@/components/OwnerOnly";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useClients, useInvoices, useJobs, useVisits } from "@/hooks/use-crm";
import { hoursLabel, money } from "@/lib/crm";
import { storedUser } from "@/lib/session";
import { cn } from "@/lib/utils";

export const Route = createFileRoute("/_authenticated/dashboard")({
  head: () => ({
    meta: [
      { title: "Home — JW Garden Services CRM" },
      {
        name: "description",
        content: "This week's money, today's round and the work waiting to be billed.",
      },
      { property: "og:title", content: "Home — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "This week's money, today's round and the work waiting to be billed.",
      },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <DashboardPage />
    </OwnerOnly>
  ),
});

type HomeTab = "week" | "today" | "cash";

/** Warm greeting from the stored session, without asking the network. */
function greetingFor(date: Date): string {
  const hour = date.getHours();
  const greeting = hour < 12 ? "Morning" : hour < 18 ? "Afternoon" : "Evening";
  const email = storedUser()?.email ?? "";
  const raw = email.split("@")[0]?.split(/[._-]/)[0] ?? "";
  const name = raw ? raw.charAt(0).toUpperCase() + raw.slice(1) : "there";
  return `${greeting}, ${name}`;
}

function between(date: string, start: Date, end: Date): boolean {
  const d = parseISO(date);
  return d >= start && d <= end;
}

function daysLate(issueDate: string, dueDate: string | null, today: Date): number {
  const due = dueDate ? parseISO(dueDate) : addDays(parseISO(issueDate), 30);
  return Math.max(0, Math.floor((today.getTime() - due.getTime()) / 86_400_000));
}

function BarChart({
  series,
  today,
}: {
  series: { day: Date; billed: number; unbilled: number }[];
  today: Date;
}) {
  const max = Math.max(1, ...series.map((point) => Math.max(point.billed, point.unbilled)));

  return (
    <div>
      <div className="flex items-end justify-between gap-1">
        {series.map((point) => {
          const isToday = isSameDay(point.day, today);
          return (
            <div
              key={point.day.toISOString()}
              className={cn(
                "flex min-w-0 flex-1 flex-col items-center rounded-lg pt-2",
                isToday ? "bg-secondary/70" : "",
              )}
            >
              <div className="flex h-28 w-full items-end justify-center gap-[3px] px-1">
                <div
                  className="w-[38%] max-w-2.5 rounded-t-sm bg-primary"
                  style={{ height: `${Math.max(3, (point.billed / max) * 100)}%` }}
                  title={`Billed ${money(point.billed)}`}
                />
                <div
                  className="w-[38%] max-w-2.5 rounded-t-sm bg-leaf/55"
                  style={{ height: `${Math.max(3, (point.unbilled / max) * 100)}%` }}
                  title={`Unbilled ${money(point.unbilled)}`}
                />
              </div>
              <p
                className={cn(
                  "figure mt-1.5 pb-1 text-[0.6rem]",
                  isToday ? "font-semibold text-foreground" : "text-muted-foreground",
                )}
              >
                {format(point.day, "EEE")}
              </p>
            </div>
          );
        })}
      </div>
      <div className="mt-2 flex items-center justify-center gap-4 text-[0.62rem] text-muted-foreground">
        <span className="flex items-center gap-1.5">
          <span className="size-2 rounded-sm bg-primary" /> Billed
        </span>
        <span className="flex items-center gap-1.5">
          <span className="size-2 rounded-sm bg-leaf/55" /> Unbilled
        </span>
      </div>
    </div>
  );
}

function SummaryRow({
  label,
  children,
  emphasis = false,
}: {
  label: string;
  children: React.ReactNode;
  emphasis?: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-border py-2.5 last:border-b-0">
      <span
        className={cn(
          "text-sm",
          emphasis ? "font-semibold text-foreground" : "text-muted-foreground",
        )}
      >
        {label}
      </span>
      <span className={cn("figure text-sm", emphasis ? "font-semibold" : "font-medium")}>
        {children}
      </span>
    </div>
  );
}

function DashboardPage() {
  const today = startOfDay(new Date());
  const weekStart = startOfWeek(today, { weekStartsOn: 1 });
  const weekEnd = endOfWeek(today, { weekStartsOn: 1 });
  const lastWeekStart = subDays(weekStart, 7);
  const lastWeekEnd = subDays(weekEnd, 7);
  const monthStart = startOfMonth(today);
  const monthEnd = endOfMonth(today);

  const [tab, setTab] = useState<HomeTab>("week");

  const reminders = useInvoiceReminders();
  const { data: clients = [], error: clientsError } = useClients();
  const { data: visits = [], error: visitsError } = useVisits();
  const { data: invoices = [], error: invoicesError } = useInvoices();
  const { data: jobs = [], error: jobsError } = useJobs({
    from: addDays(today, -30).toISOString(),
    to: addDays(today, 8).toISOString(),
  });

  const error = clientsError || visitsError || invoicesError || jobsError;
  if (error)
    return (
      <AppShell title="Home">
        <p className="text-destructive">Error loading home: {error.message}</p>
      </AppShell>
    );

  const clientName = (id: string) => clients.find((c) => c.id === id)?.name ?? "Client";

  const weekVisits = visits.filter((v) => between(v.visit_date, weekStart, weekEnd));
  const lastWeekVisits = visits.filter((v) => between(v.visit_date, lastWeekStart, lastWeekEnd));
  const monthVisits = visits.filter((v) => between(v.visit_date, monthStart, monthEnd));

  const sum = (rows: typeof visits, key: "amount" | "materials_cost" | "hours") =>
    rows.reduce((total, row) => total + Number(row[key] ?? 0), 0);

  const weekIncome = sum(weekVisits, "amount");
  const weekMaterials = sum(weekVisits, "materials_cost");
  const weekHours = sum(weekVisits, "hours");
  const weekNet = weekIncome - weekMaterials;
  const lastWeekNet = sum(lastWeekVisits, "amount") - sum(lastWeekVisits, "materials_cost");
  const netDelta = weekNet - lastWeekNet;
  const weekUnbilled = weekVisits
    .filter((v) => !v.billed && !v.missed)
    .reduce((total, v) => total + Number(v.amount), 0);

  const monthIncome = sum(monthVisits, "amount");
  const monthVisitsCount = monthVisits.filter((v) => !v.missed).length;

  const scheduled = jobs.filter((j) => j.status === "scheduled");
  const weekJobs = scheduled.filter((j) => {
    const date = parseISO(j.starts_at);
    return date >= weekStart && date <= weekEnd;
  });
  const scheduledHours = weekJobs.reduce((total, job) => total + job.duration_minutes / 60, 0);
  const capacity = Math.round((scheduledHours / 40) * 100);

  const todayJobs = scheduled.filter((j) => isSameDay(parseISO(j.starts_at), today));
  const overdue = scheduled.filter((j) => isBefore(parseISO(j.starts_at), today));
  const upcoming = scheduled
    .filter((j) => parseISO(j.starts_at) > endOfWeek(today, { weekStartsOn: 1 }))
    .slice(0, 3);

  const weekDays = Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
  const series = weekDays.map((day) => {
    const dayVisits = weekVisits.filter((v) => isSameDay(parseISO(v.visit_date), day));
    return {
      day,
      billed: dayVisits
        .filter((v) => v.billed && !v.missed)
        .reduce((total, v) => total + Number(v.amount), 0),
      unbilled: dayVisits
        .filter((v) => !v.billed && !v.missed)
        .reduce((total, v) => total + Number(v.amount), 0),
    };
  });

  const busiest = [...series].sort((a, b) => b.billed + b.unbilled - (a.billed + a.unbilled))[0];
  const busiestTotal = busiest ? busiest.billed + busiest.unbilled : 0;

  const byClient = new Map<string, number>();
  for (const visit of weekVisits) {
    if (visit.missed) continue;
    byClient.set(visit.client_id, (byClient.get(visit.client_id) ?? 0) + Number(visit.amount));
  }
  const topClient = [...byClient.entries()].sort((a, b) => b[1] - a[1])[0];

  const unpaid = invoices
    .filter((invoice) => invoice.status !== "paid")
    .sort((a, b) => a.issue_date.localeCompare(b.issue_date));
  const outstanding = unpaid.reduce((total, invoice) => total + Number(invoice.total), 0);

  const weekVisitCount = weekVisits.filter((v) => !v.missed).length;

  return (
    <AppShell title={greetingFor(today)} description={format(today, "EEEE d MMMM")} overlap>
      {/* The two numbers the day opens with. Half on the band, half on paper. */}
      <div className="grid grid-cols-2 gap-3">
        <div className="rounded-2xl bg-band px-4 py-4 text-band-foreground shadow-lg shadow-band/15">
          <p className="text-[0.58rem] uppercase tracking-[0.16em] text-band-foreground/50">
            This week
          </p>
          <p className="figure mt-2 font-display text-[1.9rem] font-semibold leading-none text-gold">
            {money(weekIncome)}
          </p>
          <p className="mt-1.5 text-[0.7rem] text-band-foreground/55">
            {weekVisitCount} {weekVisitCount === 1 ? "visit" : "visits"} · so far
          </p>
        </div>
        <div className="rounded-2xl bg-band px-4 py-4 text-band-foreground shadow-lg shadow-band/15">
          <p className="text-[0.58rem] uppercase tracking-[0.16em] text-band-foreground/50">
            This month
          </p>
          <p className="figure mt-2 font-display text-[1.9rem] font-semibold leading-none text-gold">
            {money(monthIncome)}
          </p>
          <p className="mt-1.5 text-[0.7rem] text-band-foreground/55">
            {monthVisitsCount} {monthVisitsCount === 1 ? "visit" : "visits"} · so far
          </p>
        </div>
      </div>

      <Link
        to={reminders.length ? "/invoices" : "/visits"}
        className="mt-3 flex items-center gap-2.5 rounded-full border border-primary/25 bg-secondary/60 px-4 py-2.5 transition-colors hover:bg-secondary"
      >
        <span aria-hidden className="text-base leading-none">
          📌
        </span>
        <span className="min-w-0 flex-1 truncate text-sm font-medium">
          {reminders.length === 0
            ? "No invoice reminders today"
            : `${reminders.length} ${reminders.length === 1 ? "client is" : "clients are"} ready to invoice`}
        </span>
        <span className="shrink-0 rounded-full bg-primary px-2.5 py-1 text-[0.6rem] font-semibold uppercase tracking-[0.08em] text-primary-foreground">
          {reminders.length === 0 ? "+ Add" : "Review"}
        </span>
      </Link>

      {/* The working card: the week's story, today's round, or the money owed. */}
      <section className="mt-3 overflow-hidden rounded-2xl border border-border bg-card">
        <div className="p-3 pb-2">
          <div className="grid grid-cols-3 gap-1 rounded-full bg-secondary p-1">
            {(
              [
                ["week", "This week"],
                ["today", "Today"],
                ["cash", "Cash flow"],
              ] as const
            ).map(([value, label]) => (
              <button
                key={value}
                type="button"
                onClick={() => setTab(value)}
                className={cn(
                  "rounded-full px-2 py-1.5 text-[0.78rem] font-medium transition-colors",
                  tab === value
                    ? "bg-primary text-primary-foreground shadow-sm"
                    : "text-muted-foreground hover:text-foreground",
                )}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        {tab === "week" ? (
          <div className="px-4 pb-4">
            <div className="flex items-center gap-1.5 border-b border-border pb-2.5 text-sm">
              {netDelta < 0 ? (
                <TrendingDown className="size-4 text-destructive" />
              ) : (
                <TrendingUp className="size-4 text-primary" />
              )}
              <span
                className={cn(
                  "figure font-medium",
                  netDelta < 0 ? "text-destructive" : "text-primary",
                )}
              >
                {netDelta < 0 ? "↓" : "↑"} {money(Math.abs(netDelta))} net
              </span>
              <span className="text-muted-foreground">vs last week</span>
            </div>

            <p className="mt-3 text-[0.6rem] uppercase tracking-[0.14em] text-muted-foreground">
              This week, day by day
            </p>
            <div className="mt-2">
              <BarChart series={series} today={today} />
            </div>

            <div className="mt-3 border-t border-border pt-1">
              <SummaryRow label="Busiest day">
                {busiestTotal > 0
                  ? `${format(busiest!.day, "EEEE")} · ${money(busiestTotal)}`
                  : "—"}
              </SummaryRow>
              <SummaryRow label="Top client">
                {topClient ? `${clientName(topClient[0])} · ${money(topClient[1])}` : "—"}
              </SummaryRow>
              <SummaryRow label="Capacity">
                {capacity}% · {hoursLabel(scheduledHours)} of 40h
              </SummaryRow>
            </div>

            <p className="mt-3 text-[0.6rem] uppercase tracking-[0.14em] text-muted-foreground">
              Profit &amp; loss
            </p>
            <div className="mt-1">
              <SummaryRow label="Income">{money(weekIncome)}</SummaryRow>
              <SummaryRow label="Materials">{money(weekMaterials)}</SummaryRow>
              <SummaryRow label="Net profit" emphasis>
                {money(weekNet)}
              </SummaryRow>
            </div>
          </div>
        ) : null}

        {tab === "today" ? (
          <div className="px-4 pb-4">
            <div className="flex items-center justify-between gap-2 border-b border-border pb-2.5">
              <p className="text-sm text-muted-foreground">
                {todayJobs.length === 0
                  ? "Your day is clear."
                  : `${todayJobs.length} ${todayJobs.length === 1 ? "job" : "jobs"} today`}
              </p>
              <Button asChild size="sm" variant="ghost" className="h-8 text-primary">
                <Link to="/schedule">
                  Open rota <ChevronRight className="size-3.5" />
                </Link>
              </Button>
            </div>

            {overdue.length > 0 ? (
              <div className="mt-3 flex items-start gap-2.5 rounded-xl border border-warning/40 bg-warning/10 px-3 py-2.5">
                <Clock className="mt-0.5 size-4 shrink-0 text-warning" />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-foreground">
                    {overdue.length} {overdue.length === 1 ? "job is" : "jobs are"} past their date
                  </p>
                  <p className="mt-0.5 truncate text-xs text-muted-foreground">
                    {overdue
                      .slice(0, 2)
                      .map((job) => clientName(job.client_id))
                      .join(", ")}
                    {overdue.length > 2 ? ` and ${overdue.length - 2} more` : ""}
                  </p>
                </div>
                <Link
                  to="/schedule"
                  className="shrink-0 self-center text-xs font-semibold text-warning"
                >
                  Review
                </Link>
              </div>
            ) : null}

            <div className="mt-1 divide-y divide-border">
              {todayJobs.length === 0 ? (
                <div className="py-6 text-center">
                  <CalendarDays className="mx-auto size-6 text-muted-foreground/60" />
                  <p className="mt-2 text-sm text-muted-foreground">
                    Nothing booked. Give yourself a day off, or add a job.
                  </p>
                  <Button asChild size="sm" variant="gold" className="mt-3">
                    <Link to="/schedule">Book a job</Link>
                  </Button>
                </div>
              ) : (
                todayJobs.map((job, index) => (
                  <div key={job.id} className="flex items-center gap-3 py-3">
                    <span
                      className={cn(
                        "h-8 w-1 shrink-0 rounded-full",
                        index === 0 ? "bg-lime" : "bg-border",
                      )}
                      aria-hidden
                    />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-semibold">{clientName(job.client_id)}</p>
                      <p className="figure text-xs text-muted-foreground">
                        {format(parseISO(job.starts_at), "HH:mm")} · {job.duration_minutes} min
                      </p>
                    </div>
                    {job.notes ? (
                      <span className="max-w-[38%] truncate text-xs text-muted-foreground">
                        {job.notes}
                      </span>
                    ) : null}
                  </div>
                ))
              )}
            </div>

            {upcoming.length > 0 ? (
              <div className="mt-3 rounded-xl bg-background px-3 py-2">
                <p className="text-[0.6rem] uppercase tracking-[0.14em] text-muted-foreground">
                  After this week
                </p>
                {upcoming.map((job) => (
                  <div key={job.id} className="flex items-center justify-between gap-2 py-1.5">
                    <span className="truncate text-sm">{clientName(job.client_id)}</span>
                    <span className="figure shrink-0 text-xs text-muted-foreground">
                      {format(parseISO(job.starts_at), "EEE d MMM")}
                    </span>
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}

        {tab === "cash" ? (
          <div className="px-4 pb-4">
            <div className="flex items-end justify-between gap-3 border-b border-border pb-3">
              <div>
                <p className="text-[0.6rem] uppercase tracking-[0.14em] text-muted-foreground">
                  Owed to you
                </p>
                <p className="figure mt-1 font-display text-2xl font-semibold">
                  {money(outstanding)}
                </p>
              </div>
              <div className="text-right">
                <p className="figure text-sm font-semibold">{money(weekUnbilled)}</p>
                <p className="text-[0.6rem] uppercase tracking-[0.1em] text-muted-foreground">
                  unbilled this week
                </p>
              </div>
            </div>

            <div className="divide-y divide-border">
              {unpaid.length === 0 ? (
                <p className="py-5 text-center text-sm text-muted-foreground">
                  Everything is paid. Nothing to chase.
                </p>
              ) : (
                unpaid.slice(0, 5).map((invoice) => {
                  const late = daysLate(invoice.issue_date, invoice.due_date, today);
                  return (
                    <div key={invoice.id} className="flex items-center gap-3 py-2.5">
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">
                          {clientName(invoice.client_id)}
                        </p>
                        <p className="figure text-xs text-muted-foreground">
                          {invoice.number}
                          {late > 0 ? ` · ${late} ${late === 1 ? "day" : "days"} over` : ""}
                        </p>
                      </div>
                      {late > 0 ? (
                        <Badge variant="outline" className="border-warning text-warning">
                          Late
                        </Badge>
                      ) : null}
                      <p className="figure text-sm font-semibold">{money(invoice.total)}</p>
                    </div>
                  );
                })
              )}
            </div>

            <Button asChild size="sm" variant="ghost" className="mt-2 h-9 w-full text-primary">
              <Link to="/invoices">
                Go to invoices <ArrowRight className="size-3.5" />
              </Link>
            </Button>
          </div>
        ) : null}
      </section>

      {/* The next three jobs, so Home still answers "what is coming?". */}
      {upcoming.length > 0 && tab !== "today" ? (
        <section className="mt-3 overflow-hidden rounded-2xl border border-border bg-card">
          <div className="flex items-center justify-between gap-2 px-4 pb-1 pt-3">
            <p className="text-[0.6rem] uppercase tracking-[0.14em] text-muted-foreground">
              Coming up
            </p>
            <Link to="/schedule" className="text-xs font-medium text-primary">
              Rota
            </Link>
          </div>
          <div className="divide-y divide-border px-4">
            {upcoming.map((job) => (
              <div key={job.id} className="flex items-center gap-3 py-2.5">
                <div className="w-9 text-center">
                  <p className="figure text-[0.55rem] uppercase tracking-[0.1em] text-muted-foreground">
                    {format(parseISO(job.starts_at), "EEE")}
                  </p>
                  <p className="figure text-sm font-semibold leading-none">
                    {format(parseISO(job.starts_at), "d")}
                  </p>
                </div>
                <p className="min-w-0 flex-1 truncate text-sm">{clientName(job.client_id)}</p>
                <p className="figure text-xs text-muted-foreground">
                  {format(parseISO(job.starts_at), "HH:mm")}
                </p>
              </div>
            ))}
          </div>
        </section>
      ) : null}

      <p className="mt-3 flex items-center justify-center gap-1.5 text-[0.65rem] text-muted-foreground">
        <PoundSterling className="size-3.5" />
        Figures are from logged visits, not invoices.
      </p>
    </AppShell>
  );
}
