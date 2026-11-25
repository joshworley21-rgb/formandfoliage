import { createFileRoute, Link } from "@tanstack/react-router";
import { useMemo, useState } from "react";
import {
  Check,
  CheckCircle2,
  Clock3,
  Copy,
  MoreHorizontal,
  Pencil,
  Plus,
  Send,
  Trash2,
  UserPlus,
  X,
  XCircle,
} from "lucide-react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
import { OwnerOnly } from "@/components/OwnerOnly";
import { QuoteDialog } from "@/components/QuoteDialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useClients, useSettings } from "@/hooks/use-crm";
import {
  useConvertQuoteToClient,
  useDeleteQuote,
  useQuotes,
  useSetQuoteStatus,
  type SaveQuoteInput,
} from "@/hooks/use-quotes";
import { money } from "@/lib/crm";
import { publicSiteUrl, SITE_URL_MISSING } from "@/lib/site";
import { sendQuoteEmail } from "@/lib/invoicing";
import {
  displayStatus,
  quoteLink,
  QUOTE_STATUS_LABELS,
  type Quote,
  type QuoteStatus,
} from "@/lib/quotes";
import { cn } from "@/lib/utils";

export const Route = createFileRoute("/_authenticated/quotes")({
  head: () => ({
    meta: [
      { title: "Quotes — JW Garden Services CRM" },
      {
        name: "description",
        content:
          "Price up work, send quotes customers can accept online, and turn them into clients.",
      },
      { property: "og:title", content: "Quotes — JW Garden Services CRM" },
      {
        property: "og:description",
        content:
          "Price up work, send quotes customers can accept online, and turn them into clients.",
      },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <QuotesPage />
    </OwnerOnly>
  ),
});

const STATUS_VARIANT: Record<QuoteStatus, "default" | "secondary" | "outline" | "destructive"> = {
  draft: "outline",
  sent: "default",
  accepted: "secondary",
  declined: "destructive",
  expired: "outline",
};

const STATUS_TILE: Record<QuoteStatus, { icon: typeof Pencil; tint: string; label: string }> = {
  draft: { icon: Pencil, tint: "bg-gold/25 text-gold-foreground", label: "Drafts" },
  sent: { icon: Send, tint: "bg-[oklch(0.9_0.03_250)] text-[oklch(0.35_0.06_250)]", label: "Sent" },
  accepted: {
    icon: CheckCircle2,
    tint: "bg-primary/12 text-primary",
    label: "Approved",
  },
  declined: { icon: XCircle, tint: "bg-destructive/12 text-destructive", label: "Declined" },
  expired: { icon: Clock3, tint: "bg-muted text-muted-foreground", label: "Expired" },
};

const FILTERS: Array<{ value: "all" | QuoteStatus; label: string }> = [
  { value: "all", label: "All" },
  { value: "draft", label: "Draft" },
  { value: "sent", label: "Sent" },
  { value: "accepted", label: "Approved" },
  { value: "declined", label: "Declined" },
  { value: "expired", label: "Expired" },
];

const EMPTY_DRAFT: SaveQuoteInput = {
  contactName: "",
  items: [{ description: "", quantity: 1, unit_price: 0 }],
};

/** £18k rather than £18,000.00 — the summary line, not an invoice. */
function compactMoney(value: number): string {
  return new Intl.NumberFormat("en-GB", {
    style: "currency",
    currency: "GBP",
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(value);
}

function QuotesPage() {
  const { data: quotes = [], isLoading } = useQuotes();
  const { data: clients = [] } = useClients();
  const { data: settings } = useSettings();
  const siteUrl = publicSiteUrl(settings?.public_site_url);
  const remove = useDeleteQuote();
  const setStatus = useSetQuoteStatus();
  const convert = useConvertQuoteToClient();

  const [editing, setEditing] = useState<SaveQuoteInput | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [filter, setFilter] = useState<"all" | QuoteStatus>("all");

  const withStatus = useMemo(
    () => quotes.map((quote) => ({ quote, status: displayStatus(quote) })),
    [quotes],
  );

  const counts = useMemo(() => {
    const result: Record<QuoteStatus, number> = {
      draft: 0,
      sent: 0,
      accepted: 0,
      declined: 0,
      expired: 0,
    };
    for (const entry of withStatus) result[entry.status] += 1;
    return result;
  }, [withStatus]);

  const shown = useMemo(
    () =>
      withStatus
        .filter((entry) => filter === "all" || entry.status === filter)
        .sort((a, b) => b.quote.issue_date.localeCompare(a.quote.issue_date)),
    [withStatus, filter],
  );

  const shownTotal = shown.reduce((total, entry) => total + Number(entry.quote.total), 0);
  const awaiting = counts.sent;

  async function send(quote: Quote) {
    if (!siteUrl) {
      toast.error(SITE_URL_MISSING);
      return;
    }
    setBusyId(quote.id);
    try {
      const result = await sendQuoteEmail({
        data: { quoteId: quote.id, origin: siteUrl },
      });
      if (result.sent) toast.success(`Quote emailed to ${result.to}`);
      else toast.warning(result.reason ?? "Email is not set up yet");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not send the quote");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <AppShell
      title="Quotes"
      description="Proposals & price agreements."
      actions={
        <Button variant="gold" onClick={() => setEditing(EMPTY_DRAFT)}>
          <Plus className="size-4" /> Add
        </Button>
      }
    >
      {/* Five states, each a filter. The count is the useful part. */}
      <div className="grid grid-cols-5 gap-1.5">
        {(Object.keys(STATUS_TILE) as QuoteStatus[]).map((status) => {
          const tile = STATUS_TILE[status];
          const Icon = tile.icon;
          const active = filter === status;
          return (
            <button
              key={status}
              type="button"
              onClick={() => setFilter(active ? "all" : status)}
              className={cn(
                "flex min-w-0 flex-col items-center gap-1 rounded-xl border bg-card px-1 py-2.5 transition-colors",
                active ? "border-primary ring-1 ring-primary" : "border-border",
              )}
              aria-pressed={active}
            >
              <span className={cn("flex size-7 items-center justify-center rounded-lg", tile.tint)}>
                <Icon className="size-3.5" strokeWidth={2} />
              </span>
              <span className="figure text-lg font-semibold leading-none">{counts[status]}</span>
              <span className="w-full truncate text-center text-[0.55rem] text-muted-foreground">
                {tile.label}
              </span>
            </button>
          );
        })}
      </div>

      <div className="-mx-4 mt-3 flex gap-1.5 overflow-x-auto px-4 pb-1">
        {FILTERS.map((item) => {
          const active = filter === item.value;
          const count = item.value === "all" ? quotes.length : counts[item.value];
          return (
            <button
              key={item.value}
              type="button"
              onClick={() => setFilter(item.value)}
              className={cn(
                "shrink-0 rounded-full px-3 py-1.5 text-xs font-medium transition-colors",
                active
                  ? "bg-primary text-primary-foreground"
                  : "bg-secondary/70 text-muted-foreground hover:text-foreground",
              )}
            >
              {item.label} ({count})
            </button>
          );
        })}
      </div>

      {isLoading ? (
        <p className="mt-4 text-sm text-muted-foreground">Loading quotes…</p>
      ) : quotes.length === 0 ? (
        <Card className="mt-3">
          <CardContent className="flex flex-col items-center gap-3 py-10 text-center">
            <p className="text-sm text-muted-foreground">
              No quotes yet. Start one here, or from an enquiry in the inbox.
            </p>
            <Button variant="gold" onClick={() => setEditing(EMPTY_DRAFT)}>
              <Plus className="size-4" /> New quote
            </Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <div className="mt-3 flex items-center justify-between gap-2 rounded-xl border border-border bg-card px-3 py-2">
            <p className="min-w-0 truncate text-xs text-muted-foreground">
              <span className="font-semibold text-foreground">{shown.length}</span>{" "}
              {shown.length === 1 ? "quote" : "quotes"} ·{" "}
              <span className="font-semibold text-foreground">{compactMoney(shownTotal)}</span>{" "}
              total
              {awaiting > 0 ? ` · ${awaiting} awaiting response` : ""}
            </p>
            <span className="shrink-0 text-[0.65rem] text-muted-foreground">Newest first</span>
          </div>

          <div className="mt-3 grid gap-2.5">
            {shown.map(({ quote, status }) => {
              const client = clients.find((c) => c.id === quote.client_id);
              return (
                <Card
                  key={quote.id}
                  className="stripe overflow-hidden py-0"
                  style={{
                    ["--stripe" as string]:
                      status === "accepted"
                        ? "var(--color-primary)"
                        : status === "declined"
                          ? "var(--color-destructive)"
                          : status === "sent"
                            ? "var(--color-leaf)"
                            : "var(--color-gold)",
                  }}
                >
                  <CardContent className="py-3 pl-4 pr-2">
                    <div className="flex items-start gap-2">
                      <button
                        type="button"
                        onClick={() => setEditing(toDraft(quote))}
                        className="min-w-0 flex-1 text-left"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <p className="min-w-0 truncate text-sm font-semibold">
                            {quote.contact_name}
                          </p>
                          <p className="figure shrink-0 text-sm font-semibold">
                            {money(quote.total)}
                          </p>
                        </div>
                        <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
                          <p className="figure text-[0.65rem] text-muted-foreground">
                            {quote.number}
                          </p>
                          <Badge
                            variant={STATUS_VARIANT[status]}
                            className={cn(
                              "px-1.5 py-0 text-[0.55rem] font-semibold",
                              status === "sent" &&
                                "border-transparent bg-[oklch(0.9_0.03_250)] text-[oklch(0.35_0.06_250)]",
                              status === "accepted" &&
                                "border-transparent bg-primary/12 text-primary",
                              status === "draft" && "border-gold/40 text-gold-foreground",
                            )}
                          >
                            {status === "accepted" ? "Approved" : QUOTE_STATUS_LABELS[status]}
                          </Badge>
                        </div>
                        {quote.contact_address ? (
                          <p className="mt-2 truncate text-xs text-muted-foreground">
                            {quote.contact_address}
                          </p>
                        ) : null}
                        <p className="mt-1 text-[0.65rem] text-muted-foreground">
                          {quote.valid_until
                            ? `Valid until ${quote.valid_until.slice(0, 10)}`
                            : `Raised ${quote.issue_date}`}
                          {client ? ` · ${client.name}` : ""}
                        </p>
                      </button>

                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button
                            variant="ghost"
                            size="icon"
                            className="size-8 shrink-0"
                            aria-label={`Actions for ${quote.contact_name}'s quote`}
                          >
                            <MoreHorizontal className="size-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-52">
                          <DropdownMenuItem
                            className="h-10"
                            onSelect={() => setEditing(toDraft(quote))}
                          >
                            <Pencil className="size-4" /> Edit quote
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            className="h-10"
                            disabled={busyId === quote.id || !quote.contact_email}
                            onSelect={() => {
                              void send(quote);
                            }}
                          >
                            <Send className="size-4" />
                            {quote.status === "draft" ? "Send quote" : "Send again"}
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            className="h-10"
                            onSelect={async () => {
                              if (!siteUrl) {
                                toast.error(SITE_URL_MISSING);
                                return;
                              }
                              await navigator.clipboard.writeText(
                                quoteLink(quote.public_key, siteUrl),
                              );
                              toast.success("Quote link copied");
                            }}
                          >
                            <Copy className="size-4" /> Copy link
                          </DropdownMenuItem>

                          {quote.status === "sent" ? (
                            <>
                              <DropdownMenuSeparator />
                              <DropdownMenuItem
                                className="h-10"
                                onSelect={async () => {
                                  await setStatus.mutateAsync({
                                    id: quote.id,
                                    status: "accepted",
                                  });
                                  toast.success("Marked as accepted");
                                }}
                              >
                                <Check className="size-4" /> Mark accepted
                              </DropdownMenuItem>
                              <DropdownMenuItem
                                className="h-10"
                                onSelect={async () => {
                                  await setStatus.mutateAsync({
                                    id: quote.id,
                                    status: "declined",
                                  });
                                }}
                              >
                                <X className="size-4" /> Mark declined
                              </DropdownMenuItem>
                            </>
                          ) : null}

                          {status === "accepted" && !quote.client_id ? (
                            <>
                              <DropdownMenuSeparator />
                              <DropdownMenuItem
                                className="h-10"
                                onSelect={async () => {
                                  try {
                                    await convert.mutateAsync(quote);
                                    toast.success(`${quote.contact_name} added to clients`);
                                  } catch (error) {
                                    toast.error(
                                      error instanceof Error
                                        ? error.message
                                        : "Could not add the client",
                                    );
                                  }
                                }}
                              >
                                <UserPlus className="size-4" /> Add as client
                              </DropdownMenuItem>
                            </>
                          ) : null}

                          <DropdownMenuSeparator />
                          <DropdownMenuItem
                            className="h-10 text-destructive focus:text-destructive"
                            onSelect={async () => {
                              if (!confirm(`Delete quote ${quote.number}?`)) return;
                              await remove.mutateAsync(quote.id);
                              toast.success("Quote deleted");
                            }}
                          >
                            <Trash2 className="size-4" /> Delete
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </div>
                  </CardContent>
                </Card>
              );
            })}

            {shown.length === 0 ? (
              <Card>
                <CardContent className="py-8 text-center text-sm text-muted-foreground">
                  No {FILTERS.find((item) => item.value === filter)?.label.toLowerCase()} quotes.
                  {filter === "all" ? (
                    <>
                      {" "}
                      <Link to="/enquiries" className="font-medium text-primary">
                        Check the enquiries inbox.
                      </Link>
                    </>
                  ) : null}
                </CardContent>
              </Card>
            ) : null}
          </div>
        </>
      )}

      <QuoteDialog
        open={editing !== null}
        draft={editing}
        onClose={() => setEditing(null)}
        onSaved={() => setEditing(null)}
      />

      <button
        type="button"
        onClick={() => setEditing(EMPTY_DRAFT)}
        aria-label="New quote"
        className="fixed bottom-[calc(env(safe-area-inset-bottom)+4.75rem)] right-4 z-30 flex size-14 items-center justify-center rounded-full bg-gold text-gold-foreground shadow-lg shadow-black/25 transition-transform active:scale-95 lg:bottom-6 lg:right-6"
      >
        <Plus className="size-6" />
      </button>
    </AppShell>
  );
}

/** Turn a saved quote back into the shape the dialog edits. */
function toDraft(quote: Quote): SaveQuoteInput {
  return {
    id: quote.id,
    clientId: quote.client_id,
    enquiryId: quote.enquiry_id,
    contactName: quote.contact_name,
    contactEmail: quote.contact_email,
    contactPhone: quote.contact_phone,
    contactAddress: quote.contact_address,
    validUntil: quote.valid_until,
    notes: quote.notes,
    terms: quote.terms,
    items: [],
  };
}
