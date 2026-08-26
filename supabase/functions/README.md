# Edge Functions

These replace the server functions and API routes the CRM used to run inside the
web app. Moving them here is what lets the app ship as a standalone Android app:
the phone talks to Supabase directly, and nothing needs a web server.

| Function             | Replaces                                                     | Who may call it                                           |
| -------------------- | ------------------------------------------------------------ | --------------------------------------------------------- |
| `create-invoice`     | `createInvoice` server fn                                    | signed-in user                                            |
| `send-invoice-email` | `sendInvoiceEmail` server fn                                 | signed-in user                                            |
| `email-status`       | `getResendStatus` server fn                                  | signed-in user                                            |
| `send-test-email`    | `sendTestEmail` server fn                                    | signed-in user                                            |
| `send-quote-email`   | new — emails a quote with its accept link                    | signed-in user                                            |
| `public-enquiry`     | `POST /api/public/enquiry`                                   | anyone (public form)                                      |
| `public-quote`       | new — customer views and answers a quote                     | anyone with the link                                      |
| `payment-reminders`  | `POST /api/public/hooks/payment-reminders`                   | scheduler only, via the service-role key                  |
| `monzo-connect`      | new — connects, inspects and disconnects the Monzo bank feed | signed-in user                                            |
| `monzo-callback`     | new — where Monzo returns the browser after authorising      | anyone (a browser mid-OAuth)                              |
| `monzo-sync`         | new — pulls recent payments and suggests matches             | signed-in user, or the scheduler via the service-role key |
| `monzo-webhook`      | new — Monzo posts payments here as they land                 | Monzo, via a secret token in the URL                      |

`public-quote` is how the customer's accept page reaches a quote: the `quotes`
table has no anon policy, so this function is the only way in and it returns
only the fields that page needs.

`create-invoice` has to run here rather than on the phone because
`next_invoice_number()` is granted to `service_role` only — invoice numbers must
not be skippable from the client. `send-invoice-email` and friends run here
because they hold the Resend API key.

The four `monzo-*` functions run here for the same reason: they hold live bank
access tokens. `bank_connections` is granted to `service_role` alone, so the app
can never read a token even if it wanted to — everything it knows about the
connection comes back through `monzo-connect`.

## One-time setup

Setting up a brand-new Supabase project? Follow `supabase/SETUP.md` instead —
it covers this along with everything else. These steps are for a project that
already exists.

```sh
# 1. Install the CLI (see supabase.com/docs/guides/cli for other platforms)
npm install -g supabase

# 2. Sign in and link this repo to the project
supabase login
supabase link --project-ref <your-project-ref>

# 3. Give the functions their secrets. SUPABASE_URL, SUPABASE_ANON_KEY and
#    SUPABASE_SERVICE_ROLE_KEY are provided by the platform automatically.
supabase secrets set RESEND_API_KEY=re_your_key_here

#    Only if you want the Monzo bank feed — see "Monzo bank feed" below.
supabase secrets set MONZO_CLIENT_ID=oauth2client_... MONZO_CLIENT_SECRET=mnzconf...
```

## Deploy

```sh
supabase functions deploy
```

`supabase/config.toml` already records which functions skip JWT verification, so
no extra flags are needed. To deploy a single one:

```sh
supabase functions deploy send-invoice-email
```

## Scheduling the payment reminders

The reminder sweep used to be a webhook someone had to call. Schedule it in the
database instead — run this once in the SQL editor, replacing the placeholder
with your service-role key:

```sql
create extension if not exists pg_cron;
create extension if not exists pg_net;

select cron.schedule(
  'payment-reminders',
  '0 8 * * *',  -- 08:00 UTC daily
  $$
  select net.http_post(
    url     := 'https://<project-ref>.supabase.co/functions/v1/payment-reminders',
    headers := jsonb_build_object(
      'Content-Type',  'application/json',
      'Authorization', 'Bearer YOUR_SERVICE_ROLE_KEY'
    ),
    body    := '{}'::jsonb
  );
  $$
);
```

The function refuses any caller that does not present the service-role key, so
the schedule is the only thing that can trigger a run.

## Monzo bank feed

Optional. Without it the CRM works exactly as before and invoices are marked
paid by hand; with it, payments into the bank appear in the app ready to be
matched against invoices. Nothing is ever marked paid automatically.

**1. Register an OAuth client** at
[developers.monzo.com](https://developers.monzo.com) (sign in with the same
account the business banks with):

- **Redirect URL**: `https://<project-ref>.supabase.co/functions/v1/monzo-callback`
- **Confidentiality**: **Confidential**. This is the one setting that cannot be
  wrong — only confidential clients are issued refresh tokens, and without one
  the connection dies after a few hours and has to be remade by hand.

**2. Set the secrets** and deploy:

```sh
supabase secrets set MONZO_CLIENT_ID=oauth2client_... MONZO_CLIENT_SECRET=mnzconf...
supabase functions deploy monzo-connect monzo-callback monzo-sync monzo-webhook
```

**3. Connect it** in the app under Settings → Monzo bank feed, then **approve
access in the Monzo phone app**. Monzo returns 403 for every transaction until
you do; Settings says so plainly while it is waiting.

**4. Schedule the sync**, so a missed webhook cannot leave a payment unseen:

```sql
select cron.schedule(
  'monzo-sync',
  '0 7 * * *',  -- 07:00 UTC daily
  $$
  select net.http_post(
    url     := 'https://<project-ref>.supabase.co/functions/v1/monzo-sync',
    headers := jsonb_build_object(
      'Content-Type',  'application/json',
      'Authorization', 'Bearer YOUR_SERVICE_ROLE_KEY'
    ),
    body    := '{}'::jsonb
  );
  $$
);
```

### Things worth knowing before you rely on it

- **Business accounts.** Monzo documents the developer API for personal accounts
  (`uk_retail`, `uk_retail_joint`), but a real login returns `uk_business`
  alongside them, so this works. It is undocumented rather than unsupported —
  worth knowing if it ever stops. Settings names the account it connected and
  explains this only if the connection gets stuck.
- **Which account gets picked.** A login returns everything: personal current
  account, rewards pot, business account, in that order. `chooseAccount` prefers
  the business account, then a personal current account, and never a rewards
  pot — taking the first would have watched the wrong one. Settings can change
  it either way.
- **90 days.** A freshly authorised token can read the whole history, but five
  minutes later Monzo will only serve the last 90 days. The sync asks for 90 days
  and no more, so anything older has to be reconciled by hand.
- **Monzo does not sign its webhooks.** `monzo-webhook` is protected two ways
  instead: the URL carries a 32-byte random token generated at connection time,
  and the posted body is never believed — only the transaction id is taken from
  it, and the transaction is then read back from the Monzo API. A forged post can
  invent nothing.
- **Read-only.** The connection can read transactions. It cannot move money.

## Checking a deploy

```sh
# Should report {"connected":true} once RESEND_API_KEY is set
curl -X POST https://<project-ref>.supabase.co/functions/v1/email-status \
  -H "Authorization: Bearer YOUR_USER_ACCESS_TOKEN"

# Reminder sweep, dry of any effect if nothing is due
curl -X POST https://<project-ref>.supabase.co/functions/v1/payment-reminders \
  -H "Authorization: Bearer YOUR_SERVICE_ROLE_KEY"

# Monzo, if it is set up: reports {"configured":true,...} and what is connected
curl -X POST https://<project-ref>.supabase.co/functions/v1/monzo-connect \
  -H "Authorization: Bearer YOUR_USER_ACCESS_TOKEN" \
  -H "Content-Type: application/json" -d '{"action":"status"}'
```

Logs for every function are in the dashboard under Edge Functions → Logs.
