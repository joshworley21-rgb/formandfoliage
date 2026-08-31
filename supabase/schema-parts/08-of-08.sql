-- JW Garden Services CRM schema — part 8 of 8.
--
-- GENERATED FILE. Run the parts in order, one at a time, in the Supabase
-- SQL Editor. Each part is its own transaction: if one fails, nothing in
-- that part is applied and you can fix it and run the same part again.
--
-- Only needed if you are building the database by hand. The
-- "Set up Supabase" GitHub Action does all of this without any pasting.

BEGIN;

-- ---------- 20260831070000_payment_reminders_opt_in.sql ----------

-- Whether this client may be chased about an unpaid invoice.
--
-- payment-reminders runs on a schedule and, until now, emailed every client
-- with an unpaid invoice: fourteen days after it was sent, then every seven
-- days after that, for as long as it stayed unpaid. Nobody chose that per
-- customer and nothing in the app said it was happening — the first anyone
-- knew was a client mentioning they had had a reminder.
--
-- So it is off, and stays off, until it is turned on for a particular client.
-- Default false rather than true: the existing rows are exactly the customers
-- who were being chased without anyone deciding to, and defaulting to true
-- would keep every one of them chased while claiming to have fixed it.
--
-- Sending an invoice by hand is untouched. This is only the automatic chasing.
ALTER TABLE public.clients
  ADD COLUMN IF NOT EXISTS payment_reminders boolean NOT NULL DEFAULT false;

CREATE SCHEMA IF NOT EXISTS supabase_migrations;
CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (
  version text PRIMARY KEY,
  statements text[],
  name text
);

INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260831070000', 'payment_reminders_opt_in') ON CONFLICT (version) DO NOTHING;

COMMIT;
