import { createFileRoute } from "@tanstack/react-router";
import { format, parseISO } from "date-fns";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
import { OwnerOnly } from "@/components/OwnerOnly";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
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
import { useClients, useDeleteVisit, useSaveVisit, useVisits } from "@/hooks/use-crm";
import { hoursLabel, money, priceVisit, type Visit } from "@/lib/crm";

export const Route = createFileRoute("/_authenticated/visits")({
  head: () => ({
    meta: [
      { title: "Visits — JW Garden Services CRM" },
      { name: "description", content: "Log hours and materials for every completed garden visit." },
      { property: "og:title", content: "Visits — JW Garden Services CRM" },
      { property: "og:description", content: "Log hours and materials for every completed garden visit." },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <VisitsPage />
    </OwnerOnly>
  ),
});

function todayISO() {
  return format(new Date(), "yyyy-MM-dd");
}

function VisitsPage() {
  const { data: clients = [] } = useClients();
  const { data: visits = [], isLoading } = useVisits();
  const save = useSaveVisit();
  const remove = useDeleteVisit();

  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Visit | null>(null);
  const [clientId, setClientId] = useState("");
  const [date, setDate] = useState(todayISO());
  const [hours, setHours] = useState("2");
  const [materials, setMaterials] = useState("0");
  const [description, setDescription] = useState("");
  const [payMode, setPayMode] = useState<"hourly" | "day">("hourly");
  const [rate, setRate] = useState("0");
  const [days, setDays] = useState("1");

  const client = clients.find((c) => c.id === clientId);
  const materialsNum = Number(materials) || 0;
  const computedAmount =
    payMode === "day"
      ? (Number(rate) || 0) * (Number(days) || 0) + materialsNum
      : (Number(rate) || 0) * (Number(hours) || 0) + materialsNum;
  const preview = computedAmount;


  const totals = useMemo(() => {
    const unbilled = visits.filter((v) => !v.billed);
    return {
      unbilledCount: unbilled.length,
      unbilledValue: unbilled.reduce((sum, v) => sum + Number(v.amount), 0),
    };
  }, [visits]);

  /** Rates come straight from the client record. */
  function rateFor(
    c: { hourly_rate?: number | string; fixed_price?: number | string } | null | undefined,
    mode: "hourly" | "day",
  ) {
    return String(Number((mode === "day" ? c?.fixed_price : c?.hourly_rate) ?? 0));
  }

  function resetRate(c?: { billing_mode?: string; hourly_rate?: number | string; fixed_price?: number | string } | null) {
    const mode: "hourly" | "day" = c?.billing_mode === "fixed" ? "day" : "hourly";
    setPayMode(mode);
    setRate(rateFor(c, mode));
    setDays("1");
  }

  function openNew() {
    setEditing(null);
    const first = clients[0];
    setClientId(first?.id ?? "");
    setDate(todayISO());
    setHours("2");
    setMaterials("0");
    setDescription("");
    resetRate(first);
    setOpen(true);
  }

  function openEdit(visit: Visit) {
    setEditing(visit);
    setClientId(visit.client_id);
    setDate(visit.visit_date);
    setHours(String(visit.hours));
    setMaterials(String(visit.materials_cost));
    setDescription(visit.description ?? "");
    resetRate(clients.find((c) => c.id === visit.client_id));
    setOpen(true);
  }


  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!client) {
      toast.error("Pick a client first");
      return;
    }

    try {
      await save.mutateAsync({
        ...(editing ? { id: editing.id } : {}),
        client_id: client.id,
        visit_date: date,
        hours: Number(hours) || 0,
        materials_cost: Number(materials) || 0,
        description: description || null,
        amount: computedAmount,
      });

      toast.success(editing ? "Visit updated" : "Visit logged");
      setOpen(false);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not save visit");
    }
  }

  return (
    <AppShell
      title="Visits"
      description="Hours and materials logged for completed work."
      actions={
        <Button onClick={openNew} disabled={clients.length === 0}>
          <Plus className="size-4" /> Log visit
        </Button>
      }
    >
      <div className="mb-5 grid gap-3 sm:grid-cols-2">
        <Card>
          <CardContent className="py-4">
            <p className="text-xs uppercase tracking-wide text-muted-foreground">Unbilled visits</p>
            <p className="font-display text-2xl font-bold">{totals.unbilledCount}</p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="py-4">
            <p className="text-xs uppercase tracking-wide text-muted-foreground">Ready to invoice</p>
            <p className="font-display text-2xl font-bold text-primary">
              {money(totals.unbilledValue)}
            </p>
          </CardContent>
        </Card>
      </div>

      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading visits…</p>
      ) : visits.length === 0 ? (
        <Card>
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            No visits logged yet.
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-2">
          {visits.map((visit) => {
            const name = clients.find((c) => c.id === visit.client_id)?.name ?? "Client";
            return (
              <Card key={visit.id}>
                <CardContent className="flex items-center justify-between gap-3 py-4">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="font-semibold text-foreground">{name}</p>
                      <Badge variant={visit.billed ? "secondary" : "default"}>
                        {visit.billed ? "Invoiced" : "Unbilled"}
                      </Badge>
                    </div>
                    <p className="mt-0.5 text-sm text-muted-foreground">
                      {format(parseISO(visit.visit_date), "EEE d MMM yyyy")} · {hoursLabel(visit.hours)}
                      {Number(visit.materials_cost) > 0
                        ? ` · materials ${money(visit.materials_cost)}`
                        : ""}
                    </p>
                    {visit.description ? (
                      <p className="mt-1 line-clamp-2 text-sm text-foreground/80">{visit.description}</p>
                    ) : null}
                  </div>
                  <div className="flex shrink-0 items-center gap-1">
                    <span className="mr-1 font-semibold">{money(visit.amount)}</span>
                    <Button variant="ghost" size="icon" aria-label="Edit" onClick={() => openEdit(visit)}>
                      <Pencil className="size-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Delete"
                      disabled={visit.billed}
                      onClick={async () => {
                        await remove.mutateAsync(visit.id);
                        toast.success("Visit deleted");
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

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editing ? "Edit visit" : "Log a visit"}</DialogTitle>
          </DialogHeader>
          <form className="space-y-3" onSubmit={submit}>
            <div className="space-y-1.5">
              <Label>Client</Label>
              <Select
                value={clientId}
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
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="space-y-1.5">
                <Label htmlFor="date">Date</Label>
                <Input id="date" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="hours">Hours</Label>
                <Input
                  id="hours"
                  type="number"
                  step="0.25"
                  min="0"
                  value={hours}
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
                  onChange={(e) => setMaterials(e.target.value)}
                />
              </div>
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="space-y-1.5">
                <Label>Pay basis</Label>
                <Select
                  value={payMode}
                  onValueChange={(v) => setPayMode(v as "client" | "hourly" | "day")}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="client">Client default</SelectItem>
                    <SelectItem value="hourly">Hourly rate</SelectItem>
                    <SelectItem value="day">Day rate</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              {payMode === "client" ? null : (
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
                </div>
              )}
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
              <Label htmlFor="work">Work done</Label>
              <Textarea
                id="work"
                rows={3}
                placeholder="Mowed lawns, hedge trim, cleared beds…"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
            <p className="rounded-lg bg-secondary px-3 py-2 text-sm">
              Charge: <span className="font-semibold">{money(preview)}</span>{" "}
              <span className="text-muted-foreground">
                (
                {payMode === "day"
                  ? "day rate"
                  : payMode === "hourly"
                    ? "hourly rate"
                    : client?.billing_mode === "fixed"
                      ? "fixed price"
                      : "hourly"}{" "}
                + materials)
              </span>
            </p>

            <DialogFooter>
              <Button type="submit" disabled={save.isPending}>
                Save visit
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </AppShell>
  );
}
