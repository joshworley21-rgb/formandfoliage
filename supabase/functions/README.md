# Edge Functions

These replace the server functions and API routes the CRM used to run inside the
web app. Moving them here is what lets the app ship as a standalone Android app:
the phone talks to Supabase directly, and nothing needs a web server.

| Function | Replaces | Who may call it |
|---|---|---|
| `create-invoice` | `createInvoice` server fn | signed-in user |
| `send-invoice-email` | `sendInvoiceEmail` server fn | signed-in user |
| `email-status` | `getResendStatus` server fn | signed-in user |
| `send-test-email` | `sendTestEmail` server fn | signed-in user |
| `send-quote-email` | new — emails a quote with its accept link | signed-in user |
| `public-enquiry` | `POST /api/public/enquiry` | anyone (public form) |
| `public-quote` | new — customer views and answers a quote | anyone with the link |
| `payment-reminders` | `POST /api/public/hooks/payment-reminders` | scheduler only, via the service-role key |

`public-quote` is how the customer's accept page reaches a quote: the `quotes`
table has no anon policy, so this function is the only way in and it returns
only the fields that page needs.

`create-invoice` has to run here rather than on the phone because
`next_invoice_number()` is granted to `service_role` only — invoice numbers must
not be skippable from the client. `send-invoice-email` and friends run here
because they hold the Resend API key.

## One-time setup

You need the Supabase CLI and access to the project dashboard.

```sh
# 1. Install the CLI (see supabase.com/docs/guides/cli for other platforms)
npm install -g supabase

# 2. Sign in and link this repo to the project
supabase login
supabase link --project-ref ftngutorudrwuqwdpzrb

# 3. Give the functions the Resend key (the only secret they need —
#    SUPABASE_URL, SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY are
#    provided by the platform automatically)
supabase secrets set RESEND_API_KEY=re_your_key_here
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
    url     := 'https://ftngutorudrwuqwdpzrb.supabase.co/functions/v1/payment-reminders',
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

## Checking a deploy

```sh
# Should report {"connected":true} once RESEND_API_KEY is set
curl -X POST https://ftngutorudrwuqwdpzrb.supabase.co/functions/v1/email-status \
  -H "Authorization: Bearer YOUR_USER_ACCESS_TOKEN"

# Reminder sweep, dry of any effect if nothing is due
curl -X POST https://ftngutorudrwuqwdpzrb.supabase.co/functions/v1/payment-reminders \
  -H "Authorization: Bearer YOUR_SERVICE_ROLE_KEY"
```

Logs for every function are in the dashboard under Edge Functions → Logs.
