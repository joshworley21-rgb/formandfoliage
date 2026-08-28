import { Link } from "@tanstack/react-router";
import { format, parseISO } from "date-fns";
import { BellRing } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useClients, useRecurring, useSeries, useVisits } from "@/hooks/use-crm";
import { money, type Frequency } from "@/lib/crm";

/** Unbilled visits needed before we nudge, by how often the client is visited. */
export const REMINDER_THRESHOLDS: Record<Frequency, number> = {
  weekly: 4,
  fortnightly: 2,
  four_weekly: 1,
  monthly: 1,
  quarterly: 1,
};

/** Used when a client has no recurring schedule to tell us their visit rhythm. */
export const DEFAULT_REMINDER_THRESHOLD = 4;

const FREQUENCY_LABEL: Record<Frequency, string> = {
  weekly: "weekly",
  fortnightly: "fortnightly",
  four_weekly: "four-weekly",
  monthly: "monthly",
  quarterly: "quarterly",
};

export type InvoiceReminder = {
  clientId: string;
  clientName: string;
  visits: number;
  value: number;
  oldest: string;
  threshold: number;
  frequency: Frequency | null;
};

export function useInvoiceReminders(): InvoiceReminder[] {
  const { data: visits = [] } = useVisits();
  const { data: clients = [] } = useClients();
  const { data: series = [] } = useSeries();
  const { data: recurring = [] } = useRecurring();

  // Most frequent active rhythm we know about for each client.
  const frequencyByClient = new Map<string, Frequency>();
  const rank = (f: Frequency) => REMINDER_THRESHOLDS[f];
  for (const source of [...series, ...recurring]) {
    if (!source.active) continue;
    const current = frequencyByClient.get(source.client_id);
    if (!current || rank(source.frequency) > rank(current)) {
      frequencyByClient.set(source.client_id, source.frequency);
    }
  }

  const byClient = new Map<string, { visits: number; value: number; oldest: string }>();
  for (const visit of visits) {
    // A visit that never happened is not work waiting to be invoiced.
    if (visit.billed || visit.missed) continue;
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
    .map(([clientId, v]) => {
      const frequency = frequencyByClient.get(clientId) ?? null;
      return {
        clientId,
        clientName: clients.find((c) => c.id === clientId)?.name ?? "Client",
        visits: v.visits,
        value: v.value,
        oldest: v.oldest,
        frequency,
        threshold: frequency ? REMINDER_THRESHOLDS[frequency] : DEFAULT_REMINDER_THRESHOLD,
      };
    })
    .filter((r) => r.visits >= r.threshold)
    .sort((a, b) => b.visits / b.threshold - a.visits / a.threshold);
}

export function InvoiceReminders() {
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
              {reminders.length === 1 ? "This client has" : "These clients have"} enough unbilled
              visits to bill — 4 for weekly clients, 2 for fortnightly.
            </p>
          </div>
        </div>

        {/* Each name and its detail line take the full width on a phone:
            sharing a row with the amount and the button left them about two
            words wide. */}
        <ul className="divide-y divide-border rounded-xl border border-border bg-card">
          {reminders.map((r) => (
            <li
              key={r.clientId}
              className="flex flex-wrap items-center gap-x-3 gap-y-2 px-3 py-2.5"
            >
              <div className="w-full min-w-0 sm:flex-1">
                <p className="truncate font-medium">{r.clientName}</p>
                <p className="text-xs text-muted-foreground">
                  {r.visits} unbilled {r.visits === 1 ? "visit" : "visits"}
                  {r.frequency
                    ? ` · ${FREQUENCY_LABEL[r.frequency]} (bill every ${r.threshold})`
                    : ""}{" "}
                  · oldest {format(parseISO(r.oldest), "d MMM")}
                </p>
              </div>
              <p className="font-display text-lg">{money(r.value)}</p>
              {/*
                Naming the client means the invoice opens on them with their
                visits already ticked, rather than landing on the list and
                asking again.

                This used to be hidden on the Invoices screen, on the grounds
                that the page already has a New invoice button. It does — and
                that one opens with no client chosen, so you then pick the
                client and tick the visits by hand. They were never the same
                thing, and the shortcut was missing from the one screen where
                somebody has already decided to do some invoicing.
              */}
              <Button asChild size="sm" variant="outline">
                <Link to="/invoices" search={{ client: r.clientId }}>
                  Create invoice
                </Link>
              </Button>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}
