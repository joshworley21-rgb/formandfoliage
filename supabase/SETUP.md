# Setting up a new Supabase project

Everything the CRM needs is in this repo: the schema is in `migrations/`, the
server-side jobs are in `functions/`. Nothing has to be clicked together in a
dashboard except the account itself, the auth providers, and the secrets.

The migration chain is checked against an empty database by
`scripts/test-migrations.sh`, so `db push` should not surprise you.

Work through this in order. It takes about twenty minutes.

---

## 1. Create the project

At [supabase.com/dashboard](https://supabase.com/dashboard) → **New project**.

- **Region**: London (`eu-west-2`) — closest to the business, and keeps customer
  data in the UK.
- **Database password**: generate one and save it in your password manager. You
  need it for direct database access and it is not shown again.

Once it finishes, note the **project ref** from the URL:
`supabase.com/dashboard/project/<project-ref>`.

## 2. Install the CLI and link the repo

```sh
npm install -g supabase
supabase login
supabase link --project-ref <your-new-project-ref>
```

Then point the repo at the new project — edit `supabase/config.toml`:

```toml
project_id = "<your-new-project-ref>"
```

## 3. Push the schema

```sh
supabase db push
```

This creates every table, the row-level security policies, the storage buckets
and the numbering functions. Check it landed:

```sh
supabase db lint
```

## 4. Point the app at the new project

In the dashboard: **Project Settings → API**. Copy the **Project URL** and the
**publishable** (anon) key, and put them in `.env`:

```sh
SUPABASE_PROJECT_ID=<your-new-project-ref>
SUPABASE_URL=https://<your-new-project-ref>.supabase.co
SUPABASE_PUBLISHABLE_KEY=<publishable key>
VITE_SUPABASE_PROJECT_ID=<your-new-project-ref>
VITE_SUPABASE_URL=https://<your-new-project-ref>.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=<publishable key>
```

Both values are public — they ship inside the app and are safe to commit. The
**service-role** key on that page is not: it belongs only in Supabase, and
nothing in this repo needs it.

Regenerate the types so the app matches the database:

```sh
supabase gen types typescript --linked > src/integrations/supabase/types.ts
```

## 5. Set up sign-in

**Authentication → Sign In / Providers**.

- **Email**: on by default. If you would rather not deal with confirmation
  emails while setting up, turn *Confirm email* off — you can turn it back on
  later.
- **Google**: enable it and paste in a Google OAuth client ID and secret from
  [console.cloud.google.com](https://console.cloud.google.com) (APIs & Services
  → Credentials → OAuth client ID → Web application). Google needs the callback
  Supabase shows you, which is
  `https://<project-ref>.supabase.co/auth/v1/callback`.

**Authentication → URL Configuration → Redirect URLs** — add all three:

| URL | Why |
|---|---|
| `http://localhost:8080/**` | local development |
| `https://<your-web-domain>/**` | the website, once it has one |
| `uk.co.jwgardenservices.crm://auth-callback` | the Android app's deep link |

Miss the last one and Google sign-in in the app fails after the browser step.

Then create your own account by signing up in the app.

## 6. Give the functions their email key

The Edge Functions need a [Resend](https://resend.com) API key to send invoices,
quotes and reminders. `SUPABASE_URL`, `SUPABASE_ANON_KEY` and
`SUPABASE_SERVICE_ROLE_KEY` are provided automatically — do not set those.

```sh
supabase secrets set RESEND_API_KEY=re_your_key_here
supabase functions deploy
```

To send from your own address rather than Resend's test one, verify your domain
in Resend, then set the business email in the CRM's Settings page.

## 7. Schedule the payment reminders

In the SQL editor, with your service-role key pasted in:

```sql
create extension if not exists pg_cron;
create extension if not exists pg_net;

select cron.schedule(
  'payment-reminders',
  '0 8 * * *',  -- 08:00 UTC daily
  $$
  select net.http_post(
    url     := 'https://<your-new-project-ref>.supabase.co/functions/v1/payment-reminders',
    headers := jsonb_build_object(
      'Content-Type',  'application/json',
      'Authorization', 'Bearer <YOUR_SERVICE_ROLE_KEY>'
    ),
    body    := '{}'::jsonb
  );
  $$
);
```

## 8. Connect the Monzo bank feed (optional)

Skip this and everything still works — invoices are simply marked paid by hand.
With it, payments into the bank show up in the app ready to be matched.

Register a **confidential** OAuth client at
[developers.monzo.com](https://developers.monzo.com) with the redirect URL
`https://<your-new-project-ref>.supabase.co/functions/v1/monzo-callback`, then:

```sh
supabase secrets set MONZO_CLIENT_ID=oauth2client_... MONZO_CLIENT_SECRET=mnzconf...
supabase functions deploy monzo-connect monzo-callback monzo-sync monzo-webhook
```

Connect it in Settings → Monzo bank feed, and **approve access in the Monzo
phone app** when prompted — Monzo serves no transactions until you do. Then
schedule the daily catch-up sync, alongside the reminder schedule above:

```sql
select cron.schedule(
  'monzo-sync',
  '0 7 * * *',  -- 07:00 UTC daily
  $$
  select net.http_post(
    url     := 'https://<your-new-project-ref>.supabase.co/functions/v1/monzo-sync',
    headers := jsonb_build_object(
      'Content-Type',  'application/json',
      'Authorization', 'Bearer <YOUR_SERVICE_ROLE_KEY>'
    ),
    body    := '{}'::jsonb
  );
  $$
);
```

`supabase/functions/README.md` covers the rest, including why the client has to
be confidential and what Monzo's API will and will not serve.

## 9. Check it works

In the app:

1. Sign up, then sign in.
2. **Settings** — fill in the business details. It should say email is
   connected.
3. **Settings → send a test email** to yourself.
4. Add a client, log a visit, raise an invoice, email it to yourself. This
   exercises invoice numbering, the storage bucket and Resend in one go.
5. **Enquiries** — the enquiry link should appear immediately. Open it, send a
   test enquiry, and check it lands in the inbox.
6. **Quotes** — raise one, send it to yourself, open the link, accept it.

If step 5 shows no link, the enquiry key did not generate; if step 4 fails on
upload, the storage buckets are missing. Both are created by the migrations, so
that means `db push` did not fully run.

---

## Bringing data over from the old project

Only needed if the old project has real records worth keeping. **Do this before
using the new project properly**, so nothing has to be merged later.

If you can still open the old project in the dashboard, the clean route is a
dump and restore. Get both connection strings from **Project Settings →
Database → Connection string** (URI):

```sh
# From the old project — data only, just the app's own tables
pg_dump "<OLD_CONNECTION_STRING>" \
  --data-only --no-owner --no-privileges \
  --schema=public \
  > crm-data.sql

# Into the new one
psql "<NEW_CONNECTION_STRING>" -f crm-data.sql
```

Two things to know:

- **Accounts do not come across.** Rows are owned by a `user_id` from the old
  project's `auth.users`. Sign up on the new project first, then repoint the
  data at your new id before restoring:

  ```sql
  -- new id from: select id, email from auth.users;
  update public.clients set user_id = '<new-id>' where user_id = '<old-id>';
  -- and the same for visits, scheduled_jobs, job_series, invoices,
  -- invoice_items, recurring_schedules, settings, enquiries, quotes,
  -- quote_items, team_members, bank_connections, bank_transactions
  ```

- **Invoice PDFs in storage are not included.** They are regenerated on demand
  from the invoice record, so this only matters if you need the exact original
  file. Download them from the old project's storage browser if so.

If you cannot open the old project at all, export whatever the Lovable interface
will give you (CSV per table is enough) and import through the new project's
table editor. Load them in this order so the foreign keys resolve: `settings`,
`clients`, `job_series`, `scheduled_jobs`, `visits`, `invoices`,
`invoice_items`, `recurring_schedules`, `enquiries`, `quotes`, `quote_items`.

## Turning off the old project

Once the new one is verified and any data is across, delete the old project or
disconnect it from Lovable — otherwise it keeps a copy of customer data alive
with nobody watching it. Rotate the old Resend key too if it was shared with it.
