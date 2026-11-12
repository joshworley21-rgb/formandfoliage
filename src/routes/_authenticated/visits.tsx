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
import { VisitDialog } from "@/components/VisitDialog";
import { useClients, useDeleteVisit, useVisits } from "@/hooks/use-crm";
import { hoursLabel, money, type Visit } from "@/lib/crm";

export const Route = createFileRoute("/_authenticated/visits")({
  head: () => ({
    meta: [
      { title: "Visits — JW Garden Services CRM" },
      { name: "description", content: "Log hours and materials for every completed garden visit." },
      { property: "og:title", content: "Visits — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Log hours and materials for every completed garden visit.",
      },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <VisitsPage />
    </OwnerOnly>
  ),
});

function VisitsPage() {
  const { data: clients = [] } = useClients();
  const { data: visits = [], isLoading } = useVisits();

  const remove = useDeleteVisit();

  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Visit | null>(null);
  const [defaultMissed, setDefaultMissed] = useState(false);

  const totals = useMemo(() => {
    const unbilled = visits.filter((v) => !v.billed && !v.missed);
    return {
      unbilledCount: unbilled.length,
      unbilledValue: unbilled.reduce((sum, v) => sum + Number(v.amount), 0),
    };
  }, [visits]);

  function openNew(missed: boolean) {
    setEditing(null);
    setDefaultMissed(missed);
    setOpen(true);
  }

  function openEdit(visit: Visit) {
    setEditing(visit);
    setDefaultMissed(false);
    setOpen(true);
  }

  return (
    <AppShell
      title="Visits"
      description="Hours and materials logged for completed work."
      actions={
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => openNew(true)} disabled={clients.length === 0}>
            <MinusCircle className="size-4 mr-2" /> Log missed
          </Button>
          <Button onClick={() => openNew(false)} disabled={clients.length === 0}>
            <Plus className="size-4 mr-2" /> Log visit
          </Button>
        </div>
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
            <p className="text-xs uppercase tracking-wide text-muted-foreground">
              Ready to invoice
            </p>
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
                      {/* Missed comes first: it is the reason the row carries no
                          money, and "Unbilled" on a visit that will never be
                          billed reads as something still owing. */}
                      <Badge
                        variant={visit.missed ? "outline" : visit.billed ? "secondary" : "default"}
                      >
                        {visit.missed ? "Missed" : visit.billed ? "Invoiced" : "Unbilled"}
                      </Badge>
                    </div>
                    <p className="mt-0.5 text-sm text-muted-foreground">
                      {format(parseISO(visit.visit_date), "EEE d MMM yyyy")}
                      {visit.missed ? null : (
                        <>
                          {" · "}
                          {hoursLabel(visit.hours)}
                          {Number(visit.materials_cost) > 0
                            ? ` · materials ${money(visit.materials_cost)}${
                                visit.materials_note ? ` (${visit.materials_note})` : ""
                              }`
                            : ""}
                        </>
                      )}
                    </p>
                    {visit.description ? (
                      <p className="mt-1 line-clamp-2 text-sm text-foreground/80">
                        {visit.description}
                      </p>
                    ) : null}
                  </div>
                  <div className="flex shrink-0 items-center gap-1">
                    <span className="mr-1 font-semibold">
                      {visit.missed ? "—" : money(visit.amount)}
                    </span>
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Edit"
                      onClick={() => openEdit(visit)}
                    >
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

      <VisitDialog
        open={open}
        visit={editing}
        defaultMissed={defaultMissed}
        onOpenChange={setOpen}
      />
    </AppShell>
  );
}
