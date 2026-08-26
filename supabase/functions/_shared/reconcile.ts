// Turning Monzo transactions into rows waiting to be reviewed.
//
// Both ways in end up here — the webhook when money lands, and the sync that
// backfills and catches anything the webhook missed — so that a payment looks
// the same however the CRM heard about it.
import { suggestMatch, type MatchCandidate } from "./matching.ts";
import type { MonzoTransaction } from "./monzo.ts";
import type { SupabaseClient } from "./supabase.ts";

/** Money out, declined attempts, and card refunds are none of this feature's business. */
export function isIncomingPayment(transaction: MonzoTransaction): boolean {
  return transaction.amount > 0 && !transaction.decline_reason;
}

export type PaymentRow = {
  user_id: string;
  provider_transaction_id: string;
  posted_at: string;
  settled_at: string | null;
  amount: number;
  currency: string;
  description: string | null;
  reference: string | null;
  counterparty: string | null;
};

/** Monzo counts in pence and this CRM counts in pounds. */
export function toRow(userId: string, transaction: MonzoTransaction): PaymentRow {
  const counterparty =
    transaction.counterparty?.preferred_name ?? transaction.counterparty?.name ?? null;
  return {
    user_id: userId,
    provider_transaction_id: transaction.id,
    posted_at: transaction.created,
    // Pending transactions carry an empty string rather than no field at all.
    settled_at: transaction.settled ? transaction.settled : null,
    amount: transaction.amount / 100,
    currency: transaction.currency ?? "GBP",
    description: transaction.description ?? null,
    // What the payer typed lands in one of these two depending on the payment
    // scheme; the matcher reads both and the description as well.
    reference: transaction.notes || transaction.metadata?.notes || null,
    counterparty,
  };
}

/**
 * Stores whatever is new and leaves everything else alone.
 *
 * Insert-only on purpose: a row that is already here may have been reviewed,
 * and re-syncing the same fortnight must never quietly undo somebody's
 * decision. Monzo also re-sends webhooks it is unsure about, so arriving twice
 * is normal rather than exceptional.
 *
 * @returns how many payments were new.
 */
export async function storePayments(
  supabase: SupabaseClient,
  userId: string,
  transactions: MonzoTransaction[],
): Promise<number> {
  const rows = transactions.filter(isIncomingPayment).map((t) => toRow(userId, t));
  if (rows.length === 0) return 0;

  const { data, error } = await supabase
    .from("bank_transactions")
    .upsert(rows, {
      onConflict: "user_id,provider_transaction_id",
      ignoreDuplicates: true,
    })
    .select("id");
  if (error) throw new Error(`Could not store payments: ${error.message}`);
  return data?.length ?? 0;
}

/**
 * Gives every unreviewed payment its best guess at an invoice.
 *
 * Run after storing, and again whenever the answer might have changed — a new
 * invoice makes an old unexplained payment suddenly obvious. Only untouched
 * rows are rewritten, so a suggestion never argues with a decision.
 */
export async function suggestMatches(supabase: SupabaseClient, userId: string): Promise<void> {
  const { data: pending, error: pendingError } = await supabase
    .from("bank_transactions")
    .select("id, amount, reference, description, counterparty")
    .eq("user_id", userId)
    .is("reviewed_at", null)
    .is("invoice_id", null)
    .eq("ignored", false);
  if (pendingError) throw new Error(`Could not read payments: ${pendingError.message}`);
  if (!pending || pending.length === 0) return;

  const { data: invoices, error: invoiceError } = await supabase
    .from("invoices")
    .select("id, number, total, clients(name)")
    .eq("user_id", userId)
    .neq("status", "paid");
  if (invoiceError) throw new Error(`Could not read invoices: ${invoiceError.message}`);

  const candidates: MatchCandidate[] = (invoices ?? []).map((invoice: Record<string, unknown>) => ({
    id: invoice.id as string,
    number: invoice.number as string,
    total: Number(invoice.total ?? 0),
    clientName: (invoice.clients as { name?: string } | null)?.name ?? null,
  }));

  for (const payment of pending) {
    const match = suggestMatch(
      {
        amount: Number(payment.amount ?? 0),
        reference: payment.reference,
        description: payment.description,
        counterparty: payment.counterparty,
      },
      candidates,
    );
    await supabase
      .from("bank_transactions")
      .update({
        suggested_invoice_id: match?.invoiceId ?? null,
        match_reason: match?.reason ?? null,
      })
      .eq("id", payment.id)
      // Belt and braces against a review landing between the read and the write.
      .is("reviewed_at", null)
      .is("invoice_id", null);
  }
}
