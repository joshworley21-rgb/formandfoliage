// Calling Supabase Edge Functions from the app.
//
// Lifted out of invoicing.ts once a second area (the Monzo bank feed) needed
// the same two things: a call that throws on failure, and an error message a
// person can act on.
import { FunctionsHttpError } from "@supabase/supabase-js";
import { supabase } from "@/integrations/supabase/client";

/**
 * Edge Functions signal failure with a non-2xx status and a { error } body, but
 * supabase-js surfaces only a generic message. Dig the real one out so the user
 * sees "This client has no email address saved" rather than "non-2xx status".
 */
export async function readError(error: unknown): Promise<string> {
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

export async function invoke<T>(name: string, body: Record<string, unknown> = {}): Promise<T> {
  const { data, error } = await supabase.functions.invoke<T>(name, { body });
  if (error) throw new Error(await readError(error));
  return data as T;
}
