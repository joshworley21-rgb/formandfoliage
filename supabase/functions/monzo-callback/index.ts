// Where Monzo sends the browser back to after the account holder has said yes.
//
// This is the one function in the set that answers a person rather than the
// app, so it replies with a page instead of JSON. It is public by necessity —
// the browser arrives with no session — which is what the signed `state` is
// for: it carries the user id and proves this callback started the flow.
import { adminClient } from "../_shared/supabase.ts";
import { readState } from "../_shared/oauth-state.ts";
import {
  chooseAccount,
  exchangeCode,
  expiresAt,
  isAwaitingApproval,
  listAccounts,
  listTransactions,
  registerWebhook,
} from "../_shared/monzo.ts";

function randomSecret(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (character) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character] ??
      character,
  );
}

/**
 * A whole page rather than a redirect: on the phone this opens in the browser,
 * not the app, so there is nowhere to send anyone back to that would reliably
 * work. Saying what happened and letting them close the tab does.
 */
function page(title: string, body: string, backTo: string | null, status = 200): Response {
  const link = backTo
    ? `<p><a class="back" href="${escapeHtml(backTo)}">Back to the CRM</a></p>`
    : `<p class="muted">You can close this tab and return to the app.</p>`;
  return new Response(
    `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${escapeHtml(title)}</title>
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 1.5rem;
         font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif;
         background: Canvas; color: CanvasText; }
  main { max-width: 32rem; }
  h1 { font-size: 1.35rem; margin: 0 0 .75rem; }
  p { margin: 0 0 .75rem; }
  .muted { opacity: .7; font-size: .9rem; }
  .back { display: inline-block; margin-top: .5rem; padding: .6rem 1rem; border-radius: 999px;
          background: CanvasText; color: Canvas; text-decoration: none; }
</style>
</head>
<body><main><h1>${escapeHtml(title)}</h1>${body}${link}</main></body>
</html>`,
    { status, headers: { "Content-Type": "text/html; charset=utf-8" } },
  );
}

Deno.serve(async (request) => {
  const url = new URL(request.url);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const refused = url.searchParams.get("error");

  if (refused) {
    return page(
      "Monzo was not connected",
      `<p>Monzo reported: ${escapeHtml(refused)}.</p><p>Nothing has changed. You can try again from Settings.</p>`,
      null,
      400,
    );
  }
  if (!code || !state) {
    return page(
      "Something is missing",
      "<p>That link is incomplete. Start again from Settings.</p>",
      null,
      400,
    );
  }

  const verified = await readState(state, Deno.env.get("MONZO_CLIENT_SECRET") ?? "");
  if (!verified.ok) {
    // Either a stale link or someone else's, and there is no way to tell which.
    console.error("Rejected a Monzo callback:", verified.reason);
    return page("That link cannot be used", `<p>${escapeHtml(verified.reason)}.</p>`, null, 400);
  }
  const userId = verified.userId;

  const supabase = adminClient();
  const { data: settings } = await supabase
    .from("settings")
    .select("public_site_url")
    .eq("user_id", userId)
    .maybeSingle();
  const backTo = settings?.public_site_url ? `${settings.public_site_url}/settings` : null;

  try {
    let tokens;
    try {
      tokens = await exchangeCode(code);
    } catch (error) {
      // Monzo authorisation codes are single use. Spending one twice almost
      // always means this page was reopened, refreshed, or restored by the
      // browser — in which case the first visit already did the work, and
      // saying "could not be connected" would be alarming and wrong.
      const { data: existing } = await supabase
        .from("bank_connections")
        .select("account_name, approved_at")
        .eq("user_id", userId)
        .maybeSingle();
      if (existing) {
        return page(
          "Monzo is already connected",
          `<p>Connected to <strong>${escapeHtml(existing.account_name ?? "your account")}</strong>.</p>
           ${
             existing.approved_at
               ? "<p>Payments into this account will appear in the CRM for you to match against invoices.</p>"
               : "<p><strong>Open your Monzo app and approve access</strong> if you have not already — Monzo releases no transactions until you do.</p>"
           }
           <p class="muted">This link had already been used, which is why you are seeing this rather than the page from the first time.</p>`,
          backTo,
        );
      }
      throw error;
    }

    const accounts = await listAccounts(tokens.access_token);
    const account = chooseAccount(accounts);
    if (!account) {
      return page(
        "No account came back",
        `<p>Monzo authorised the connection but returned no open accounts for it.</p>
         <p>Invoices can still be marked paid by hand.</p>`,
        backTo,
        200,
      );
    }

    // Written before anything else is attempted: whatever follows, the tokens
    // are the part that cannot be obtained again without the whole round trip.
    const webhookSecret = randomSecret();
    const { error: saveError } = await supabase.from("bank_connections").upsert(
      {
        user_id: userId,
        provider: "monzo",
        access_token: tokens.access_token,
        refresh_token: tokens.refresh_token ?? null,
        expires_at: expiresAt(tokens),
        account_id: account.id,
        account_name: account.description ?? account.id,
        account_type: account.type ?? null,
        webhook_secret: webhookSecret,
        webhook_id: null,
        last_sync_error: null,
      },
      { onConflict: "user_id" },
    );
    if (saveError) throw new Error(saveError.message);

    // Does Monzo actually answer for this account yet? Reading one minute of
    // history is the cheapest way to find out, and its failure is the specific
    // "approve it in the app" case rather than a broken connection.
    let approved = false;
    let note = "";
    try {
      await listTransactions(tokens.access_token, account.id, new Date(Date.now() - 60_000));
      approved = true;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (!isAwaitingApproval(message)) note = message;
    }

    let webhookId: string | null = null;
    try {
      webhookId = await registerWebhook(
        tokens.access_token,
        account.id,
        `${Deno.env.get("SUPABASE_URL")}/functions/v1/monzo-webhook?token=${webhookSecret}`,
      );
    } catch (error) {
      // Not fatal: the sync still finds payments, just not the instant they land.
      console.error("Could not register the Monzo webhook:", error);
      note ||= error instanceof Error ? error.message : String(error);
    }

    await supabase
      .from("bank_connections")
      .update({
        webhook_id: webhookId,
        approved_at: approved ? new Date().toISOString() : null,
        last_sync_error: note || null,
      })
      .eq("user_id", userId);

    const accountLine = `<p>Connected to <strong>${escapeHtml(account.description ?? account.id)}</strong>${
      accounts.length > 1
        ? " — if that is the wrong one, pick another in Settings under Monzo."
        : "."
    }</p>`;

    if (!approved) {
      return page(
        "Almost there",
        `${accountLine}
         <p><strong>Open your Monzo app and approve access.</strong> Monzo will not release any
         transactions until you do — the connection is made but it cannot see anything yet.</p>
         <p class="muted">Then come back to Settings and tap Sync now.</p>`,
        backTo,
      );
    }

    return page(
      "Monzo connected",
      `${accountLine}
       <p>Payments into this account will now appear in the CRM for you to match against
       invoices. Nothing is marked paid until you confirm it.</p>`,
      backTo,
    );
  } catch (error) {
    console.error("Monzo callback failed:", error);
    const message = error instanceof Error ? error.message : String(error);
    // A spent code with no connection behind it means the round trip never
    // finished. Monzo's own wording for that is unreadable, and the way out is
    // not to reload this page — which spends the code again — but to start over.
    const spent = /bad_authorization_code/i.test(message);
    return page(
      "Monzo could not be connected",
      spent
        ? `<p>That sign-in link had already been used, and no connection was saved.</p>
           <p>Open Settings in the CRM and tap Connect Monzo to start again. Reloading this page
           will not work: each link can only be used once.</p>`
        : `<p>${escapeHtml(message)}</p>`,
      backTo,
      500,
    );
  }
});
