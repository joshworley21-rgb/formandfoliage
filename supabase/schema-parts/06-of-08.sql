-- JW Garden Services CRM schema — part 6 of 8.
--
-- GENERATED FILE. Run the parts in order, one at a time, in the Supabase
-- SQL Editor. Each part is its own transaction: if one fails, nothing in
-- that part is applied and you can fix it and run the same part again.
--
-- Only needed if you are building the database by hand. The
-- "Set up Supabase" GitHub Action does all of this without any pasting.

BEGIN;

-- ---------- 20260823150000_business_details.sql ----------

-- Pre-fill the business details so Settings is populated the first time it is
-- opened, rather than being a form to fill in on a phone.
--
-- These are column defaults rather than values in the app, so they apply to the
-- settings row whenever it is created, and they never ship inside the APK.

ALTER TABLE public.settings
  ALTER COLUMN address SET DEFAULT '24 Portland Close
Bierton
HP22 7DG',
  ALTER COLUMN email SET DEFAULT 'info@jw-gardenservices.co.uk',
  ALTER COLUMN payment_terms SET DEFAULT 'Payment due within 14 days.',
  -- The invoice PDF reads this as "Label: value" lines, so labelling them here
  -- is what turns it into a payment block rather than loose numbers.
  ALTER COLUMN bank_details SET DEFAULT 'Account name: Joshua Worley trading as JW Garden Services
Sort code: 04-00-03
Account number: 64775630';

-- Fill in a settings row that already exists, but only where a field is still
-- empty — anything already typed in the app wins over these.
UPDATE public.settings SET
  business_name = COALESCE(NULLIF(btrim(business_name), ''), 'JW Garden Services'),
  address       = COALESCE(NULLIF(btrim(address), ''), '24 Portland Close
Bierton
HP22 7DG'),
  email         = COALESCE(NULLIF(btrim(email), ''), 'info@jw-gardenservices.co.uk'),
  invoice_prefix = COALESCE(NULLIF(btrim(invoice_prefix), ''), 'JW-'),
  payment_terms = COALESCE(NULLIF(btrim(payment_terms), ''), 'Payment due within 14 days.'),
  bank_details  = COALESCE(NULLIF(btrim(bank_details), ''), 'Account name: Joshua Worley trading as JW Garden Services
Sort code: 04-00-03
Account number: 64775630');

-- Phone is deliberately left blank: no number was given. Add it in Settings and
-- it will appear on invoices alongside the address.

-- ---------- 20260823170000_reply_to_address.sql ----------

-- Let the From address and the reply address differ.
--
-- One field was doing both jobs, so sending invoices from a send-only address
-- would have quietly dropped every customer reply. Nothing needs two addresses
-- today — this stays empty, and everything falls back to `email` — but the
-- fallback is what makes adding an invoices@ alias later a Settings change
-- rather than a code change.

ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS reply_to_email text;

COMMENT ON COLUMN public.settings.email IS
  'The From address on invoice and quote emails. Must be on a domain verified with Resend; it does not need a mailbox.';
COMMENT ON COLUMN public.settings.reply_to_email IS
  'Where customer replies, website enquiries and quote answers land, and the contact address printed on the invoice. Falls back to email when blank.';

-- ---------- 20260824100000_email_delivery_events.sql ----------

-- Delivery outcomes reported by Resend after an email leaves the app.
--
-- Until now the log stopped at "sent", which only means Resend accepted it.
-- Whether it reached the client, bounced, or was marked as spam is the part
-- worth knowing — a wrong address currently looks identical to a paid invoice
-- nobody has answered yet.

ALTER TYPE public.email_event_status ADD VALUE IF NOT EXISTS 'delivered';
ALTER TYPE public.email_event_status ADD VALUE IF NOT EXISTS 'opened';
ALTER TYPE public.email_event_status ADD VALUE IF NOT EXISTS 'clicked';
ALTER TYPE public.email_event_status ADD VALUE IF NOT EXISTS 'bounced';
ALTER TYPE public.email_event_status ADD VALUE IF NOT EXISTS 'complained';
ALTER TYPE public.email_event_status ADD VALUE IF NOT EXISTS 'delivery_delayed';

-- Webhooks arrive knowing only Resend's message id, so that is the way back to
-- the invoice the email was about.
CREATE INDEX IF NOT EXISTS invoice_email_events_provider_idx
  ON public.invoice_email_events(provider_message_id)
  WHERE provider_message_id IS NOT NULL;

CREATE SCHEMA IF NOT EXISTS supabase_migrations;
CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (
  version text PRIMARY KEY,
  statements text[],
  name text
);

INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260823150000', 'business_details') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260823170000', 'reply_to_address') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260824100000', 'email_delivery_events') ON CONFLICT (version) DO NOTHING;

COMMIT;
