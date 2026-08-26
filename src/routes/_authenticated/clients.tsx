import { createFileRoute } from "@tanstack/react-router";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { AddressSearch } from "@/components/AddressSearch";
import { AppShell } from "@/components/AppShell";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
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
import { useClients, useDeleteClient, useSaveClient } from "@/hooks/use-crm";
import { useIsStaff } from "@/hooks/use-workspace";
import { money, type BillingMode, type Client } from "@/lib/crm";

export const Route = createFileRoute("/_authenticated/clients")({
  head: () => ({
    meta: [
      { title: "Clients — JW Garden Services CRM" },
      {
        name: "description",
        content: "Manage garden maintenance clients, rates and contact details.",
      },
      { property: "og:title", content: "Clients — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Manage garden maintenance clients, rates and contact details.",
      },
    ],
  }),
  component: ClientsPage,
});

const EMPTY = {
  name: "",
  address: "",
  phone: "",
  email: "",
  notes: "",
  billing_mode: "hourly" as BillingMode,
  hourly_rate: "0",
  fixed_price: "0",
};

function ClientsPage() {
  const isStaff = useIsStaff();
  const { data: clients = [], isLoading } = useClients();
  const save = useSaveClient();
  const remove = useDeleteClient();
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Client | null>(null);
  const [form, setForm] = useState(EMPTY);

  function openNew() {
    setEditing(null);
    setForm(EMPTY);
    setOpen(true);
  }

  function openEdit(client: Client) {
    setEditing(client);
    setForm({
      name: client.name,
      address: client.address ?? "",
      phone: client.phone ?? "",
      email: client.email ?? "",
      notes: client.notes ?? "",
      billing_mode: client.billing_mode,
      hourly_rate: String(client.hourly_rate ?? 0),
      fixed_price: String(client.fixed_price ?? 0),
    });
    setOpen(true);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    try {
      await save.mutateAsync({
        ...(editing ? { id: editing.id } : {}),
        name: form.name.trim(),
        address: form.address || null,
        phone: form.phone || null,
        email: form.email || null,
        notes: form.notes || null,
        billing_mode: form.billing_mode,
        hourly_rate: Number(form.hourly_rate) || 0,
        fixed_price: Number(form.fixed_price) || 0,
      });
      toast.success(editing ? "Client updated" : "Client added");
      setOpen(false);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not save client");
    }
  }

  return (
    <AppShell
      title="Clients"
      description={
        isStaff
          ? "Gardens on your round, with access notes."
          : "Every garden you look after, with their rates."
      }
      actions={
        isStaff ? null : (
          <Dialog open={open} onOpenChange={setOpen}>
            <DialogTrigger asChild>
              <Button onClick={openNew}>
                <Plus className="size-4" /> New client
              </Button>
            </DialogTrigger>
            <DialogContent className="max-h-[90vh] overflow-y-auto">
              <DialogHeader>
                <DialogTitle>{editing ? "Edit client" : "New client"}</DialogTitle>
              </DialogHeader>
              <form className="space-y-3" onSubmit={submit}>
                <div className="space-y-1.5">
                  <Label htmlFor="name">Name</Label>
                  <Input
                    id="name"
                    required
                    value={form.name}
                    onChange={(e) => setForm({ ...form, name: e.target.value })}
                  />
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label htmlFor="phone">Phone</Label>
                    <Input
                      id="phone"
                      value={form.phone}
                      onChange={(e) => setForm({ ...form, phone: e.target.value })}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="email">Email (for invoices)</Label>
                    <Input
                      id="email"
                      type="email"
                      value={form.email}
                      onChange={(e) => setForm({ ...form, email: e.target.value })}
                    />
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="address">Address</Label>
                  {/*
                  Search fills the field; the field stays editable underneath.
                  A garden the lookup has never heard of, or no signal at all,
                  must never be a reason you cannot add a client.
                */}
                  <AddressSearch
                    // Functional update: a tap resolves over the network, and the
                    // name may have been typed since, so the form must not be
                    // rebuilt from a snapshot taken before the lookup started.
                    onPick={(address) => setForm((current) => ({ ...current, address }))}
                  />
                  <Textarea
                    id="address"
                    // Five lines: a full UK address is house, street, village,
                    // town, county, postcode, and clipping the postcode is the
                    // one line worth seeing.
                    rows={5}
                    value={form.address}
                    onChange={(e) => setForm({ ...form, address: e.target.value })}
                  />
                </div>
                <div className="grid gap-3 sm:grid-cols-3">
                  <div className="space-y-1.5">
                    <Label>Billing</Label>
                    <Select
                      value={form.billing_mode}
                      onValueChange={(v) => setForm({ ...form, billing_mode: v as BillingMode })}
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="hourly">Hourly</SelectItem>
                        <SelectItem value="fixed">Fixed per visit</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="rate">Hourly rate (£)</Label>
                    <Input
                      id="rate"
                      type="number"
                      step="0.01"
                      min="0"
                      value={form.hourly_rate}
                      onChange={(e) => setForm({ ...form, hourly_rate: e.target.value })}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="fixed">Fixed price (£)</Label>
                    <Input
                      id="fixed"
                      type="number"
                      step="0.01"
                      min="0"
                      value={form.fixed_price}
                      onChange={(e) => setForm({ ...form, fixed_price: e.target.value })}
                    />
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="notes">Notes</Label>
                  <Textarea
                    id="notes"
                    rows={2}
                    placeholder="Gate code, dog, bin day…"
                    value={form.notes}
                    onChange={(e) => setForm({ ...form, notes: e.target.value })}
                  />
                </div>
                <DialogFooter>
                  <Button type="submit" disabled={save.isPending}>
                    Save client
                  </Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
        )
      }
    >
      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading clients…</p>
      ) : clients.length === 0 ? (
        <Card>
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            No clients yet. Add your first one to start scheduling jobs.
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {clients.map((client) => (
            /*
              min-w-0 is load-bearing. A grid item defaults to min-width:auto,
              and "truncate" sets white-space:nowrap, which makes the address
              line's min-content width the whole address — so the track grew to
              fit it and the card ran off the side of the phone.
            */
            <Card key={client.id} className="min-w-0">
              <CardContent className="flex items-start justify-between gap-3 py-5">
                <div className="min-w-0">
                  <p className="truncate font-semibold text-foreground">{client.name}</p>
                  {client.address ? (
                    // Wrapped, not truncated: one line cuts off the postcode,
                    // which is the half of an address worth having.
                    <p className="mt-0.5 line-clamp-2 break-words text-sm text-muted-foreground">
                      {client.address}
                    </p>
                  ) : null}
                  {client.notes ? (
                    <p className="mt-1 line-clamp-2 text-sm text-foreground/80">{client.notes}</p>
                  ) : null}
                  {isStaff ? null : (
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <Badge variant="secondary">
                        {client.billing_mode === "fixed"
                          ? `${money(client.fixed_price)} / visit`
                          : `${money(client.hourly_rate)} / hour`}
                      </Badge>
                      {client.email ? (
                        <span className="min-w-0 truncate text-xs text-muted-foreground">
                          {client.email}
                        </span>
                      ) : (
                        <span className="text-xs text-warning-foreground">
                          No email for invoices
                        </span>
                      )}
                    </div>
                  )}
                </div>
                {isStaff ? null : (
                  <div className="flex shrink-0 gap-1">
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => openEdit(client)}
                      aria-label="Edit"
                    >
                      <Pencil className="size-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Delete"
                      onClick={async () => {
                        if (!confirm(`Delete ${client.name} and all their records?`)) return;
                        await remove.mutateAsync(client.id);
                        toast.success("Client deleted");
                      }}
                    >
                      <Trash2 className="size-4 text-destructive" />
                    </Button>
                  </div>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </AppShell>
  );
}
