-- JW Garden Services CRM schema — part 7 of 7.
--
-- GENERATED FILE. Run the parts in order, one at a time, in the Supabase
-- SQL Editor. Each part is its own transaction: if one fails, nothing in
-- that part is applied and you can fix it and run the same part again.
--
-- Only needed if you are building the database by hand. The
-- "Set up Supabase" GitHub Action does all of this without any pasting.

BEGIN;

-- ---------- 20260826120000_monzo_bank_feed.sql ----------

-- Monzo bank feed: incoming payments arrive on their own and wait to be matched
-- to an invoice.
--
-- The bank_connections and bank_transactions tables have existed since the
-- first migration but nothing has ever written to them — they were shaped for
-- TrueLayer and left dormant. This fills them in for Monzo, whose developer API
-- pushes a webhook the moment money lands, so the CRM learns about a payment
-- without anyone opening the banking app.
--
-- Nothing here marks an invoice paid. A payment becomes a suggestion; a person
-- confirms it. That is deliberate: a wrongly auto-settled invoice is worse than
-- one tap, because it stops the reminder that would have chased a real debt.

-- ------------------------------------------------------------- connection ---

ALTER TABLE public.bank_connections
  -- Every row this app writes is Monzo. The old default named a provider that
  -- was never implemented, which made the column read like a lie.
  ALTER COLUMN provider SET DEFAULT 'monzo',
  -- Monzo hands back a webhook id at registration; it is the only way to take
  -- the webhook down again when the account is disconnected.
  ADD COLUMN IF NOT EXISTS webhook_id text,
  -- Monzo does not sign its webhooks, so the endpoint has nothing to verify a
  -- request against. This random token travels in the webhook URL and is what
  -- tells a real delivery from a stranger who found the function. The payload
  -- is checked against the Monzo API before it is trusted regardless.
  ADD COLUMN IF NOT EXISTS webhook_secret text,
  -- uk_retail, uk_retail_joint, or a business type. Worth keeping: the
  -- developer API documents the retail ones, so this is the field that explains
  -- an account the sync cannot see.
  ADD COLUMN IF NOT EXISTS account_type text,
  -- Monzo requires the account holder to approve API access in the phone app.
  -- Until they do, transaction reads come back 403 and the connection is real
  -- but useless, which needs saying out loud in Settings.
  ADD COLUMN IF NOT EXISTS approved_at timestamptz,
  -- Why the last sync failed, shown in Settings. Cleared by a sync that works.
  ADD COLUMN IF NOT EXISTS last_sync_error text;

-- The webhook arrives with no user and no JWT: this token is the lookup.
CREATE UNIQUE INDEX IF NOT EXISTS bank_connections_webhook_secret_idx
  ON public.bank_connections(webhook_secret)
  WHERE webhook_secret IS NOT NULL;

-- ------------------------------------------------------------ transaction ---

ALTER TABLE public.bank_transactions
  -- What the matcher thinks this payment pays, and why. Kept apart from
  -- invoice_id so that a guess can never be mistaken for a decision: invoice_id
  -- is only ever set by a person confirming one.
  ADD COLUMN IF NOT EXISTS suggested_invoice_id uuid REFERENCES public.invoices(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS match_reason text,
  -- Set when the payment has been dealt with — confirmed against an invoice or
  -- dismissed. What is left is the review list.
  ADD COLUMN IF NOT EXISTS reviewed_at timestamptz,
  -- Monzo's own settled timestamp. posted_at holds when it was created; a
  -- faster payment in is usually both at once, but a pending one is not.
  ADD COLUMN IF NOT EXISTS settled_at timestamptz;

-- The review list is "money in, not yet dealt with, newest first". Everything
-- else in the table is history.
CREATE INDEX IF NOT EXISTS bank_transactions_review_idx
  ON public.bank_transactions(user_id, posted_at DESC)
  WHERE reviewed_at IS NULL AND ignored = false;

-- One payment settles one invoice here. Two rows pointing at the same invoice
-- would each look like the thing that paid it, and the second would silently
-- overwrite the first's audit trail.
CREATE UNIQUE INDEX IF NOT EXISTS bank_transactions_invoice_idx
  ON public.bank_transactions(invoice_id)
  WHERE invoice_id IS NOT NULL;

-- ---------- 20260826180000_missed_visits.sql ----------

-- A visit that did not happen, recorded rather than forgotten.
--
-- Turning up to a locked gate, or a garden too waterlogged to touch, is worth
-- writing down: it explains a gap in the round to anyone looking back, and it
-- is the answer to "did you come in March?". Until now the only ways to record
-- one were to log it and remember never to bill it, or not to log it at all —
-- one of which quietly overcharges a client and the other loses the history.
--
-- Kept apart from `billed`, deliberately. Marking a missed visit as billed
-- would say an invoice covered it, which is untrue and would confuse the
-- reconciliation the moment anyone looked. This says what actually happened
-- and every count of unbilled work skips it.

ALTER TABLE public.visits
  ADD COLUMN IF NOT EXISTS missed boolean NOT NULL DEFAULT false;

-- Every "what is waiting to be invoiced" query is now "not billed and not
-- missed", on a table that grows by a few rows a day forever.
CREATE INDEX IF NOT EXISTS visits_billable_idx
  ON public.visits(user_id, visit_date DESC)
  WHERE billed = false AND missed = false;

-- ---------- 20260828180000_materials_note.sql ----------

-- What the materials on a visit actually were.
--
-- Until now a visit recorded only a materials figure, so an invoice could say
-- a customer owed £18 for materials with nothing anywhere saying what they
-- were — not on the invoice, and not in the app three months later when they
-- ask. This is the line that answers it: "2 bags compost, bark chippings".
--
-- Free text rather than a table of priced rows. Nobody itemises a bag of
-- compost on a phone in a garden, and a sentence that gets written beats a
-- structure that does not.
ALTER TABLE public.visits
  ADD COLUMN IF NOT EXISTS materials_note text;

CREATE SCHEMA IF NOT EXISTS supabase_migrations;
CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (
  version text PRIMARY KEY,
  statements text[],
  name text
);

INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260826120000', 'monzo_bank_feed') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260826180000', 'missed_visits') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260828180000', 'materials_note') ON CONFLICT (version) DO NOTHING;

COMMIT;
