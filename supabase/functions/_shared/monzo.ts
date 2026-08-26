// The bits of the Monzo developer API this CRM uses.
//
// Monzo's API is OAuth 2.0 with one wrinkle worth knowing before reading any of
// this: after the browser flow finishes, the account holder must also approve
// access in the Monzo phone app. Until they do, every transaction read comes
// back 403. A connection can therefore be entirely real and entirely useless,
// which is why approval has its own state rather than being assumed.
//
// The other wrinkle is time. Monzo lets a fresh token read the whole history,
// but five minutes after authorising it will only serve the last 90 days. There
// is no point pretending otherwise, so the sync asks for 90 days and stops.
//
// Docs: https://docs.monzo.com
import type { SupabaseClient } from "./supabase.ts";

const API = "https://api.monzo.com";
const AUTH = "https://auth.monzo.com";

/** Monzo stops serving anything older than this once a token has settled. */
export const HISTORY_DAYS = 90;

/** Refresh this long before expiry rather than racing it. */
const REFRESH_MARGIN_MS = 5 * 60 * 1000;

export type MonzoTokens = {
  access_token: string;
  refresh_token?: string;
  expires_in?: number;
  user_id?: string;
};

export type MonzoAccount = {
  id: string;
  description?: string;
  /** uk_retail, uk_retail_joint, uk_business, uk_rewards. */
  type?: string;
  /** "personal" or "business" — the plainer half of the same answer. */
  owner_type?: string;
  closed?: boolean;
  account_number?: string;
  sort_code?: string;
};

/**
 * Which account the bank feed should watch, out of everything Monzo returns.
 *
 * A real login came back with three: a personal current account first, a
 * rewards pot second, and the business account third — so taking the first was
 * simply wrong. Customers pay invoices into the business account, so that is
 * the default, and a rewards pot is never it. Whatever this picks can still be
 * changed in Settings; the point is that the common case needs no correcting.
 */
export function chooseAccount(accounts: MonzoAccount[]): MonzoAccount | null {
  const open = accounts.filter((account) => !account.closed);
  if (open.length === 0) return null;

  const isBusiness = (account: MonzoAccount) =>
    account.owner_type === "business" || (account.type ?? "").includes("business");
  // Savings and rewards pots take no payments in from customers.
  const isSpendable = (account: MonzoAccount) => !/rewards|savings/.test(account.type ?? "");

  return (
    open.find((account) => isBusiness(account) && isSpendable(account)) ??
    open.find((account) => (account.type ?? "").startsWith("uk_retail")) ??
    open.find(isSpendable) ??
    open[0]!
  );
}

export type MonzoTransaction = {
  id: string;
  /** Minor units. Positive is money in, negative is money out. */
  amount: number;
  currency: string;
  description?: string;
  notes?: string;
  created: string;
  settled?: string;
  decline_reason?: string;
  scheme?: string;
  metadata?: Record<string, string>;
  counterparty?: {
    name?: string;
    preferred_name?: string;
    sort_code?: string;
    account_number?: string;
  };
  merchant?: { name?: string } | string | null;
};

function requireEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing environment variable: ${name}`);
  return value;
}

export function monzoConfigured(): boolean {
  return Boolean(Deno.env.get("MONZO_CLIENT_ID") && Deno.env.get("MONZO_CLIENT_SECRET"));
}

/**
 * Where Monzo sends the browser back to. It has to match the redirect URI
 * registered on the Monzo developer client exactly, so it is derived from the
 * project URL rather than passed in by a caller who could get it wrong.
 */
export function redirectUri(): string {
  return `${requireEnv("SUPABASE_URL")}/functions/v1/monzo-callback`;
}

export function authorizeUrl(state: string): string {
  const query = new URLSearchParams({
    client_id: requireEnv("MONZO_CLIENT_ID"),
    redirect_uri: redirectUri(),
    response_type: "code",
    state,
  });
  return `${AUTH}/?${query.toString()}`;
}

/** Monzo answers errors as JSON with a code and message; surface those. */
async function readMonzoError(response: Response): Promise<string> {
  const body = await response.text();
  try {
    const parsed = JSON.parse(body) as { code?: string; message?: string };
    if (parsed.message) return `${parsed.message}${parsed.code ? ` (${parsed.code})` : ""}`;
  } catch {
    // Not JSON — the raw body is the best on offer.
  }
  return body.slice(0, 300) || `Monzo returned ${response.status}`;
}

/** True when Monzo is refusing because nobody has approved access in the app yet. */
export function isAwaitingApproval(message: string): boolean {
  return /forbidden\.insufficient_permissions|insufficient permissions|verify.*identity/i.test(
    message,
  );
}

async function monzoGet<T>(
  token: string,
  path: string,
  query: Record<string, string> = {},
): Promise<T> {
  const url = new URL(`${API}${path}`);
  for (const [key, value] of Object.entries(query)) url.searchParams.set(key, value);
  const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) throw new Error(await readMonzoError(response));
  return (await response.json()) as T;
}

async function tokenRequest(form: Record<string, string>): Promise<MonzoTokens> {
  const response = await fetch(`${API}/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(form).toString(),
  });
  if (!response.ok) throw new Error(await readMonzoError(response));
  return (await response.json()) as MonzoTokens;
}

export function exchangeCode(code: string): Promise<MonzoTokens> {
  return tokenRequest({
    grant_type: "authorization_code",
    client_id: requireEnv("MONZO_CLIENT_ID"),
    client_secret: requireEnv("MONZO_CLIENT_SECRET"),
    redirect_uri: redirectUri(),
    code,
  });
}

/**
 * Only confidential clients get refresh tokens, so the Monzo client must be
 * registered as one. Without that the connection dies after a few hours and the
 * only fix is to reconnect by hand.
 */
export function refreshTokens(refreshToken: string): Promise<MonzoTokens> {
  return tokenRequest({
    grant_type: "refresh_token",
    client_id: requireEnv("MONZO_CLIENT_ID"),
    client_secret: requireEnv("MONZO_CLIENT_SECRET"),
    refresh_token: refreshToken,
  });
}

export function expiresAt(tokens: MonzoTokens): string | null {
  if (!tokens.expires_in) return null;
  return new Date(Date.now() + tokens.expires_in * 1000).toISOString();
}

export type StoredConnection = {
  id: string;
  access_token: string;
  refresh_token: string | null;
  expires_at: string | null;
};

/**
 * The access token to use right now, refreshed and written back if it is close
 * to expiry. Refreshing invalidates the previous token, so the new pair has to
 * be stored before it is used anywhere else.
 */
export async function freshAccessToken(
  connection: StoredConnection,
  supabase: SupabaseClient,
): Promise<string> {
  const expiry = connection.expires_at ? Date.parse(connection.expires_at) : null;
  const stillGood = expiry === null || expiry - Date.now() > REFRESH_MARGIN_MS;
  if (stillGood) return connection.access_token;

  if (!connection.refresh_token) {
    throw new Error(
      "The Monzo connection has expired and there is no refresh token to renew it. Reconnect Monzo in Settings.",
    );
  }

  const tokens = await refreshTokens(connection.refresh_token);
  await supabase
    .from("bank_connections")
    .update({
      access_token: tokens.access_token,
      refresh_token: tokens.refresh_token ?? connection.refresh_token,
      expires_at: expiresAt(tokens),
    })
    .eq("id", connection.id);
  return tokens.access_token;
}

export async function listAccounts(token: string): Promise<MonzoAccount[]> {
  const { accounts } = await monzoGet<{ accounts: MonzoAccount[] }>(token, "/accounts");
  return (accounts ?? []).filter((account) => !account.closed);
}

export async function listTransactions(
  token: string,
  accountId: string,
  since: Date,
): Promise<MonzoTransaction[]> {
  const { transactions } = await monzoGet<{ transactions: MonzoTransaction[] }>(
    token,
    "/transactions",
    { account_id: accountId, since: since.toISOString(), limit: "100" },
  );
  return transactions ?? [];
}

export async function getTransaction(token: string, id: string): Promise<MonzoTransaction> {
  const { transaction } = await monzoGet<{ transaction: MonzoTransaction }>(
    token,
    `/transactions/${encodeURIComponent(id)}`,
  );
  return transaction;
}

export async function registerWebhook(
  token: string,
  accountId: string,
  url: string,
): Promise<string> {
  const response = await fetch(`${API}/webhooks`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({ account_id: accountId, url }).toString(),
  });
  if (!response.ok) throw new Error(await readMonzoError(response));
  const body = (await response.json()) as { webhook?: { id?: string } };
  const id = body.webhook?.id;
  if (!id) throw new Error("Monzo registered the webhook but did not return its id");
  return id;
}

export async function deleteWebhook(token: string, webhookId: string): Promise<void> {
  const response = await fetch(`${API}/webhooks/${encodeURIComponent(webhookId)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  // A webhook that is already gone is the outcome we wanted.
  if (!response.ok && response.status !== 404) throw new Error(await readMonzoError(response));
}
