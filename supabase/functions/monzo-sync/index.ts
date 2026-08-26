// Pulls recent payments from Monzo and works out what each one might pay for.
//
// The webhook is what makes a payment appear straight away; this is what makes
// the feature reliable. Webhooks get missed — a deploy, an outage, a webhook
// that never registered because approval had not been given yet — and this
// closes the gap by asking for the window Monzo will still serve.
//
// Called two ways: by the app when someone taps Sync now, and by the nightly
// cron with the service-role key, which sweeps every connection.
import { requireUser, adminClient, type SupabaseClient } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { storePayments, suggestMatches } from "../_shared/reconcile.ts";
import {
  freshAccessToken,
  HISTORY_DAYS,
  isAwaitingApproval,
  listTransactions,
  registerWebhook,
} from "../_shared/monzo.ts";

/** Re-reads a day either side of where we left off, in case anything settled late. */
const OVERLAP_MS = 24 * 60 * 60 * 1000;

type Connection = {
  id: string;
  user_id: string;
  account_id: string | null;
  access_token: string;
  refresh_token: string | null;
  expires_at: string | null;
  last_synced_at: string | null;
  webhook_id: string | null;
  webhook_secret: string | null;
};

type SyncResult = {
  added: number;
  awaitingApproval: boolean;
  error: string | null;
};

/**
 * Monzo serves the last 90 days to a settled token and no more, so there is no
 * point asking for anything older however long the CRM has been quiet.
 */
function windowStart(connection: Connection): Date {
  const earliest = Date.now() - HISTORY_DAYS * 24 * 60 * 60 * 1000;
  const resume = connection.last_synced_at
    ? Date.parse(connection.last_synced_at) - OVERLAP_MS
    : earliest;
  return new Date(Math.max(resume, earliest));
}

async function syncOne(supabase: SupabaseClient, connection: Connection): Promise<SyncResult> {
  if (!connection.account_id) {
    return { added: 0, awaitingApproval: false, error: "No Monzo account is chosen yet" };
  }

  try {
    const token = await freshAccessToken(connection, supabase);
    const transactions = await listTransactions(
      token,
      connection.account_id,
      windowStart(connection),
    );
    const added = await storePayments(supabase, connection.user_id, transactions);
    await suggestMatches(supabase, connection.user_id);

    // Reaching this line means Monzo is answering, so approval has happened —
    // and it is also the moment a webhook that could not be registered before
    // will now succeed.
    let webhookId = connection.webhook_id;
    if (!webhookId && connection.webhook_secret) {
      try {
        webhookId = await registerWebhook(
          token,
          connection.account_id,
          `${Deno.env.get("SUPABASE_URL")}/functions/v1/monzo-webhook?token=${connection.webhook_secret}`,
        );
      } catch (error) {
        console.error("Could not register the Monzo webhook:", error);
      }
    }

    await supabase
      .from("bank_connections")
      .update({
        last_synced_at: new Date().toISOString(),
        last_sync_error: null,
        approved_at: new Date().toISOString(),
        webhook_id: webhookId,
      })
      .eq("id", connection.id);

    return { added, awaitingApproval: false, error: null };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    const awaiting = isAwaitingApproval(message);
    await supabase
      .from("bank_connections")
      .update({
        last_sync_error: awaiting
          ? "Waiting for you to approve access in the Monzo app"
          : message.slice(0, 500),
        // Approval can be withdrawn in the Monzo app as easily as it is given.
        ...(awaiting ? { approved_at: null } : {}),
      })
      .eq("id", connection.id);
    return { added: 0, awaitingApproval: awaiting, error: message };
  }
}

const SELECT =
  "id, user_id, account_id, access_token, refresh_token, expires_at, last_synced_at, webhook_id, webhook_secret";

Deno.serve(
  serveJson(async (request) => {
    const presented =
      request.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ??
      request.headers.get("apikey") ??
      "";
    const isScheduledRun = presented === Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = adminClient();

    if (isScheduledRun) {
      const { data: connections } = await supabase
        .from("bank_connections")
        .select(SELECT)
        .eq("provider", "monzo");
      let added = 0;
      for (const connection of (connections ?? []) as Connection[]) {
        const result = await syncOne(supabase, connection);
        added += result.added;
        if (result.error) console.error(`Sync failed for ${connection.user_id}:`, result.error);
      }
      return json({ connections: connections?.length ?? 0, added });
    }

    const { userId } = await requireUser(request);
    const { data: connection } = await supabase
      .from("bank_connections")
      .select(SELECT)
      .eq("user_id", userId)
      .maybeSingle();
    if (!connection) throw new Error("Monzo is not connected");

    const result = await syncOne(supabase, connection as Connection);
    if (result.awaitingApproval) {
      return json({
        added: 0,
        awaitingApproval: true,
        reason:
          "Monzo is holding back your transactions until you approve access in the Monzo app.",
      });
    }
    if (result.error) throw new Error(result.error);
    return json({ added: result.added, awaitingApproval: false });
  }),
);
