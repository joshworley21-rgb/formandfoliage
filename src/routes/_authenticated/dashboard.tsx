import { createFileRoute, Link } from "@tanstack/react-router";
import { addDays, format, isBefore, parseISO, startOfDay } from "date-fns";
import { CalendarDays, ClipboardList, PoundSterling, Users } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useClients, useInvoices, useJobs, useVisits } from "@/hooks/use-crm";
import { money } from "@/lib/crm";

export const Route = createFileRoute("/_authenticated/dashboard")({
  head: () => ({
    meta: [
      { title: "Dashboard — JW Garden Services CRM" },
      { name: "description", content: "Today's round, unbilled work and outstanding invoices at a glance." },
      { property: "og:title", content: "Dashboard — JW Garden Services CRM" },
      { property: "og:description", content: "Today's round, unbilled work and outstanding invoices at a glance." },
    ],
  }),
  component: DashboardPage,
});

function DashboardPage() {
  const today = startOfDay(new Date());
  const { data: clients = [] } = useClients();
  const { data: visits = [] } = useVisits();
  const { data: invoices = [] } = useInvoices();
  const { data: jobs = [] } = useJobs({
    from: addDays(today, -30).toISOString(),
    to: addDays(today, 8).toISOString(),
  });

  const unbilled = visits.filter((v) => !v.billed);
  const unbilledTotal = unbilled.reduce((s, v) => s + Number(v.amount), 0);
  const outstanding = invoices.filter((i) => i.status !== "paid");
  const outstandingTotal = outstanding.reduce((s, i) => s + Number(i.total), 0);

  const upcoming = jobs
    .filter((j) => j.status === "scheduled" && parseISO(j.starts_at) >= today)
    .slice(0, 8);
  const overdue = jobs.filter(
    (j) => j.status === "scheduled" && isBefore(parseISO(j.starts_at), today),
  );

  const clientName = (id: string) => clients.find((c) => c.id === id)?.name ?? "Client";

  const stats = [
    { label: "Active clients", value: String(clients.filter((c) => c.active).length), icon: Users },
    { label: "Unbilled work", value: money(unbilledTotal), icon: ClipboardList },
    { label: "Awaiting payment", value: money(outstandingTotal), icon: PoundSterling },
    { label: "Jobs next 7 days", value: String(upcoming.length), icon: CalendarDays },
  ];

  return (
    <AppShell title="Dashboard" description="Where the round stands today.">
      <section className="overflow-hidden rounded-3xl border border-border bg-card">
        <div className="grid gap-6 p-6 sm:p-8 lg:grid-cols-[1.1fr_1fr] lg:items-center">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">
              {format(today, "EEEE d MMMM")}
            </p>
            <h2 className="mt-2 font-display text-4xl leading-tight sm:text-5xl">
              {money(unbilledTotal)}{" "}
              <span className="italic text-primary">ready to bill</span>
            </h2>
            <p className="mt-3 max-w-md text-muted-foreground">
              {unbilled.length} unbilled {unbilled.length === 1 ? "visit" : "visits"} ·{" "}
              {money(outstandingTotal)} awaiting payment across {outstanding.length}{" "}
              {outstanding.length === 1 ? "invoice" : "invoices"}.
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              <Button asChild>
                <Link to="/invoices">Create invoice</Link>
              </Button>
              <Button asChild variant="outline">
                <Link to="/schedule">Open calendar</Link>
              </Button>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-px overflow-hidden rounded-2xl bg-border">
            {stats.map((s) => (
              <div key={s.label} className="bg-card p-5">
                <s.icon className="size-5 text-primary" />
                <p className="mt-3 font-display text-2xl">{s.value}</p>
                <p className="text-xs uppercase tracking-wide text-muted-foreground">{s.label}</p>
              </div>
            ))}
          </div>
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

      <div className="mt-4 grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-3">
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

        <Card className="lg:col-span-2">
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle className="font-display text-2xl font-normal">Ready to invoice</CardTitle>
            <Button asChild size="sm" variant="ghost">
              <Link to="/invoices">New</Link>
            </Button>
          </CardHeader>
          <CardContent className="divide-y divide-border border-t border-border p-0">
            {unbilled.length === 0 ? (
              <p className="p-5 text-sm text-muted-foreground">Everything is billed. Nice.</p>
            ) : (
              Object.entries(
                unbilled.reduce<Record<string, number>>((acc, v) => {
                  acc[v.client_id] = (acc[v.client_id] ?? 0) + Number(v.amount);
                  return acc;
                }, {}),
              ).map(([id, amount]) => (
                <div key={id} className="flex items-center justify-between px-5 py-3">
                  <p className="truncate font-medium">{clientName(id)}</p>
                  <p className="font-display text-lg text-primary">{money(amount)}</p>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>
    </AppShell>
  );
}

