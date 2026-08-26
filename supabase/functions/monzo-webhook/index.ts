// Monzo posts here the moment something happens on the account.
//
// Unlike Resend, Monzo does not sign its webhooks — there is no signature to
// check, so the endpoint has to defend itself twice over:
//
//  1. The URL carries a 32-byte random token, generated when the account was
//     connected and known only to Monzo and this database. No token, no entry.
//  2. Even with the right token, the posted body is not believed. Only the
//     transaction id is taken from it, and the transaction is then read back
//     from the Monzo API using our own stored credentials. A forged post can
//     therefore invent nothing: at most it asks us to look up a real payment.
//
// Everything it records still waits for a person to confirm it, so the worst a
// determined forger achieves is a line in a review list.
import { adminClient } from "../_shared/supabase.ts";
import { storePayments, suggestMatches } from "../_shared/reconcile.ts";
import { freshAccessToken, getTransaction } from "../_shared/monzo.ts";

type WebhookBody = {
  type?: string;
  data?: { id?: string; account_id?: string; amount?: number };
};

/** Monzo retries anything that is not a 2xx, so "understood, nothing to do" is 200. */
const ACKNOWLEDGED = new Response("ok", { status: 200 });

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });

  const token = new URL(request.url).searchParams.get("token");
  if (!token) return new Response("Unauthorized", { status: 401 });

  const supabase = adminClient();
  const { data: connection } = await supabase
    .from("bank_connections")
    .select("id, user_id, account_id, access_token, refresh_token, expires_at")
    .eq("webhook_secret", token)
    .maybeSingle();
  // A token that matches nothing is either an old webhook Monzo has not
  // forgotten or someone guessing. Both get the same answer.
  if (!connection) return new Response("Unauthorized", { status: 401 });

  let body: WebhookBody;
  try {
    body = (await request.json()) as WebhookBody;
  } catch {
    return new Response("Body is not JSON", { status: 400 });
  }

  if (body.type !== "transaction.created") return ACKNOWLEDGED;
  const transactionId = body.data?.id;
  if (!transactionId) return ACKNOWLEDGED;

  // Card spending fires this hook far more often than payments in do. The
  // posted amount is not trusted for anything except skipping that noise before
  // spending an API call on it.
  if (typeof body.data?.amount === "number" && body.data.amount <= 0) return ACKNOWLEDGED;

  // A webhook for an account this connection no longer watches is not ours —
  // worth knowing before spending an API call on it.
  if (
    connection.account_id &&
    body.data?.account_id &&
    body.data.account_id !== connection.account_id
  ) {
    return ACKNOWLEDGED;
  }

  try {
    const accessToken = await freshAccessToken(connection, supabase);
    // The one call that matters: whatever was posted, this is what Monzo says.
    const transaction = await getTransaction(accessToken, transactionId);

    const added = await storePayments(supabase, connection.user_id, [transaction]);
    if (added > 0) await suggestMatches(supabase, connection.user_id);
  } catch (error) {
    // Retrying will not help if Monzo is refusing us, and a 500 here would have
    // Monzo hammering the endpoint five times over. Record it and accept.
    const message = error instanceof Error ? error.message : String(error);
    console.error("Monzo webhook could not be processed:", message);
    await supabase
      .from("bank_connections")
      .update({ last_sync_error: message.slice(0, 500) })
      .eq("id", connection.id);
  }

  return ACKNOWLEDGED;
});
