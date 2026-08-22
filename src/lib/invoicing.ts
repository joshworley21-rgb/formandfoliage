// Client-side entry points for the invoicing Edge Functions.
//
// These used to be TanStack Start server functions (src/lib/invoicing.functions.ts).
// They now call Supabase Edge Functions instead, so the app needs no server of
// its own and can be packaged as a standalone Android app.
//
// The argument shape ({ data }) is kept from the server-function era so call
// sites read the same.
import { FunctionsHttpError } from "@supabase/supabase-js";
import { supabase } from "@/integrations/supabase/client";

/**
 * Edge Functions signal failure with a non-2xx status and a { error } body, but
 * supabase-js surfaces only a generic message. Dig the real one out so the user
 * sees "This client has no email address saved" rather than "non-2xx status".
 */
async function readError(error: unknown): Promise<string> {
  if (error instanceof FunctionsHttpError) {
    try {
      const payload = (await error.context.json()) as { error?: string } | null;
      if (payload?.error) return payload.error;
    } catch {
      // Body was not JSON — fall through to the generic message.
    }
  }
  return error instanceof Error ? error.message : String(error);
}

async function invoke<T>(name: string, body: Record<string, unknown> = {}): Promise<T> {
  const { data, error } = await supabase.functions.invoke<T>(name, { body });
  if (error) throw new Error(await readError(error));
  return data as T;
}

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
