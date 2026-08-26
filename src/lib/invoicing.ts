// Client-side entry points for the invoicing Edge Functions.
//
// These used to be TanStack Start server functions (src/lib/invoicing.functions.ts).
// They now call Supabase Edge Functions instead, so the app needs no server of
// its own and can be packaged as a standalone Android app.
//
// The argument shape ({ data }) is kept from the server-function era so call
// sites read the same.
import { invoke } from "@/lib/functions";

export type CreateInvoiceInput = {
  clientId: string;
  visitIds: string[];
  retainerDescription?: string | null;
  retainerAmount?: number;
  notes?: string | null;
  dueInDays?: number;
  dueDate?: string | null;
};

export type CreateInvoiceResult = { invoiceId: string; number: string; total: number };

export function createInvoice({ data }: { data: CreateInvoiceInput }) {
  return invoke<CreateInvoiceResult>("create-invoice", data);
}

export type SendInvoiceInput = {
  invoiceId: string;
  pdfBase64: string;
  message?: string | null;
};

export type SendInvoiceResult = { sent: boolean; to?: string; reason?: string };

export function sendInvoiceEmail({ data }: { data: SendInvoiceInput }) {
  return invoke<SendInvoiceResult>("send-invoice-email", data);
}

export function getResendStatus() {
  return invoke<{ connected: boolean }>("email-status");
}

export type SendTestEmailInput = {
  to: string;
  subjectTemplate?: string | null;
  bodyTemplate?: string | null;
};

export type SendTestEmailResult = {
  sent: boolean;
  to?: string;
  subject?: string;
  reason?: string;
};

export function sendTestEmail({ data }: { data: SendTestEmailInput }) {
  return invoke<SendTestEmailResult>("send-test-email", data);
}

export type EnquiryInput = {
  key: string;
  name: string;
  email?: string;
  phone?: string;
  address?: string;
  message?: string;
  source?: string;
  website?: string;
};

/** Public — used by the customer-facing enquiry form, signed in or not. */
export function submitEnquiry(data: EnquiryInput) {
  return invoke<{ ok: true }>("public-enquiry", data);
}

export type SendQuoteInput = {
  quoteId: string;
  /** Public origin the accept link should point at. */
  origin: string;
  message?: string | null;
};

export function sendQuoteEmail({ data }: { data: SendQuoteInput }) {
  return invoke<{ sent: boolean; to?: string; reason?: string }>("send-quote-email", data);
}

export type PublicQuoteView = {
  number: string;
  contactName: string;
  contactAddress: string | null;
  issueDate: string;
  validUntil: string | null;
  subtotal: number;
  vatAmount: number;
  total: number;
  status: "sent" | "accepted" | "declined" | "expired";
  expired: boolean;
  notes: string | null;
  terms: string | null;
  vatRegistered: boolean;
  business: { name: string; email: string | null; phone: string | null };
  items: Array<{ description: string; quantity: number; unitPrice: number; amount: number }>;
};

/** Public — the customer's accept page, which nobody is signed in on. */
export function fetchPublicQuote(key: string) {
  return invoke<{ quote: PublicQuoteView }>("public-quote", { action: "get", key });
}

export function decidePublicQuote(input: {
  key: string;
  decision: "accepted" | "declined";
  reason?: string | null;
}) {
  return invoke<{ ok: true; status: "accepted" | "declined" }>("public-quote", {
    action: "decide",
    ...input,
  });
}

// --------------------------------------------------------------- Address ---

export type AddressSuggestion = {
  /** Pass back to resolveAddress when address is null. */
  id: string | null;
  label: string;
  address: string | null;
};

export type AddressSearchResult = {
  provider: "getaddress" | "photon";
  suggestions: AddressSuggestion[];
};

export function searchAddresses(query: string) {
  return invoke<AddressSearchResult>("address-lookup", { query });
}

/** Only needed for suggestions that arrive without an address of their own. */
export function resolveAddress(id: string, postcode?: string) {
  return invoke<{ provider: string; address: string }>("address-lookup", {
    resolve: id,
    ...(postcode ? { postcode } : {}),
  });
}
