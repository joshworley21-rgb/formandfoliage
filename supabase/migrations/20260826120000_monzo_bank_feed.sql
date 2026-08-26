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
