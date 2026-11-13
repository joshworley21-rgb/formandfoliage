import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { addDays, format, parseISO } from "date-fns";
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
  MailCheck,
  MessageSquare,
  MinusCircle,
  MousePointerClick,
  Pencil,
  Plus,
  Trash2,
} from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
import { DraftInvoiceDialog } from "@/components/DraftInvoiceDialog";
import { InvoiceReminders } from "@/components/InvoiceReminders";
import { PaymentsToReview } from "@/components/PaymentsToReview";
import { SearchField } from "@/components/SearchField";
import { OwnerOnly } from "@/components/OwnerOnly";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
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
  useAllInvoiceEmailEvents,
  useClients,
  useDeleteInvoice,
  useInvoiceEmailEvents,
  useInvoices,
  useSettings,
  useUpdateInvoice,
  useVisits,
} from "@/hooks/use-crm";
import { supabase } from "@/integrations/supabase/client";
import { savePdf } from "@/lib/save-pdf";
import {
  money,
  type Client,
  type Invoice,
  type InvoiceEmailEvent,
  type InvoiceItem,
} from "@/lib/crm";
import { InvoicePreviewDoc } from "@/components/InvoicePreviewDoc";
import { SendInvoiceDialog, type SendInvoiceDraft } from "@/components/SendInvoiceDialog";
import { buildInvoicePdf, pdfToBase64 } from "@/lib/invoice-pdf";
import { createInvoice, sendInvoiceEmail } from "@/lib/invoicing";
import { filterBy } from "@/lib/search";

export const Route = createFileRoute("/_authenticated/invoices")({
  // "Create invoice" on a reminder names the client it is nagging about, so
  // the dialog can open on them instead of asking again.
  validateSearch: (search: Record<string, unknown>): { client?: string | undefined } => {
    const client = search["client"];
    return typeof client === "string" && client ? { client } : {};
  },
  head: () => ({
    meta: [
      { title: "Invoices — JW Garden Services CRM" },
      {
        name: "description",
        content: "Turn logged visits into branded PDF invoices and email them in one click.",
      },
      { property: "og:title", content: "Invoices — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Turn logged visits into branded PDF invoices and email them in one click.",
      },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <InvoicesPage />
    </OwnerOnly>
  ),
});

/**
 * Status, said twice on purpose: once as the card's edge stripe, which is
 * readable before any word on it, and once as a chip for when you need the
 * exact word. Amber appears here and nowhere else in the app, so on this page
 * it can only ever mean money that is late.
 */
const STATUS_LOOK: Record<string, { stripe: string; chip: string; label: string }> = {
  draft: {
    stripe: "var(--color-border)",
    chip: "border-border text-muted-foreground",
    label: "Draft",
  },
  sent: {
    stripe: "var(--color-lime)",
    chip: "border-border text-muted-foreground",
    label: "Sent",
  },
  paid: {
    stripe: "var(--color-primary)",
    chip: "border-primary/40 text-primary",
    label: "Paid",
  },
};

/** Sent, issued more than 30 days ago, and still not paid. */
function daysOverdue(invoice: Invoice): number {
  if (invoice.status === "paid") return 0;
  const due = invoice.due_date
    ? parseISO(invoice.due_date)
    : addDays(parseISO(invoice.issue_date), 30);
  const days = Math.floor((Date.now() - due.getTime()) / 86_400_000);
  return days > 0 ? days : 0;
}

/**
 * One invoice. The figure is the point of the card, so it sits alone on the
 * right in the tabular face; everything else is subordinate to it.
 */
function InvoiceCard({
  invoice,
  clientName,
  children,
}: {
  invoice: Invoice;
  clientName: string;
  children: ReactNode;
}) {
  const look = STATUS_LOOK[invoice.status] ?? STATUS_LOOK["draft"]!;
  const late = daysOverdue(invoice);

  return (
    <Card
      className="stripe gap-0 overflow-hidden py-0"
      style={{ ["--stripe" as string]: late > 0 ? "var(--color-warning)" : look.stripe }}
    >
      <CardContent className="space-y-3 py-4 pl-4 pr-3">
        <div className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-3">
          <div className="min-w-0">
            <p className="figure font-semibold">{invoice.number}</p>
            <p className="figure text-xs text-muted-foreground">
              {clientName} · {format(parseISO(invoice.issue_date), "d MMM yyyy")}
              {invoice.period_start && invoice.period_end
                ? ` · ${invoice.period_start} – ${invoice.period_end}`
                : ""}
            </p>
          </div>
          <p className="figure shrink-0 text-lg font-semibold leading-none">
            {money(invoice.total)}
          </p>
        </div>

        <div>
          {late > 0 ? (
            <span className="figure inline-flex items-center rounded-full border border-warning px-2 py-0.5 text-[0.6rem] uppercase tracking-[0.08em] text-warning">
              {late} {late === 1 ? "day" : "days"} over
            </span>
          ) : (
            <span
              className={`figure inline-flex items-center rounded-full border px-2 py-0.5 text-[0.6rem] uppercase tracking-[0.08em] ${look.chip}`}
            >
              {look.label}
            </span>
          )}
        </div>

        {children}
      </CardContent>
    </Card>
  );
}

function InvoicesPage() {
  const { data: clients = [], error: clientsError } = useClients();
  const { data: invoices = [], error: invoicesError } = useInvoices();
  const { data: emailEvents = [], error: emailEventsError } = useAllInvoiceEmailEvents();
  const { data: visits = [], error: visitsError } = useVisits();
  const { data: settings, error: settingsError } = useSettings();

  // The draft being corrected before it goes anywhere.
  const [editingDraft, setEditingDraft] = useState<Invoice | null>(null);
  const deleteInvoice = useDeleteInvoice();
  const updateInvoice = useUpdateInvoice();
  const { client: clientFromLink } = Route.useSearch();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [presetClient, setPresetClient] = useState<string | null>(null);

  // Arriving with a client in the URL opens the dialog on them. The parameter
  // is cleared straight away so closing the dialog and reopening it later does
  // not silently reuse a client picked minutes ago.
  useEffect(() => {
    if (!clientFromLink) return;
    setPresetClient(clientFromLink);
    setCreating(true);
    navigate({ to: "/invoices", search: {}, replace: true });
  }, [clientFromLink, navigate]);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [openHistory, setOpenHistory] = useState<string | null>(null);
  const [preview, setPreview] = useState<{
    invoice: Invoice;
    client: Client;
    items: InvoiceItem[];
    intent: "download" | "send";
  } | null>(null);
  // The invoice whose email is being worded before it goes out.
  const [composing, setComposing] = useState<{ invoice: Invoice; client: Client } | null>(null);

  const queryClient = useQueryClient();

  const error = clientsError || invoicesError || emailEventsError || visitsError || settingsError;
  if (error)
    return (
      <AppShell title="Invoices">
        <p className="text-destructive">Error loading invoices: {error.message}</p>
      </AppShell>
    );

  const sendFn = sendInvoiceEmail;
  const createFn = createInvoice;

  const clientName = (id: string) => clients.find((c) => c.id === id)?.name ?? "Client";

  async function loadInvoiceData(invoice: Invoice) {
    const client = clients.find((c) => c.id === invoice.client_id);
    if (!client) throw new Error("Client missing");
    const { data: items, error } = await supabase
      .from("invoice_items")
      .select("*")
      .eq("invoice_id", invoice.id)
      .order("item_date");
    if (error) throw error;
    return { client, items: items ?? [] };
  }

  async function buildPdf(invoice: Invoice) {
    const { client, items } = await loadInvoiceData(invoice);
    return await buildInvoicePdf({ invoice, items, client, settings: settings ?? null });
  }

  async function download(invoice: Invoice) {
    try {
      setBusyId(invoice.id);
      const doc = await buildPdf(invoice);
      const outcome = await savePdf(doc, `${invoice.number}.pdf`);
      if (outcome.kind === "shared") toast.success(`${invoice.number}.pdf ready to save or send`);
      if (outcome.kind === "saved") toast.success(`Saved to ${outcome.where}`);
    } catch (error) {
      // Whatever goes wrong, say so. This button used to fail in silence,
      // which is how it went unnoticed that it never worked on the phone.
      toast.error(error instanceof Error ? error.message : "Could not save the PDF");
    } finally {
      setBusyId(null);
    }
  }

  async function openPreview(invoice: Invoice, intent: "download" | "send") {
    try {
      setBusyId(invoice.id);
      const { client, items } = await loadInvoiceData(invoice);
      setPreview({ invoice, client, items, intent });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not build the preview");
    } finally {
      setBusyId(null);
    }
  }

  function closePreview() {
    setPreview(null);
  }

  /** Opens the email for wording. Nothing is sent until Send in the dialog. */
  function compose(invoice: Invoice) {
    const client = clients.find((c) => c.id === invoice.client_id);
    if (!client) {
      toast.error("This invoice has no client on it");
      return;
    }
    setComposing({ invoice, client });
  }

  // With no draft this is the one-tap send it has always been: the saved
  // template, filled in on the server. A draft replaces the wording for this
  // send only.
  async function sendOneClick(invoice: Invoice, draft?: SendInvoiceDraft) {
    try {
      setBusyId(invoice.id);
      const doc = await buildPdf(invoice);
      const result = (await sendFn({
        data: {
          invoiceId: invoice.id,
          pdfBase64: pdfToBase64(doc),
          subject: draft?.subject ?? null,
          message: draft?.message ?? null,
        },
      })) as { sent: boolean; to?: string; reason?: string };
      if (result.sent) toast.success(`Invoice emailed to ${result.to}`);
      else toast.warning(result.reason ?? "Email is not set up yet");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not send the invoice");
    } finally {
      setBusyId(null);
      setOpenHistory(invoice.id);
      await queryClient.invalidateQueries({ queryKey: ["invoice_email_events"] });
      await queryClient.invalidateQueries({ queryKey: ["invoices"] });
    }
  }

  const unpaid = invoices.filter((i) => i.status !== "paid");
  const owedTotal = unpaid.reduce((sum, i) => sum + Number(i.total), 0);
  const unpaidCount = unpaid.length;

  // Client name as well as the number, because an invoice is remembered as
  // "the one for Mrs Smith" far more often than as JW-0007. Status is searchable
  // too, so "paid" and "draft" work as filters without needing their own control.
  const shown = filterBy(invoices, query, (invoice) => [
    invoice.number,
    clientName(invoice.client_id),
    invoice.status,
    invoice.notes,
  ]);

  return (
    <AppShell
      title="Invoices"
      description="Build an invoice from unbilled visits, then send it with one click."
      onRefresh={() => queryClient.refetchQueries({ type: "active" })}
      actions={
        <Button onClick={() => setCreating(true)} disabled={clients.length === 0}>
          <Plus className="size-4" /> New invoice
        </Button>
      }
    >
      {/* What is owed leads, because it is the only number this page exists for. */}
      <section className="mb-3 rounded-3xl bg-band p-5 text-band-foreground">
        <div className="flex items-baseline justify-between gap-3">
          <p className="figure text-[0.65rem] uppercase tracking-[0.14em] opacity-75">
            Owed to you
          </p>
          <p className="figure text-[0.65rem] uppercase tracking-[0.14em] opacity-75">
            {unpaidCount} unpaid
          </p>
        </div>
        <p className="figure mt-1.5 text-4xl font-semibold leading-none">{money(owedTotal)}</p>
      </section>

      {/* Above the reminder to invoice, because money that has already arrived
          is more urgent than money not yet asked for. */}
      <div className="mb-3 space-y-3">
        <PaymentsToReview />
        <InvoiceReminders />
      </div>

      {/* The total above always counts every unpaid invoice, searched or not:
          a filtered list must never make the amount owed look smaller. Shown
          for any non-empty list — hiding it on short ones only made it look
          missing. */}
      {invoices.length > 0 ? (
        <div className="mb-3">
          <SearchField
            value={query}
            onChange={setQuery}
            label="Search invoices"
            placeholder="Invoice number or client"
          />
        </div>
      ) : null}

      {invoices.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
            <FilePlus2 className="size-8 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">
              No invoices yet. Log some visits, then create your first invoice.
            </p>
          </CardContent>
        </Card>
      ) : shown.length === 0 ? (
        <Card>
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            No invoice matches “{query}”.
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-2">
          {shown.map((invoice) => (
            <InvoiceCard
              key={invoice.id}
              invoice={invoice}
              clientName={clientName(invoice.client_id)}
            >
              {/* The two actions this card is for, at thumb height. */}
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="h-10 flex-1"
                  disabled={busyId === invoice.id}
                  onClick={() => openPreview(invoice, "download")}
                >
                  <Eye className="size-4" /> Preview
                </Button>
                <Button
                  size="sm"
                  className="h-10 flex-1"
                  disabled={busyId === invoice.id}
                  onClick={() => sendOneClick(invoice)}
                >
                  <Mail className="size-4" /> {invoice.status === "draft" ? "Send" : "Resend"}
                </Button>
              </div>

              {/* Everything else stays small, because it is not what you came for. */}
              <div className="flex flex-wrap items-center gap-1">
                {/* Send above stays one tap. This is the same send with the
                    wording opened first, for the invoice that needs a line on
                    it. */}
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busyId === invoice.id}
                  onClick={() => compose(invoice)}
                >
                  <MessageSquare className="size-4" /> Message
                </Button>
                {/* Only a draft. A sent invoice has to go on matching the copy
                    the customer is holding. */}
                {invoice.status === "draft" ? (
                  <Button variant="ghost" size="sm" onClick={() => setEditingDraft(invoice)}>
                    <Pencil className="size-4" /> Edit
                  </Button>
                ) : null}
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
                <AlertDialog>
                  <AlertDialogTrigger asChild>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`Delete invoice ${invoice.number}`}
                      className="text-destructive hover:text-destructive"
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  </AlertDialogTrigger>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Delete {invoice.number}?</AlertDialogTitle>
                      <AlertDialogDescription>
                        This removes the invoice, its lines and its email history. Any visits on it
                        go back to unbilled so you can invoice them again.
                      </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                      <AlertDialogCancel>Cancel</AlertDialogCancel>
                      <AlertDialogAction
                        onClick={async () => {
                          try {
                            await deleteInvoice.mutateAsync(invoice.id);
                            toast.success(`Invoice ${invoice.number} deleted`);
                          } catch (error) {
                            toast.error(
                              error instanceof Error ? error.message : "Could not delete",
                            );
                          }
                        }}
                      >
                        Delete invoice
                      </AlertDialogAction>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>

              <LastAttempt event={emailEvents.find((e) => e.invoice_id === invoice.id) ?? null} />
              {openHistory === invoice.id ? <EmailHistory invoiceId={invoice.id} /> : null}
            </InvoiceCard>
          ))}
        </div>
      )}

      <DraftInvoiceDialog invoice={editingDraft} onClose={() => setEditingDraft(null)} />

      {/* Keyed on the invoice, so each one is composed on a fresh dialog rather
          than in a box that has to remember to empty itself. */}
      <SendInvoiceDialog
        key={composing?.invoice.id ?? "none"}
        invoice={composing?.invoice ?? null}
        client={composing?.client ?? null}
        settings={settings ?? null}
        busy={busyId === composing?.invoice.id}
        onClose={() => setComposing(null)}
        onSend={async (draft) => {
          if (!composing) return;
          const invoice = composing.invoice;
          // Closed before sending, as Send in the preview does: the toast is
          // what reports the outcome, and a dialog left open over it reads as
          // though nothing happened.
          setComposing(null);
          await sendOneClick(invoice, draft);
        }}
      />

      <NewInvoiceDialog
        open={creating}
        initialClientId={presetClient}
        onOpenChange={(v) => {
          setCreating(v);
          if (!v) setPresetClient(null);
        }}
        unbilledVisits={visits.filter((v) => !v.billed && !v.missed)}
        create={async (input) => {
          const result = (await createFn({ data: input })) as { number: string };
          toast.success(`Invoice ${result.number} created`);
          setCreating(false);
        }}
      />

      <Dialog open={preview !== null} onOpenChange={(open) => (open ? null : closePreview())}>
        <DialogContent className="max-h-[92svh] w-[calc(100vw-1.5rem)] max-w-3xl overflow-hidden p-4 sm:p-6">
          <DialogHeader>
            <DialogTitle>Preview {preview?.invoice.number}</DialogTitle>
            <DialogDescription>
              Check the layout before you {preview?.intent === "send" ? "send" : "download"} it.
            </DialogDescription>
          </DialogHeader>
          {preview ? (
            <div className="-mx-1 max-h-[62svh] overflow-auto px-1">
              <InvoicePreviewDoc
                invoice={preview.invoice}
                items={preview.items}
                client={preview.client}
                settings={settings ?? null}
              />
            </div>
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

const EVENT_META: Record<string, { label: string; icon: typeof CheckCircle2; className: string }> =
  {
    sent: { label: "Sent", icon: CheckCircle2, className: "text-primary" },
    failed: { label: "Failed", icon: AlertCircle, className: "text-destructive" },
    skipped: { label: "Not sent", icon: MinusCircle, className: "text-muted-foreground" },
    // Reported by Resend after the fact, through the webhook.
    delivered: { label: "Delivered", icon: MailCheck, className: "text-primary" },
    opened: { label: "Opened", icon: Eye, className: "text-primary" },
    clicked: { label: "Link clicked", icon: MousePointerClick, className: "text-primary" },
    bounced: { label: "Bounced", icon: AlertCircle, className: "text-destructive" },
    complained: { label: "Marked as spam", icon: AlertCircle, className: "text-destructive" },
    delivery_delayed: { label: "Delayed", icon: Clock, className: "text-muted-foreground" },
  };

/** The statuses whose detail line is worth showing next to the label. */
const DETAIL_WORTH_SHOWING = new Set(["failed", "skipped", "bounced", "complained"]);

function LastAttempt({ event }: { event: InvoiceEmailEvent | null }) {
  if (!event) {
    return (
      <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <MinusCircle className="size-3.5" /> Not sent yet
      </p>
    );
  }
  const meta = EVENT_META[event.status] ?? EVENT_META["skipped"]!;
  const Icon = meta.icon;
  return (
    <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
      <Icon className={`size-3.5 ${meta.className}`} />
      <span className="font-medium text-foreground">{meta.label}</span>
      {event.recipient ? <span>to {event.recipient}</span> : null}
      <span>·</span>
      <span>{format(parseISO(event.created_at), "d MMM yyyy, HH:mm")}</span>
      {event.detail && DETAIL_WORTH_SHOWING.has(event.status) ? (
        <span>· {event.detail}</span>
      ) : null}
    </p>
  );
}

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
  initialClientId,
  onOpenChange,
  unbilledVisits,
  create,
}: {
  open: boolean;
  initialClientId?: string | null;
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

  // The dialog stays mounted between openings, so its fields are put back
  // deliberately each time — otherwise the last invoice's notes and retainer
  // would be waiting in the next one.
  useEffect(() => {
    if (!open) return;
    const client = initialClientId ?? "";
    setClientId(client);
    setSelected(
      client ? unbilledVisits.filter((v) => v.client_id === client).map((v) => v.id) : [],
    );
    setRetainer("0");
    setRetainerDescription("");
    setNotes("");
    setDueDate(new Date(Date.now() + 14 * 86400000).toISOString().slice(0, 10));
    // unbilledVisits is deliberately not a dependency: it changes identity on
    // every refetch, and re-ticking the boxes underneath someone mid-edit
    // would be worse than starting from a slightly stale list.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initialClientId]);

  const clientVisits = useMemo(
    () => unbilledVisits.filter((v) => v.client_id === clientId),
    [unbilledVisits, clientId],
  );

  const total =
    clientVisits.filter((v) => selected.includes(v.id)).reduce((s, v) => s + Number(v.amount), 0) +
    (Number(retainer) || 0);

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
            <Textarea
              id="inotes"
              rows={2}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
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
