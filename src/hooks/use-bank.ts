// Payments that have arrived in the bank and what the app does with them.
//
// The rows themselves are read straight from bank_transactions under RLS — they
// are ordinary data, unlike the connection behind them. Confirming a match is
// two writes (the payment points at an invoice, the invoice becomes paid) and
// goes through the offline queue like every other change, so it survives being
// tapped in a garden with no signal.
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { supabase } from "@/integrations/supabase/client";
import { runOrQueue } from "@/lib/offline-queue";
import {
  disconnectMonzo,
  getMonzoStatus,
  listMonzoAccounts,
  selectMonzoAccount,
  startMonzoConnection,
  syncMonzo,
} from "@/lib/monzo";
import type { Database } from "@/integrations/supabase/types";

export type BankTransaction = Database["public"]["Tables"]["bank_transactions"]["Row"];

/**
 * Money in that nobody has dealt with yet, newest first. Everything else in the
 * table is history: confirmed against an invoice, or dismissed.
 */
export function usePaymentsToReview() {
  return useQuery({
    queryKey: ["bank_transactions", "review"],
    queryFn: async (): Promise<BankTransaction[]> => {
      // No join to the suggested invoice: match_reason already names it in the
      // sentence shown, and the picker lists the invoices from useInvoices.
      const { data, error } = await supabase
        .from("bank_transactions")
        .select("*")
        .is("reviewed_at", null)
        .eq("ignored", false)
        .order("posted_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
  });
}

/**
 * This payment settles that invoice. The only place an invoice is marked paid
 * by the bank feed, and it takes a person tapping Confirm to get here.
 */
export function useConfirmPayment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      transactionId,
      invoiceId,
    }: {
      transactionId: string;
      invoiceId: string;
    }) => {
      const now = new Date().toISOString();
      await runOrQueue("Payment matched to invoice", [
        {
          kind: "update",
          table: "bank_transactions",
          payload: {
            invoice_id: invoiceId,
            matched_at: now,
            reviewed_at: now,
            // The guess has been answered; keeping it would only confuse the
            // record of what was actually decided.
            suggested_invoice_id: null,
          },
          match: { id: transactionId },
        },
        {
          kind: "update",
          table: "invoices",
          payload: { status: "paid", paid_at: now },
          match: { id: invoiceId },
        },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

/** Not an invoice payment at all — a transfer, a refund, someone paying you back. */
export function useIgnorePayment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (transactionId: string) => {
      await runOrQueue("Payment dismissed", [
        {
          kind: "update",
          table: "bank_transactions",
          payload: { ignored: true, reviewed_at: new Date().toISOString() },
          match: { id: transactionId },
        },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useMonzoStatus() {
  return useQuery({
    queryKey: ["monzo-status"],
    queryFn: getMonzoStatus,
    // Connecting happens in a browser tab, and approval happens in the Monzo
    // app. Coming back to this screen is the moment the answer may have changed.
    refetchOnWindowFocus: true,
  });
}

export function useConnectMonzo() {
  return useMutation({
    mutationFn: async () => {
      const { url } = await startMonzoConnection();
      return url;
    },
  });
}

export function useMonzoAccounts(enabled: boolean) {
  return useQuery({
    queryKey: ["monzo-accounts"],
    queryFn: listMonzoAccounts,
    enabled,
  });
}

export function useSelectMonzoAccount() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: selectMonzoAccount,
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useSyncMonzo() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: syncMonzo,
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDisconnectMonzo() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: disconnectMonzo,
    onSuccess: () => qc.invalidateQueries(),
  });
}
