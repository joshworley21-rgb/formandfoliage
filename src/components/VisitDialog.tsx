// Logging a visit, and editing one already logged.
//
// This used to live inside the Visits screen, which meant the only way to add
// the compost you forgot was to leave the Schedule, find the right day in the
// visit log, and edit it there — for work you were looking straight at. It is
// a component now so the job you have just completed can open it directly.
//
// One rule it enforces that the old inline version did not: an invoiced visit
// is not a working document. Its money is locked, because the invoice keeps
// its own copy of every line and changing the visit afterwards would leave the
// two disagreeing with nothing to say so.
import { format } from "date-fns";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
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
import { Textarea } from "@/components/ui/textarea";
import { useClients, useJobs, useSaveVisit } from "@/hooks/use-crm";
import { money, type Visit } from "@/lib/crm";
import { findJobForVisit } from "@/lib/job-visit-link";
import { visitAmount, visitMoneyFields } from "@/lib/visit-fields";

function todayISO() {
  return format(new Date(), "yyyy-MM-dd");
}

type RateSource = {
  billing_mode?: string;
  hourly_rate?: number | string;
  fixed_price?: number | string;
};

/** Rates come straight from the client record. */
function rateFor(client: RateSource | null | undefined, mode: "hourly" | "day") {
  return String(Number((mode === "day" ? client?.fixed_price : client?.hourly_rate) ?? 0));
}

export function VisitDialog({
  open,
  visit,
  defaultClientId,
  defaultDate,
  onOpenChange,
  onSaved,
}: {
  open: boolean;
  /** The visit being edited, or null to log a new one. */
  visit: Visit | null;
  /** Pre-selected client for a new visit — the job it is being logged against. */
  defaultClientId?: string;
  /** yyyy-MM-dd for a new visit. Defaults to today. */
  defaultDate?: string;
  onOpenChange: (open: boolean) => void;
  onSaved?: (result: { missed: boolean; completedJob: boolean }) => void;
}) {
  const { data: clients = [] } = useClients();
  const save = useSaveVisit();

  // Jobs around today, to spot the one a visit finishes off. Bounded rather
  // than every job ever, and memoised so the query key does not change on
  // every render. A visit dated outside this window simply links nothing.
  const jobWindow = useMemo(() => {
    const now = new Date();
    return {
      from: new Date(now.getTime() - 90 * 86400000).toISOString(),
      to: new Date(now.getTime() + 30 * 86400000).toISOString(),
    };
  }, []);
  const { data: jobs = [] } = useJobs(jobWindow);

  const [clientId, setClientId] = useState("");
  const [date, setDate] = useState(todayISO());
  const [hours, setHours] = useState("2");
  const [materials, setMaterials] = useState("0");
  const [materialsNote, setMaterialsNote] = useState("");
  const [description, setDescription] = useState("");
  const [missed, setMissed] = useState(false);
  const [payMode, setPayMode] = useState<"hourly" | "day">("hourly");
  const [rate, setRate] = useState("0");
  const [days, setDays] = useState("1");

  const client = clients.find((c) => c.id === clientId);
  // Already on an invoice the customer has been sent. The description stays
  // editable — it is a note to yourself — but nothing that moves the total.
  const locked = Boolean(visit?.billed);

  function resetRate(source?: RateSource | null) {
    const mode: "hourly" | "day" = source?.billing_mode === "fixed" ? "day" : "hourly";
    setPayMode(mode);
    setRate(rateFor(source, mode));
    setDays("1");
  }

  // The dialog stays mounted between openings, so its fields are put back
  // deliberately — otherwise one visit's hours turn up on the next.
  useEffect(() => {
    if (!open) return;
    if (visit) {
      setClientId(visit.client_id);
      setDate(visit.visit_date);
      setHours(String(visit.hours));
      setMaterials(String(visit.materials_cost));
      setMaterialsNote(visit.materials_note ?? "");
      setDescription(visit.description ?? "");
      setMissed(visit.missed);
      resetRate(clients.find((c) => c.id === visit.client_id));
      return;
    }
    const first = defaultClientId ? clients.find((c) => c.id === defaultClientId) : clients[0];
    setClientId(first?.id ?? "");
    setDate(defaultDate ?? todayISO());
    setHours("2");
    setMaterials("0");
    setMaterialsNote("");
    setDescription("");
    setMissed(false);
    resetRate(first);
    // clients is deliberately out of the deps: it arrives once and re-running
    // this on a cache refresh would wipe whatever is half-typed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, visit, defaultClientId, defaultDate]);

  const moneyInput = { locked, missed, payMode, hours, materials, rate, days };
  const computedAmount = visitAmount(moneyInput);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!client) {
      toast.error("Pick a client first");
      return;
    }

    // A visit logged for a day this client was booked in is that job, done.
    // Only on a new visit: editing one months later should not quietly tick
    // something off the schedule.
    const jobDone = visit ? null : findJobForVisit(jobs, client.id, date);

    try {
      await save.mutateAsync({
        ...(visit ? { id: visit.id } : {}),
        client_id: client.id,
        visit_date: date,
        // Empty for an invoiced visit, so the update leaves the billed figures
        // exactly as the invoice has them.
        ...visitMoneyFields(moneyInput),
        ...(locked ? {} : { materials_note: materialsNote.trim() || null }),
        description: description || null,
        ...(jobDone ? { completesJobId: jobDone.id, jobOutcome: missed ? "missed" : "done" } : {}),
      });

      toast.success(
        visit
          ? "Visit updated"
          : jobDone
            ? `${missed ? "Missed visit recorded" : "Visit logged"} — ${client.name}'s job is marked ${missed ? "missed" : "complete"}`
            : missed
              ? "Missed visit recorded"
              : "Visit logged",
      );
      onOpenChange(false);
      onSaved?.({ missed, completedJob: Boolean(jobDone) });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not save visit");
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{visit ? "Edit visit" : "Log a visit"}</DialogTitle>
        </DialogHeader>
        <form className="space-y-3" onSubmit={submit}>
          {/*
            Said before the fields rather than after a rejected save: the
            question "why can I not change this?" should never need asking.
          */}
          {locked ? (
            <p className="rounded-lg border border-border bg-secondary px-3 py-2 text-xs">
              This visit is on an invoice, so its hours, materials and charge are fixed at what the
              invoice says. You can still change what the work was. To bill something extra, log it
              as its own visit.
            </p>
          ) : null}

          <div className="space-y-1.5">
            <Label>Client</Label>
            <Select
              value={clientId}
              disabled={locked}
              onValueChange={(v) => {
                setClientId(v);
                const c = clients.find((x) => x.id === v);
                if (c) resetRate(c);
              }}
            >
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
          <div className="space-y-1.5">
            <Label htmlFor="date">Date</Label>
            <Input
              id="date"
              type="date"
              value={date}
              disabled={locked}
              onChange={(e) => setDate(e.target.value)}
            />
          </div>

          {/* Above everything about money, because ticking it makes all of
              that irrelevant — and it is the first thing you know when you
              are standing at a locked gate. */}
          {locked ? null : (
            <label className="flex items-start gap-2.5 rounded-lg border border-border p-3 text-sm">
              <Checkbox
                checked={missed}
                onCheckedChange={(value) => setMissed(Boolean(value))}
                className="mt-0.5"
              />
              <span>
                <span className="font-medium">Missed visit</span>
                <span className="block text-xs text-muted-foreground">
                  Kept as a record, charged at nothing, and left out of invoices and everything
                  waiting to be billed.
                </span>
              </span>
            </label>
          )}

          <div className={missed ? "hidden" : "grid gap-3 sm:grid-cols-2"}>
            <div className="space-y-1.5">
              <Label htmlFor="hours">Hours</Label>
              <Input
                id="hours"
                type="number"
                step="0.25"
                min="0"
                value={hours}
                disabled={locked}
                onChange={(e) => setHours(e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="materials">Materials (£)</Label>
              <Input
                id="materials"
                type="number"
                step="0.01"
                min="0"
                value={materials}
                disabled={locked}
                onChange={(e) => setMaterials(e.target.value)}
              />
            </div>
          </div>
          {/*
            Only once there is a figure to explain. An empty box asking what
            the materials were, on a visit that had none, is a question with no
            answer — and this is a form filled in at the end of a long day.
          */}
          {!missed && Number(materials) > 0 ? (
            <div className="space-y-1.5">
              <Label htmlFor="matnote">What the materials were</Label>
              <Input
                id="matnote"
                value={materialsNote}
                disabled={locked}
                placeholder="2 bags compost, bark chippings"
                onChange={(e) => setMaterialsNote(e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                Goes on the invoice as its own line, so the customer can see what they are paying
                for.
              </p>
            </div>
          ) : null}
          <div className={missed || locked ? "hidden" : "grid gap-3 sm:grid-cols-3"}>
            <div className="space-y-1.5">
              <Label>Pay basis</Label>
              <Select
                value={payMode}
                onValueChange={(v) => {
                  const mode = v as "hourly" | "day";
                  setPayMode(mode);
                  setRate(rateFor(client, mode));
                }}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="hourly">Hourly rate</SelectItem>
                  <SelectItem value="day">Day rate</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="vrate">
                {payMode === "day" ? "Day rate (£)" : "Hourly rate (£)"}
              </Label>
              <Input
                id="vrate"
                type="number"
                step="0.01"
                min="0"
                value={rate}
                onChange={(e) => setRate(e.target.value)}
              />
              <p className="text-xs text-muted-foreground">From the client record</p>
            </div>

            {payMode === "day" ? (
              <div className="space-y-1.5">
                <Label htmlFor="vdays">Days worked</Label>
                <Input
                  id="vdays"
                  type="number"
                  step="0.5"
                  min="0"
                  value={days}
                  onChange={(e) => setDays(e.target.value)}
                />
              </div>
            ) : null}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="work">{missed ? "What happened" : "Work done"}</Label>
            <Textarea
              id="work"
              rows={3}
              placeholder={
                missed
                  ? "Gate locked, ground too wet, cancelled on the day…"
                  : "Mowed lawns, hedge trim, cleared beds…"
              }
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <p className="rounded-lg bg-secondary px-3 py-2 text-sm">
            {locked ? (
              <>
                Invoiced at:{" "}
                <span className="font-semibold">{money(Number(visit?.amount ?? 0))}</span>
              </>
            ) : missed ? (
              <>
                Charge: <span className="font-semibold">nothing</span>{" "}
                <span className="text-muted-foreground">(this visit did not happen)</span>
              </>
            ) : (
              <>
                Charge: <span className="font-semibold">{money(computedAmount)}</span>{" "}
                <span className="text-muted-foreground">
                  ({payMode === "day" ? "day rate" : "hourly rate"} + materials)
                </span>
              </>
            )}
          </p>

          <DialogFooter>
            <Button type="submit" disabled={save.isPending}>
              {missed ? "Save missed visit" : "Save visit"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
