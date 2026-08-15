import { createFileRoute } from "@tanstack/react-router";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useServerFn } from "@tanstack/react-start";
import { format, parseISO } from "date-fns";
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock,
  Download,
  Eye,
  FilePlus2,
  History,
  Mail,
  MinusCircle,
  Plus,
} from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
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
import { Textarea } from "@/components/ui/textarea";
import {
  useClients,
  useInvoiceEmailEvents,
  useInvoices,
  useSettings,
  useUpdateInvoice,
  useVisits,
} from "@/hooks/use-crm";
import { supabase } from "@/integrations/supabase/client";
import { money, type Invoice, type InvoiceEmailEvent } from "@/lib/crm";
import { buildInvoicePdf, pdfToBase64 } from "@/lib/invoice-pdf";
import { createInvoice, sendInvoiceEmail } from "@/lib/invoicing.functions";

export const Route = createFileRoute("/_authenticated/invoices")({
  head: () => ({
    meta: [
      { title: "Invoices — JW Garden Services CRM" },
      { name: "description", content: "Turn logged visits into branded PDF invoices and email them in one click." },
      { property: "og:title", content: "Invoices — JW Garden Services CRM" },
      { property: "og:description", content: "Turn logged visits into branded PDF invoices and email them in one click." },
    ],
  }),
  component: InvoicesPage,
});

const STATUS_VARIANT: Record<string, "default" | "secondary" | "outline"> = {
  draft: "outline",
  sent: "default",
  paid: "secondary",
};

function InvoicesPage() {
  const { data: clients = [] } = useClients();
  const { data: invoices = [] } = useInvoices();
  const { data: visits = [] } = useVisits();
  const { data: settings } = useSettings();
  const updateInvoice = useUpdateInvoice();
  const [creating, setCreating] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [openHistory, setOpenHistory] = useState<string | null>(null);
  const [preview, setPreview] = useState<
    { invoice: Invoice; url: string; intent: "download" | "send" } | null
  >(null);
  const queryClient = useQueryClient();

  const sendFn = useServerFn(sendInvoiceEmail);
  const createFn = useServerFn(createInvoice);

  const clientName = (id: string) => clients.find((c) => c.id === id)?.name ?? "Client";

  async function buildPdf(invoice: Invoice) {
    const client = clients.find((c) => c.id === invoice.client_id);
    if (!client) throw new Error("Client missing");
    const { data: items, error } = await supabase
      .from("invoice_items")
      .select("*")
      .eq("invoice_id", invoice.id)
      .order("item_date");
    if (error) throw error;
    return await buildInvoicePdf({ invoice, items: items ?? [], client, settings: settings ?? null });
  }

  async function download(invoice: Invoice) {
    try {
      setBusyId(invoice.id);
      const doc = await buildPdf(invoice);
      doc.save(`${invoice.number}.pdf`);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not build the PDF");
    } finally {
      setBusyId(null);
    }
  }

  async function openPreview(invoice: Invoice, intent: "download" | "send") {
    try {
      setBusyId(invoice.id);
      const doc = await buildPdf(invoice);
      const url = URL.createObjectURL(doc.output("blob"));
      setPreview((current) => {
        if (current?.url) URL.revokeObjectURL(current.url);
        return { invoice, url, intent };
      });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not build the preview");
    } finally {
      setBusyId(null);
    }
  }

  function closePreview() {
    setPreview((current) => {
      if (current?.url) URL.revokeObjectURL(current.url);
      return null;
    });
  }


  async function sendOneClick(invoice: Invoice) {
    try {
      setBusyId(invoice.id);
      const doc = await buildPdf(invoice);
      const result = (await sendFn({
        data: { invoiceId: invoice.id, pdfBase64: pdfToBase64(doc) },
      })) as { sent: boolean; to?: string; reason?: string };
      if (result.sent) toast.success(`Invoice emailed to ${result.to}`);
      else toast.warning(result.reason ?? "Email is not set up yet");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not send the invoice");
    } finally {
      setBusyId(null);
      setOpenHistory(invoice.id);
      await queryClient.invalidateQueries({ queryKey: ["invoice_email_events", invoice.id] });
      await queryClient.invalidateQueries({ queryKey: ["invoices"] });
    }
  }

  return (
    <AppShell
      title="Invoices"
      description="Build an invoice from unbilled visits, then send it with one click."
      actions={
        <Button onClick={() => setCreating(true)} disabled={clients.length === 0}>
          <Plus className="size-4" /> New invoice
        </Button>
      }
    >
      {invoices.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
            <FilePlus2 className="size-8 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">
              No invoices yet. Log some visits, then create your first invoice.
            </p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-2">
          {invoices.map((invoice) => (
            <Card key={invoice.id}>
              <CardContent className="space-y-3 py-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="font-semibold">{invoice.number}</p>
                    <Badge variant={STATUS_VARIANT[invoice.status] ?? "outline"}>
                      {invoice.status}
                    </Badge>
                  </div>
                  <p className="text-sm text-muted-foreground">
                    {clientName(invoice.client_id)} ·{" "}
                    {format(parseISO(invoice.issue_date), "d MMM yyyy")}
                    {invoice.period_start && invoice.period_end
                      ? ` · ${invoice.period_start} – ${invoice.period_end}`
                      : ""}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <p className="font-display text-lg font-semibold">{money(invoice.total)}</p>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busyId === invoice.id}
                    onClick={() => openPreview(invoice, "download")}
                  >
                    <Eye className="size-4" /> Preview
                  </Button>
                  <Button
                    size="sm"
                    disabled={busyId === invoice.id}
                    onClick={() => sendOneClick(invoice)}
                  >
                    <Mail className="size-4" /> {invoice.status === "draft" ? "Send" : "Resend"}
                  </Button>

                  {invoice.status !== "paid" ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={async () => {
                        await updateInvoice.mutateAsync({
                          id: invoice.id,
                          patch: { status: "paid", paid_at: new Date().toISOString() },
                        });
                        toast.success("Marked as paid");
                      }}
                    >
                      Mark paid
                    </Button>
                  ) : null}
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label="Email history"
                    onClick={() =>
                      setOpenHistory((current) => (current === invoice.id ? null : invoice.id))
                    }
                  >
                    <History className="size-4" />
                    {openHistory === invoice.id ? (
                      <ChevronUp className="size-4" />
                    ) : (
                      <ChevronDown className="size-4" />
                    )}
                  </Button>
                </div>
              </div>
              {openHistory === invoice.id ? <EmailHistory invoiceId={invoice.id} /> : null}
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      <NewInvoiceDialog
        open={creating}
        onOpenChange={setCreating}
        unbilledVisits={visits.filter((v) => !v.billed)}
        create={async (input) => {
          const result = (await createFn({ data: input })) as { number: string };
          toast.success(`Invoice ${result.number} created`);
          setCreating(false);
        }}
      />

      <Dialog open={preview !== null} onOpenChange={(open) => (open ? null : closePreview())}>
        <DialogContent className="max-w-3xl">
          <DialogHeader>
            <DialogTitle>Preview {preview?.invoice.number}</DialogTitle>
            <DialogDescription>
              Check the layout before you {preview?.intent === "send" ? "send" : "download"} it.
            </DialogDescription>
          </DialogHeader>
          {preview ? (
            <iframe
              title={`Invoice ${preview.invoice.number} preview`}
              src={preview.url}
              className="h-[65vh] w-full rounded-lg border bg-muted"
            />
          ) : null}
          <DialogFooter className="gap-2">
            <Button variant="outline" onClick={closePreview}>
              Close
            </Button>
            <Button
              variant="outline"
              onClick={async () => {
                if (!preview) return;
                const invoice = preview.invoice;
                closePreview();
                await download(invoice);
              }}
            >
              <Download className="size-4" /> Download PDF
            </Button>
            <Button
              onClick={async () => {
                if (!preview) return;
                const invoice = preview.invoice;
                closePreview();
                await sendOneClick(invoice);
              }}
            >
              <Mail className="size-4" /> {preview?.invoice.status === "draft" ? "Send" : "Resend"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

    </AppShell>
  );
}

const EVENT_META: Record<
  string,
  { label: string; icon: typeof CheckCircle2; className: string }
> = {
  sent: { label: "Sent", icon: CheckCircle2, className: "text-primary" },
  failed: { label: "Failed", icon: AlertCircle, className: "text-destructive" },
  skipped: { label: "Not sent", icon: MinusCircle, className: "text-muted-foreground" },
};

function EmailHistory({ invoiceId }: { invoiceId: string }) {
  const { data: events = [], isLoading } = useInvoiceEmailEvents(invoiceId);

  if (isLoading) {
    return <p className="border-t pt-3 text-sm text-muted-foreground">Loading email history…</p>;
  }

  if (events.length === 0) {
    return (
      <p className="border-t pt-3 text-sm text-muted-foreground">
        No email attempts recorded for this invoice yet.
      </p>
    );
  }

  return (
    <div className="space-y-2 border-t pt-3">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        Email history
      </p>
      <ul className="space-y-2">
        {events.map((event: InvoiceEmailEvent) => {
          const meta = EVENT_META[event.status] ?? EVENT_META["skipped"]!;
          const Icon = meta.icon;
          return (
            <li key={event.id} className="flex items-start gap-2 text-sm">
              <Icon className={`mt-0.5 size-4 shrink-0 ${meta.className}`} />
              <div className="min-w-0">
                <p className="font-medium">
                  {meta.label}
                  {event.recipient ? ` · ${event.recipient}` : ""}
                </p>
                <p className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Clock className="size-3" />
                  {format(parseISO(event.created_at), "d MMM yyyy, HH:mm")}
                </p>
                {event.detail ? (
                  <p className="break-words text-xs text-muted-foreground">{event.detail}</p>
                ) : null}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function NewInvoiceDialog({
  open,
  onOpenChange,
  unbilledVisits,
  create,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  unbilledVisits: Array<{
    id: string;
    client_id: string;
    visit_date: string;
    description: string | null;
    amount: number;
  }>;
  create: (input: {
    clientId: string;
    visitIds: string[];
    retainerAmount: number;
    retainerDescription: string | null;
    notes: string | null;
    dueDate: string | null;
  }) => Promise<void>;
}) {
  const { data: clients = [] } = useClients();
  const [clientId, setClientId] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [retainer, setRetainer] = useState("0");
  const [retainerDescription, setRetainerDescription] = useState("");
  const [notes, setNotes] = useState("");
  const [dueDate, setDueDate] = useState(() =>
    new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10),
  );


  const clientVisits = useMemo(
    () => unbilledVisits.filter((v) => v.client_id === clientId),
    [unbilledVisits, clientId],
  );

  const total =
    clientVisits
      .filter((v) => selected.includes(v.id))
      .reduce((s, v) => s + Number(v.amount), 0) + (Number(retainer) || 0);

  const mutation = useMutation({
    mutationFn: () =>
      create({
        clientId,
        visitIds: selected,
        retainerAmount: Number(retainer) || 0,
        retainerDescription: retainerDescription || null,
        notes: notes || null,
        dueDate: dueDate || null,

      }),
    onError: (error) =>
      toast.error(error instanceof Error ? error.message : "Could not create the invoice"),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>New invoice</DialogTitle>
          <DialogDescription>Pick a client, tick the visits to bill.</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label>Client</Label>
            <Select
              value={clientId}
              onValueChange={(v) => {
                setClientId(v);
                setSelected(unbilledVisits.filter((x) => x.client_id === v).map((x) => x.id));
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

          {clientId ? (
            <div className="space-y-1.5">
              <Label>Unbilled visits</Label>
              {clientVisits.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  No unbilled visits — you can still invoice a retainer amount below.
                </p>
              ) : (
                <div className="max-h-56 space-y-1 overflow-y-auto rounded-lg border border-border p-2">
                  {clientVisits.map((v) => (
                    <label
                      key={v.id}
                      className="flex items-center justify-between gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent/60"
                    >
                      <span className="flex items-center gap-2">
                        <Checkbox
                          checked={selected.includes(v.id)}
                          onCheckedChange={(checked) =>
                            setSelected((prev) =>
                              checked ? [...prev, v.id] : prev.filter((id) => id !== v.id),
                            )
                          }
                        />
                        <span>
                          {format(parseISO(v.visit_date), "d MMM")} ·{" "}
                          {v.description || "Garden maintenance"}
                        </span>
                      </span>
                      <span className="font-medium">{money(v.amount)}</span>
                    </label>
                  ))}
                </div>
              )}
            </div>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="ret">Retainer / extra (£)</Label>
              <Input
                id="ret"
                type="number"
                step="0.01"
                min="0"
                value={retainer}
                onChange={(e) => setRetainer(e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="retdesc">Retainer description</Label>
              <Input
                id="retdesc"
                value={retainerDescription}
                onChange={(e) => setRetainerDescription(e.target.value)}
                placeholder="Monthly maintenance retainer"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="duedate">Due date</Label>
            <Input
              id="duedate"
              type="date"
              value={dueDate}
              onChange={(e) => setDueDate(e.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              Shown on the invoice PDF. Leave blank for “on receipt”.
            </p>
          </div>


          <div className="space-y-1.5">
            <Label htmlFor="inotes">Invoice notes</Label>
            <Textarea id="inotes" rows={2} value={notes} onChange={(e) => setNotes(e.target.value)} />
          </div>

          <p className="text-sm text-muted-foreground">
            Invoice total before VAT: <strong className="text-foreground">{money(total)}</strong>
          </p>
        </div>
        <DialogFooter>
          <Button disabled={!clientId || mutation.isPending} onClick={() => mutation.mutate()}>
            Create invoice
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
