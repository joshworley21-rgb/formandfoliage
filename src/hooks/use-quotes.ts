import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { supabase } from "@/integrations/supabase/client";
import {
  lineAmount,
  quoteTotals,
  type Quote,
  type QuoteItem,
  type QuoteItemDraft,
} from "@/lib/quotes";
import type { Enquiry } from "@/hooks/use-enquiries";
import { currentUser } from "@/lib/session";
import { runOrQueue, type QueuedOp } from "@/lib/offline-queue";

export function useQuotes() {
  return useQuery({
    queryKey: ["quotes"],
    queryFn: async (): Promise<Quote[]> => {
      const { data, error } = await supabase
        .from("quotes")
        .select("*")
        .order("created_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useQuoteItems(quoteId?: string) {
  return useQuery({
    queryKey: ["quote_items", quoteId],
    enabled: Boolean(quoteId),
    queryFn: async (): Promise<QuoteItem[]> => {
      if (!quoteId) return [];
      const { data, error } = await supabase
        .from("quote_items")
        .select("*")
        .eq("quote_id", quoteId)
        .order("sort_order", { ascending: true });
      if (error) throw error;
      return data ?? [];
    },
  });
}

export type SaveQuoteInput = {
  id?: string;
  clientId?: string | null;
  enquiryId?: string | null;
  contactName: string;
  contactEmail?: string | null;
  contactPhone?: string | null;
  contactAddress?: string | null;
  validUntil?: string | null;
  notes?: string | null;
  terms?: string | null;
  items: QuoteItemDraft[];
};

/**
 * Create or update a quote and replace its lines.
 *
 * Lines are rewritten wholesale rather than diffed: a quote has a handful of
 * them, they are edited as one list, and this keeps ordering honest.
 */
export function useSaveQuote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: SaveQuoteInput): Promise<string> => {
      const authUser = await currentUser();
      const userId = authUser?.id;
      if (!userId) throw new Error("Not signed in");

      const { data: settings } = await supabase
        .from("settings")
        .select("vat_registered, vat_rate, quote_validity_days, quote_terms")
        .eq("user_id", userId)
        .maybeSingle();

      const totals = quoteTotals(input.items, {
        vatRegistered: settings?.vat_registered,
        vatRate: settings?.vat_rate,
      });

      const validUntil =
        input.validUntil ??
        new Date(Date.now() + (settings?.quote_validity_days ?? 30) * 86400000)
          .toISOString()
          .slice(0, 10);

      const fields = {
        client_id: input.clientId ?? null,
        enquiry_id: input.enquiryId ?? null,
        contact_name: input.contactName,
        contact_email: input.contactEmail ?? null,
        contact_phone: input.contactPhone ?? null,
        contact_address: input.contactAddress ?? null,
        valid_until: validUntil,
        notes: input.notes ?? null,
        terms: input.terms ?? settings?.quote_terms ?? null,
        subtotal: totals.subtotal,
        vat_amount: totals.vatAmount,
        total: totals.total,
        user_id: userId,
      };

      const quoteId = input.id ?? crypto.randomUUID();
      const ops: QueuedOp[] = [];

      if (input.id) {
        ops.push({ kind: "update", table: "quotes", payload: fields, match: { id: quoteId } });
        ops.push({ kind: "delete", table: "quote_items", match: { quote_id: quoteId } });
      } else {
        // Need next_quote_number for number, which is an RPC
        // This makes this non-atomic across RPC + Insert.
        // For now, accept the pattern established in the app.
        const { data: number, error: numberError } = await supabase.rpc("next_quote_number", {
          _user_id: userId,
        });
        if (numberError) throw numberError;
        ops.push({ kind: "insert", table: "quotes", payload: { ...fields, id: quoteId, number } });
      }

      const rows = input.items
        .filter((item) => item.description.trim() || lineAmount(item) !== 0)
        .map((item, index) => ({
          user_id: userId,
          quote_id: quoteId,
          description: item.description.trim() || "Garden work",
          quantity: Number(item.quantity) || 0,
          unit_price: Number(item.unit_price) || 0,
          amount: lineAmount(item),
          sort_order: index,
        }));

      if (rows.length > 0) {
        ops.push(...rows.map((row) => ({ kind: "insert" as const, table: "quote_items", payload: row })));
      }

      await runOrQueue(input.id ? "Quote update" : "New quote", ops);
      return quoteId;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["quotes"] });
      qc.invalidateQueries({ queryKey: ["quote_items"] });
    },
  });
}

export function useDeleteQuote() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await supabase.from("quotes").delete().eq("id", id);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["quotes"] }),
  });
}

/** Record a decision taken away from the accept link (over the phone, say). */
export function useSetQuoteStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, status }: { id: string; status: Quote["status"] }) => {
      const decided = status === "accepted" || status === "declined";
      const { error } = await supabase
        .from("quotes")
        .update({ status, decided_at: decided ? new Date().toISOString() : null })
        .eq("id", id);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["quotes"] }),
  });
}

/**
 * Turn an accepted quote into a client.
 *
 * Reuses the client the quote already points at when there is one, so accepting
 * a second quote for the same person does not create a duplicate. The enquiry it
 * came from, if any, is marked converted at the same time.
 */
export function useConvertQuoteToClient() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (quote: Quote): Promise<string> => {
      if (quote.client_id) return quote.client_id;

      const authUser = await currentUser();
      const userId = authUser?.id;
      if (!userId) throw new Error("Not signed in");

      const { data: client, error } = await supabase
        .from("clients")
        .insert({
          user_id: userId,
          name: quote.contact_name,
          email: quote.contact_email,
          phone: quote.contact_phone,
          address: quote.contact_address,
          notes: quote.notes,
        })
        .select("id")
        .single();
      if (error) throw error;

      const { error: linkError } = await supabase
        .from("quotes")
        .update({ client_id: client.id })
        .eq("id", quote.id);
      if (linkError) throw linkError;

      if (quote.enquiry_id) {
        await supabase
          .from("enquiries")
          .update({ status: "converted", client_id: client.id })
          .eq("id", quote.enquiry_id);
      }

      return client.id;
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

/** Prefill a new quote from an enquiry, so nothing is retyped. */
export function quoteDraftFromEnquiry(enquiry: Enquiry): SaveQuoteInput {
  return {
    enquiryId: enquiry.id,
    clientId: enquiry.client_id,
    contactName: enquiry.name,
    contactEmail: enquiry.email,
    contactPhone: enquiry.phone,
    contactAddress: enquiry.address,
    notes: enquiry.message,
    items: [{ description: "", quantity: 1, unit_price: 0 }],
  };
}
