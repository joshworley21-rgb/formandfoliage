// Supabase clients for the Edge Functions.
//
// SUPABASE_URL, SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY are injected by
// the platform — they do not need to be set as secrets.
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";

export type { SupabaseClient };

function requireEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing environment variable: ${name}`);
  return value;
}

/** Service-role client. Bypasses RLS — never hand this to caller-supplied input. */
export function adminClient(): SupabaseClient {
  return createClient(requireEnv("SUPABASE_URL"), requireEnv("SUPABASE_SERVICE_ROLE_KEY"), {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

/** Client acting as the signed-in user, so every query is still subject to RLS. */
export function userClient(accessToken: string): SupabaseClient {
  return createClient(requireEnv("SUPABASE_URL"), requireEnv("SUPABASE_ANON_KEY"), {
    global: { headers: { Authorization: `Bearer ${accessToken}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export type Caller = { userId: string; token: string; supabase: SupabaseClient };

/**
 * Resolve the signed-in user from the request's bearer token. Mirrors the
 * requireSupabaseAuth middleware the server functions used to run.
 */
export async function requireUser(request: Request): Promise<Caller> {
  const authHeader = request.headers.get("authorization");
  if (!authHeader) throw new Error("Unauthorized: no authorization header provided");
  if (!authHeader.startsWith("Bearer "))
    throw new Error("Unauthorized: only Bearer tokens are supported");

  const token = authHeader.slice("Bearer ".length).trim();
  if (!token || token.split(".").length !== 3) throw new Error("Unauthorized: invalid token");

  const supabase = userClient(token);
  const { data, error } = await supabase.auth.getUser(token);
  if (error || !data?.user?.id) throw new Error("Unauthorized: invalid token");

  return { userId: data.user.id, token, supabase };
}

/**
 * Guard for functions that only trusted schedulers may call (deployed with
 * verify_jwt = false). The caller must present the service-role key.
 */
export function requireServiceRole(request: Request): void {
  const presented =
    request.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ??
    request.headers.get("apikey") ??
    "";
  const expected = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
  if (presented !== expected) throw new Error("Unauthorized");
}
