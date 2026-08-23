import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { Check, Copy, Plus, Send, Trash2, UserPlus, X } from "lucide-react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
import { OwnerOnly } from "@/components/OwnerOnly";
import { QuoteDialog } from "@/components/QuoteDialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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

const EMPTY_DRAFT: SaveQuoteInput = {
  contactName: "",
  items: [{ description: "", quantity: 1, unit_price: 0 }],
};

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
      description="Price up a job, send it over, and let the customer accept with one tap."
      actions={
        <Button onClick={() => setEditing(EMPTY_DRAFT)}>
          <Plus className="mr-2 size-4" />
          New quote
        </Button>
      }
    >
      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading quotes…</p>
      ) : quotes.length === 0 ? (
        <Card>
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            No quotes yet. Start one from here, or from an enquiry in the Enquiries inbox.
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3">
          {quotes.map((quote) => {
            const status = displayStatus(quote);
            const client = clients.find((c) => c.id === quote.client_id);
            return (
              <Card key={quote.id}>
                <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3 pb-3">
                  <div className="min-w-0">
                    <CardTitle className="flex flex-wrap items-center gap-2 text-base">
                      {quote.number}
                      <Badge variant={STATUS_VARIANT[status]}>{QUOTE_STATUS_LABELS[status]}</Badge>
                    </CardTitle>
                    <p className="mt-1 truncate text-sm text-muted-foreground">
                      {quote.contact_name}
                      {quote.contact_address ? ` — ${quote.contact_address}` : ""}
                    </p>
                  </div>
                  <p className="text-lg font-semibold">{money(quote.total)}</p>
                </CardHeader>

                <CardContent className="space-y-3">
                  <p className="text-xs text-muted-foreground">
                    Raised {quote.issue_date}
                    {quote.valid_until ? ` · valid until ${quote.valid_until}` : ""}
                    {quote.decided_at ? ` · answered ${quote.decided_at.slice(0, 10)}` : ""}
                  </p>

                  {quote.status === "declined" && quote.decline_reason ? (
                    <p className="rounded-lg bg-muted px-3 py-2 text-sm">
                      Reason given: {quote.decline_reason}
                    </p>
                  ) : null}

                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busyId === quote.id || !quote.contact_email}
                      onClick={() => send(quote)}
                      title={quote.contact_email ? undefined : "This quote has no email address"}
                    >
                      <Send className="mr-2 size-4" />
                      {quote.status === "draft" ? "Send" : "Send again"}
                    </Button>

                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={async () => {
                        if (!siteUrl) {
                          toast.error(SITE_URL_MISSING);
                          return;
                        }
                        await navigator.clipboard.writeText(quoteLink(quote.public_key, siteUrl));
                        toast.success("Quote link copied");
                      }}
                    >
                      <Copy className="mr-2 size-4" />
                      Copy link
                    </Button>

                    <Button variant="ghost" size="sm" onClick={() => setEditing(toDraft(quote))}>
                      Edit
                    </Button>

                    {/* Answers that come in by phone rather than through the link. */}
                    {quote.status === "sent" ? (
                      <>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={async () => {
                            await setStatus.mutateAsync({ id: quote.id, status: "accepted" });
                            toast.success("Marked as accepted");
                          }}
                        >
                          <Check className="mr-2 size-4" />
                          Accepted
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={async () => {
                            await setStatus.mutateAsync({ id: quote.id, status: "declined" });
                          }}
                        >
                          <X className="mr-2 size-4" />
                          Declined
                        </Button>
                      </>
                    ) : null}

                    {quote.status === "accepted" && !quote.client_id ? (
                      <Button
                        size="sm"
                        onClick={async () => {
                          try {
                            await convert.mutateAsync(quote);
                            toast.success(`${quote.contact_name} added to clients`);
                          } catch (error) {
                            toast.error(
                              error instanceof Error ? error.message : "Could not add the client",
                            );
                          }
                        }}
                      >
                        <UserPlus className="mr-2 size-4" />
                        Add as client
                      </Button>
                    ) : null}

                    {client ? (
                      <span className="self-center text-xs text-muted-foreground">
                        Client: {client.name}
                      </span>
                    ) : null}

                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Delete quote"
                      className="ml-auto"
                      onClick={async () => {
                        await remove.mutateAsync(quote.id);
                        toast.success("Quote deleted");
                      }}
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <QuoteDialog
        open={editing !== null}
        draft={editing}
        onClose={() => setEditing(null)}
        onSaved={() => setEditing(null)}
      />
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
