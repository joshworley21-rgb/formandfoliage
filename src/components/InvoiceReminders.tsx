import { Link } from "@tanstack/react-router";
import { BellRing } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useClients, useVisits } from "@/hooks/use-crm";
import { money } from "@/lib/crm";

/** Nudge to raise an invoice once a client has this many unbilled visits. */
export const REMINDER_VISIT_THRESHOLD = 4;

export type InvoiceReminder = {
  clientId: string;
  clientName: string;
  visits: number;
  value: number;
  oldest: string;
};

export function useInvoiceReminders(): InvoiceReminder[] {
  const { data: visits = [] } = useVisits();
  const { data: clients = [] } = useClients();

  const byClient = new Map<string, { visits: number; value: number; oldest: string }>();
  for (const visit of visits) {
    if (visit.billed) continue;
    const current = byClient.get(visit.client_id) ?? {
      visits: 0,
      value: 0,
      oldest: visit.visit_date,
    };
    current.visits += 1;
    current.value += Number(visit.amount ?? 0);
    if (visit.visit_date < current.oldest) current.oldest = visit.visit_date;
    byClient.set(visit.client_id, current);
  }

  return [...byClient.entries()]
    .filter(([, v]) => v.visits >= REMINDER_VISIT_THRESHOLD)
    .map(([clientId, v]) => ({
      clientId,
      clientName: clients.find((c) => c.id === clientId)?.name ?? "Client",
      visits: v.visits,
      value: v.value,
      oldest: v.oldest,
    }))
    .sort((a, b) => b.visits - a.visits);
}

export function InvoiceReminders({ compact = false }: { compact?: boolean }) {
  const reminders = useInvoiceReminders();
  if (reminders.length === 0) return null;

  return (
    <Card className="border-primary/40 bg-primary/5">
      <CardContent className="space-y-3 py-4">
        <div className="flex items-start gap-2">
          <BellRing className="mt-0.5 size-5 shrink-0 text-primary" />
          <div>
            <p className="font-display text-lg font-semibold">Time to invoice</p>
            <p className="text-sm text-muted-foreground">
              {reminders.length === 1 ? "This client has" : "These clients have"}{" "}
              {REMINDER_VISIT_THRESHOLD}+ visits logged that haven&apos;t been billed yet.
            </p>
          </div>
        </div>
        <ul className="divide-y divide-border rounded-xl border border-border bg-card">
          {reminders.map((r) => (
            <li key={r.clientId} className="flex flex-wrap items-center gap-3 px-3 py-2.5">
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium">{r.clientName}</p>
                <p className="text-xs text-muted-foreground">
                  {r.visits} unbilled visits · oldest {r.oldest}
                </p>
              </div>
              <p className="font-display text-lg">{money(r.value)}</p>
              {compact ? null : (
                <Button asChild size="sm" variant="outline">
                  <Link to="/invoices">Create invoice</Link>
                </Button>
              )}
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}
