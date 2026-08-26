// Everything Settings needs to manage the Monzo connection except the OAuth
// round trip itself, which is monzo-callback.
//
// It runs here rather than on the phone because bank_connections holds live
// access tokens and is readable by service_role alone. Nothing on this path
// ever returns a token — only whether there is one and what it is attached to.
import { requireUser, adminClient } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { createState } from "../_shared/oauth-state.ts";
import {
  authorizeUrl,
  deleteWebhook,
  freshAccessToken,
  listAccounts,
  monzoConfigured,
} from "../_shared/monzo.ts";

type Action = "status" | "start" | "accounts" | "select" | "disconnect";

const CONNECTION_FIELDS =
  "id, provider, account_id, account_name, account_type, approved_at, last_synced_at, last_sync_error, webhook_id, access_token, refresh_token, expires_at";

/** Monzo documents the developer API against personal accounts only. */
function isRetail(accountType: string | null | undefined): boolean {
  return (accountType ?? "").startsWith("uk_retail");
}

Deno.serve(
  serveJson(async (request) => {
    const { userId } = await requireUser(request);
    const { action = "status", accountId } = (await request.json().catch(() => ({}))) as {
      action?: Action;
      accountId?: string;
    };

    const supabase = adminClient();
    const { data: connection } = await supabase
      .from("bank_connections")
      .select(CONNECTION_FIELDS)
      .eq("user_id", userId)
      .maybeSingle();

    switch (action) {
      case "status":
        return json({
          // Told apart deliberately: "nobody has set this up on the server" and
          // "you have not connected your account yet" need different answers.
          configured: monzoConfigured(),
          connected: Boolean(connection),
          accountName: connection?.account_name ?? null,
          accountType: connection?.account_type ?? null,
          // Monzo will not name the accounts a connection covers until access
          // is approved in the phone app, so a connection can exist with the
          // tokens saved and no account picked yet. The next sync finishes it.
          accountChosen: Boolean(connection?.account_id),
          // Monzo will not serve transactions until this is approved in the app.
          approved: Boolean(connection?.approved_at),
          // The developer API is documented for personal accounts. A business
          // account may simply not be there — say so rather than looking broken.
          businessAccount: Boolean(connection) && !isRetail(connection?.account_type),
          lastSyncedAt: connection?.last_synced_at ?? null,
          lastSyncError: connection?.last_sync_error ?? null,
          webhookRegistered: Boolean(connection?.webhook_id),
        });

      case "start": {
        if (!monzoConfigured()) {
          throw new Error(
            "Monzo is not set up on the server yet. Add MONZO_CLIENT_ID and MONZO_CLIENT_SECRET to the Edge Function secrets.",
          );
        }
        // The client secret doubles as the signing key: it is already required
        // for the flow, already present in both functions, and never leaves them.
        const state = await createState(userId, Deno.env.get("MONZO_CLIENT_SECRET") ?? "");
        return json({ url: authorizeUrl(state) });
      }

      case "accounts": {
        if (!connection) throw new Error("Monzo is not connected");
        const token = await freshAccessToken(connection, supabase);
        const accounts = await listAccounts(token);
        return json({
          accounts: accounts.map((account) => ({
            id: account.id,
            name: account.description ?? account.id,
            type: account.type ?? null,
            selected: account.id === connection.account_id,
          })),
        });
      }

      case "select": {
        if (!connection) throw new Error("Monzo is not connected");
        if (!accountId) throw new Error("An account is required");
        const token = await freshAccessToken(connection, supabase);
        const accounts = await listAccounts(token);
        const chosen = accounts.find((account) => account.id === accountId);
        if (!chosen) throw new Error("That account is not on this Monzo connection");

        // The old webhook watches the old account, so it goes with it.
        if (connection.webhook_id) {
          try {
            await deleteWebhook(token, connection.webhook_id);
          } catch (error) {
            console.error("Could not remove the previous webhook:", error);
          }
        }
        await supabase
          .from("bank_connections")
          .update({
            account_id: chosen.id,
            account_name: chosen.description ?? chosen.id,
            account_type: chosen.type ?? null,
            webhook_id: null,
            last_synced_at: null,
            last_sync_error: null,
          })
          .eq("id", connection.id);
        // monzo-sync registers the webhook for whichever account is current.
        return json({ ok: true, accountName: chosen.description ?? chosen.id });
      }

      case "disconnect": {
        if (!connection) return json({ ok: true });
        // Take the webhook down first: a webhook left behind would keep posting
        // to an endpoint that no longer knows what to do with it.
        if (connection.webhook_id) {
          try {
            const token = await freshAccessToken(connection, supabase);
            await deleteWebhook(token, connection.webhook_id);
          } catch (error) {
            // Revoked or expired tokens cannot delete anything, and that must
            // not trap the user in a connection they have asked to be rid of.
            console.error("Could not remove the Monzo webhook:", error);
          }
        }
        await supabase.from("bank_connections").delete().eq("id", connection.id);
        // Payments already reviewed stay: they are the record of how invoices
        // came to be marked paid, and they are no longer Monzo's to take away.
        return json({ ok: true });
      }

      default:
        throw new Error(`Unknown action: ${action}`);
    }
  }),
);
