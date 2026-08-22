import type { Database } from "@/integrations/supabase/types";

export type Quote = Database["public"]["Tables"]["quotes"]["Row"];
export type QuoteItem = Database["public"]["Tables"]["quote_items"]["Row"];
export type QuoteStatus = Database["public"]["Enums"]["quote_status"];

export const QUOTE_STATUS_LABELS: Record<QuoteStatus, string> = {
  draft: "Draft",
  sent: "Sent",
  accepted: "Accepted",
  declined: "Declined",
  expired: "Expired",
};

/** A line as it is being edited, before it has been saved and given an id. */
export type QuoteItemDraft = {
  id?: string;
  description: string;
  quantity: number;
  unit_price: number;
};

export function lineAmount(item: Pick<QuoteItemDraft, "quantity" | "unit_price">): number {
  const amount = (Number(item.quantity) || 0) * (Number(item.unit_price) || 0);
  return Math.round(amount * 100) / 100;
}

export type QuoteTotals = { subtotal: number; vatAmount: number; total: number };

/** Price a whole quote. VAT only applies when the business is VAT registered. */
export function quoteTotals(
  items: Array<Pick<QuoteItemDraft, "quantity" | "unit_price">>,
  options: {
    vatRegistered?: boolean | null | undefined;
    vatRate?: number | string | null | undefined;
  } = {},
): QuoteTotals {
  const subtotal = Math.round(items.reduce((sum, item) => sum + lineAmount(item), 0) * 100) / 100;
  const rate = options.vatRegistered ? Number(options.vatRate ?? 0) : 0;
  const vatAmount = Math.round(subtotal * (rate / 100) * 100) / 100;
  const total = Math.round((subtotal + vatAmount) * 100) / 100;
  return { subtotal, vatAmount, total };
}

/**
 * A quote is past its date when valid_until has gone by. Stored status is left
 * alone — this is what the UI shows, so an untouched quote still reads as
 * expired without a job having to sweep the table.
 */
export function isExpired(quote: Pick<Quote, "valid_until" | "status">): boolean {
  if (quote.status !== "sent" || !quote.valid_until) return false;
  return quote.valid_until < new Date().toISOString().slice(0, 10);
}

export function displayStatus(quote: Pick<Quote, "valid_until" | "status">): QuoteStatus {
  return isExpired(quote) ? "expired" : quote.status;
}

/** Where a customer goes to accept or decline. */
export function quoteLink(publicKey: string, origin: string): string {
  return `${origin.replace(/\/$/, "")}/quote/${publicKey}`;
}
