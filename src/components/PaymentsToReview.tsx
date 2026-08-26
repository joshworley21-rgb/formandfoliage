// Payments that have landed in the bank and are waiting to be told what they
// are for.
//
// The CRM never settles an invoice off the back of a bank feed on its own. It
// guesses, explains the guess in a sentence, and leaves the decision here. A
// wrongly auto-settled invoice stops being chased, and nobody finds out until
// the money is long overdue — one tap is much the cheaper mistake.
import { useState } from "react";
import { format, parseISO } from "date-fns";
import { Landmark } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useConfirmPayment, useIgnorePayment, usePaymentsToReview } from "@/hooks/use-bank";
import { useClients, useInvoices } from "@/hooks/use-crm";
import { money } from "@/lib/crm";

export function PaymentsToReview() {
  const { data: payments = [] } = usePaymentsToReview();
  const { data: invoices = [] } = useInvoices();
  const { data: clients = [] } = useClients();
  const confirmPayment = useConfirmPayment();
  const ignorePayment = useIgnorePayment();
  // Only set once someone overrides the suggestion, so the suggestion stays the
  // default without being copied into state the moment the list loads.
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);

  if (payments.length === 0) return null;

  const unpaid = invoices.filter((invoice) => invoice.status !== "paid");
  const clientName = (id: string | null) =>
    clients.find((client) => client.id === id)?.name ?? "Client";

  return (
    <Card className="border-primary/40 bg-primary/5">
      <CardContent className="space-y-3 py-4">
        <div className="flex items-start gap-2">
          <Landmark className="mt-0.5 size-5 shrink-0 text-primary" />
          <div>
            <p className="font-display text-lg font-semibold">
              {payments.length === 1
                ? "A payment has arrived"
                : `${payments.length} payments have arrived`}
            </p>
            <p className="text-sm text-muted-foreground">
              Money paid into your Monzo account. Confirm what each one settles and the invoice is
              marked paid.
            </p>
          </div>
        </div>

        <ul className="divide-y divide-border rounded-xl border border-border bg-card">
          {payments.map((payment) => {
            const suggestedId = payment.suggested_invoice_id ?? "";
            const selected = chosen[payment.id] ?? suggestedId;
            const busy = busyId === payment.id;

            return (
              <li key={payment.id} className="space-y-2 px-3 py-3">
                <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                  <p className="font-display text-lg">{money(Number(payment.amount))}</p>
                  <p className="min-w-0 flex-1 truncate text-sm">
                    {payment.counterparty ?? payment.description ?? "Payment in"}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {format(parseISO(payment.posted_at), "d MMM")}
                  </p>
                </div>

                {/* The reference is what the matcher had to work with, so it is
                    worth seeing next to the suggestion it produced. */}
                {payment.reference ? (
                  <p className="truncate text-xs text-muted-foreground">
                    Reference: {payment.reference}
                  </p>
                ) : null}
                <p className="text-xs text-muted-foreground">
                  {payment.match_reason ?? "Nothing here says which invoice this is for."}
                </p>

                <div className="flex flex-wrap items-center gap-2">
                  <Select
                    value={selected}
                    onValueChange={(value) =>
                      setChosen((current) => ({ ...current, [payment.id]: value }))
                    }
                  >
                    <SelectTrigger className="h-10 w-full sm:w-72">
                      <SelectValue placeholder="Choose an invoice" />
                    </SelectTrigger>
                    <SelectContent>
                      {unpaid.length === 0 ? (
                        <SelectItem value="none" disabled>
                          No unpaid invoices
                        </SelectItem>
                      ) : (
                        unpaid.map((invoice) => (
                          <SelectItem key={invoice.id} value={invoice.id}>
                            {invoice.number} · {clientName(invoice.client_id)} ·{" "}
                            {money(Number(invoice.total))}
                          </SelectItem>
                        ))
                      )}
                    </SelectContent>
                  </Select>

                  <div className="flex flex-1 items-center gap-2">
                    <Button
                      size="sm"
                      className="h-10 flex-1"
                      disabled={busy || !selected}
                      onClick={async () => {
                        setBusyId(payment.id);
                        try {
                          await confirmPayment.mutateAsync({
                            transactionId: payment.id,
                            invoiceId: selected,
                          });
                          const invoice = unpaid.find((candidate) => candidate.id === selected);
                          toast.success(`${invoice?.number ?? "Invoice"} marked paid`);
                        } catch (error) {
                          toast.error(
                            error instanceof Error ? error.message : "Could not match the payment",
                          );
                        } finally {
                          setBusyId(null);
                        }
                      }}
                    >
                      Confirm
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-10"
                      disabled={busy}
                      onClick={async () => {
                        setBusyId(payment.id);
                        try {
                          await ignorePayment.mutateAsync(payment.id);
                          toast.success("Payment dismissed");
                        } catch (error) {
                          toast.error(
                            error instanceof Error ? error.message : "Could not dismiss it",
                          );
                        } finally {
                          setBusyId(null);
                        }
                      }}
                    >
                      Not an invoice
                    </Button>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      </CardContent>
    </Card>
  );
}
