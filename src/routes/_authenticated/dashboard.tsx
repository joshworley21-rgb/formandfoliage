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
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <Card key={s.label}>
            <CardContent className="flex items-center gap-3 py-4">
              <span className="flex size-10 items-center justify-center rounded-xl bg-primary/12 text-primary">
                <s.icon className="size-5" />
              </span>
              <div>
                <p className="text-xs uppercase tracking-wide text-muted-foreground">{s.label}</p>
                <p className="font-display text-xl font-semibold">{s.value}</p>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

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

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle>Next 7 days</CardTitle>
            <Button asChild size="sm" variant="ghost">
              <Link to="/schedule">Open calendar</Link>
            </Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {upcoming.length === 0 ? (
              <p className="text-sm text-muted-foreground">Nothing booked yet.</p>
            ) : (
              upcoming.map((job) => (
                <div
                  key={job.id}
                  className="flex items-center justify-between rounded-lg border border-border px-3 py-2"
                >
                  <div>
                    <p className="font-medium">{clientName(job.client_id)}</p>
                    <p className="text-sm text-muted-foreground">
                      {format(parseISO(job.starts_at), "EEE d MMM, HH:mm")}
                    </p>
                  </div>
                  <Badge variant="secondary">{job.duration_minutes} min</Badge>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle>Ready to invoice</CardTitle>
            <Button asChild size="sm" variant="ghost">
              <Link to="/invoices">Create invoice</Link>
            </Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {unbilled.length === 0 ? (
              <p className="text-sm text-muted-foreground">Everything is billed. Nice.</p>
            ) : (
              Object.entries(
                unbilled.reduce<Record<string, number>>((acc, v) => {
                  acc[v.client_id] = (acc[v.client_id] ?? 0) + Number(v.amount);
                  return acc;
                }, {}),
              ).map(([id, amount]) => (
                <div
                  key={id}
                  className="flex items-center justify-between rounded-lg border border-border px-3 py-2"
                >
                  <p className="font-medium">{clientName(id)}</p>
                  <p className="font-semibold text-primary">{money(amount)}</p>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>
    </AppShell>
  );
}
