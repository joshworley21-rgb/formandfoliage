import { createFileRoute } from "@tanstack/react-router";
import { addDays, addMonths, addWeeks, format, parseISO } from "date-fns";
import { Plus, Repeat, Trash2, Zap } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
import { OwnerOnly } from "@/components/OwnerOnly";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  useClients,
  useDeleteRecurring,
  useRecurring,
  useSaveRecurring,
  useVisits,
} from "@/hooks/use-crm";
import { FREQUENCY_LABELS, money, type Frequency, type RecurringSchedule } from "@/lib/crm";
import { createInvoice } from "@/lib/invoicing";

export const Route = createFileRoute("/_authenticated/recurring")({
  head: () => ({
    meta: [
      { title: "Recurring billing — JW Garden Services CRM" },
      {
        name: "description",
        content: "Set clients on weekly, fortnightly, monthly or quarterly invoice schedules.",
      },
      { property: "og:title", content: "Recurring billing — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Set clients on weekly, fortnightly, monthly or quarterly invoice schedules.",
      },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <RecurringPage />
    </OwnerOnly>
  ),
});

function advance(date: Date, frequency: Frequency) {
  switch (frequency) {
    case "weekly":
      return addWeeks(date, 1);
    case "fortnightly":
      return addWeeks(date, 2);
    case "four_weekly":
      return addWeeks(date, 4);
    case "quarterly":
      return addMonths(date, 3);
    default:
      return addMonths(date, 1);
  }
}

function RecurringPage() {
  const { data: clients = [] } = useClients();
  const { data: schedules = [] } = useRecurring();
  const { data: visits = [] } = useVisits();
  const saveRecurring = useSaveRecurring();
  const deleteRecurring = useDeleteRecurring();
  const createFn = createInvoice;
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<RecurringSchedule | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const clientName = (id: string) => clients.find((c) => c.id === id)?.name ?? "Client";
  const today = format(new Date(), "yyyy-MM-dd");

  async function runNow(schedule: RecurringSchedule) {
    setBusy(schedule.id);
    try {
      const visitIds = visits
        .filter((v) => !v.billed && v.client_id === schedule.client_id)
        .map((v) => v.id);
      const result = (await createFn({
        data: {
          clientId: schedule.client_id,
          visitIds,
          retainerAmount: Number(schedule.retainer_amount) || 0,
          retainerDescription: `${FREQUENCY_LABELS[schedule.frequency]} maintenance retainer`,
        },
      })) as { number: string };
      const next = advance(parseISO(schedule.next_run_date), schedule.frequency);
      await saveRecurring.mutateAsync({
        id: schedule.id,
        client_id: schedule.client_id,
        next_run_date: format(next, "yyyy-MM-dd"),
        last_run_at: new Date().toISOString(),
      });
      toast.success(`Invoice ${result.number} created from the schedule`);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not run the schedule");
    } finally {
      setBusy(null);
    }
  }

  return (
    <AppShell
      title="Recurring billing"
      description="Put regular clients on a repeating invoice cycle."
      actions={
        <Button
          onClick={() => {
            setEditing(null);
            setOpen(true);
          }}
          disabled={clients.length === 0}
        >
          <Plus className="size-4" /> New schedule
        </Button>
      }
    >
      {schedules.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
            <Repeat className="size-8 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">
              No recurring schedules yet. Add one to invoice a client automatically each cycle.
            </p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-2">
          {schedules.map((s) => {
            const due = s.next_run_date <= today;
            return (
              <Card key={s.id}>
                <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
                  <div>
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="font-semibold">{clientName(s.client_id)}</p>
                      <Badge variant="secondary">{FREQUENCY_LABELS[s.frequency]}</Badge>
                      {s.auto_send ? <Badge>Auto-send</Badge> : null}
                      {!s.active ? <Badge variant="outline">Paused</Badge> : null}
                    </div>
                    <p className="text-sm text-muted-foreground">
                      Next run {format(parseISO(s.next_run_date), "d MMM yyyy")}
                      {Number(s.retainer_amount) > 0
                        ? ` · retainer ${money(s.retainer_amount)}`
                        : " · bills logged visits"}
                    </p>
                  </div>
                  <div className="flex items-center gap-1">
                    <Button
                      size="sm"
                      variant={due ? "default" : "outline"}
                      disabled={busy === s.id}
                      onClick={() => runNow(s)}
                    >
                      <Zap className="size-4" /> Run now
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setEditing(s);
                        setOpen(true);
                      }}
                    >
                      Edit
                    </Button>
                    <Button
                      size="icon"
                      variant="ghost"
                      aria-label="Delete schedule"
                      onClick={async () => {
                        await deleteRecurring.mutateAsync(s.id);
                        toast.success("Schedule removed");
                      }}
                    >
                      <Trash2 className="size-4 text-destructive" />
                    </Button>
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <ScheduleDialog
        key={editing?.id ?? "new"}
        open={open}
        onOpenChange={setOpen}
        schedule={editing}
        onSave={async (values) => {
          await saveRecurring.mutateAsync(values);
          setOpen(false);
          toast.success("Schedule saved");
        }}
      />
    </AppShell>
  );
}

function ScheduleDialog({
  open,
  onOpenChange,
  schedule,
  onSave,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  schedule: RecurringSchedule | null;
  onSave: (values: Partial<RecurringSchedule> & { client_id: string }) => Promise<void>;
}) {
  const { data: clients = [] } = useClients();
  const [clientId, setClientId] = useState(schedule?.client_id ?? "");
  const [frequency, setFrequency] = useState<Frequency>(schedule?.frequency ?? "monthly");
  const [nextRun, setNextRun] = useState(
    schedule?.next_run_date ?? format(addDays(new Date(), 1), "yyyy-MM-dd"),
  );
  const [retainer, setRetainer] = useState(String(schedule?.retainer_amount ?? 0));
  const [autoSend, setAutoSend] = useState(schedule?.auto_send ?? false);
  const [active, setActive] = useState(schedule?.active ?? true);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{schedule ? "Edit schedule" : "New recurring schedule"}</DialogTitle>
          <DialogDescription>
            On each run the app builds an invoice from that client's unbilled visits plus any
            retainer.
          </DialogDescription>
        </DialogHeader>
        <form
          className="space-y-3"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!clientId) {
              toast.error("Pick a client");
              return;
            }
            await onSave({
              ...(schedule ? { id: schedule.id } : {}),
              client_id: clientId,
              frequency,
              next_run_date: nextRun,
              start_date: schedule?.start_date ?? nextRun,
              retainer_amount: Number(retainer) || 0,
              auto_send: autoSend,
              active,
            });
          }}
        >
          <div className="space-y-1.5">
            <Label>Client</Label>
            <Select value={clientId} onValueChange={setClientId}>
              <SelectTrigger>
                <SelectValue placeholder="Choose client" />
              </SelectTrigger>
              <SelectContent>
                {clients.map((c) => (
                  <SelectItem key={c.id} value={c.id}>
                    {c.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label>Frequency</Label>
              <Select value={frequency} onValueChange={(v) => setFrequency(v as Frequency)}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {Object.entries(FREQUENCY_LABELS).map(([value, label]) => (
                    <SelectItem key={value} value={value}>
                      {label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="nextrun">Next run</Label>
              <Input
                id="nextrun"
                type="date"
                value={nextRun}
                onChange={(e) => setNextRun(e.target.value)}
              />
            </div>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="retainer">Retainer amount (£)</Label>
            <Input
              id="retainer"
              type="number"
              step="0.01"
              min="0"
              value={retainer}
              onChange={(e) => setRetainer(e.target.value)}
            />
          </div>
          <label className="flex items-center gap-2 text-sm font-medium">
            <Checkbox checked={autoSend} onCheckedChange={(v) => setAutoSend(Boolean(v))} />
            Email the invoice as soon as it is generated
          </label>
          <label className="flex items-center gap-2 text-sm font-medium">
            <Checkbox checked={active} onCheckedChange={(v) => setActive(Boolean(v))} />
            Schedule active
          </label>
          <DialogFooter>
            <Button type="submit">Save schedule</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
