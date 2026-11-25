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

-- ---------- 20261113090000_voice_model_bucket.sql ----------

-- Public read bucket for the optional fp32 Kokoro voice.
--
-- The 326 MB model does not belong in the APK or in git. The phone downloads
-- it once from this bucket (see KokoroHqDownloader), and can resume a dropped
-- connection with an HTTP Range request.
--
-- Populate it with scripts/upload-kokoro-fp32.sh. Until then the app falls
-- back to the official sherpa-onnx archive.
insert into storage.buckets (id, name, public)
values ('voice-models', 'voice-models', true)
on conflict (id) do update set public = true;

-- ---------- 20261125150000_running_costs.sql ----------

-- What it costs to keep the business running.
--
-- Until now the app could tell an owner what came in (visits, invoices) but
-- not what went out, so "profit" was really "income". Fuel, insurance, the
-- van, tools and software are the costs a garden business is judged on, and
-- they repeat — so each row is a recurring cost, not a one-off receipt.
--
-- The amount is stored exactly as the owner entered it, with the frequency it
-- applies to. Monthly equivalents are calculated in the app, which means a
-- quote or a report never has to guess how a weekly figure was annualised.

CREATE TABLE IF NOT EXISTS public.running_costs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  name text NOT NULL,
  category text NOT NULL DEFAULT 'other',
  amount numeric(10,2) NOT NULL DEFAULT 0,
  frequency text NOT NULL DEFAULT 'monthly',
  notes text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS running_costs_owner_idx
  ON public.running_costs(user_id, category);

GRANT SELECT, INSERT, UPDATE, DELETE ON public.running_costs TO authenticated;
GRANT ALL ON public.running_costs TO service_role;

ALTER TABLE public.running_costs ENABLE ROW LEVEL SECURITY;

CREATE POLICY "owner manages running costs" ON public.running_costs
  FOR ALL TO authenticated
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

CREATE TRIGGER t_running_costs_updated BEFORE UPDATE ON public.running_costs
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ---------- 20261125170000_business_knowledge.sql ----------

-- What Secretary knows about the business.
--
-- The app already ships a hand-written profile distilled from the website.
-- This column lets the owner replace it with one learned from the live site:
-- services, areas, credentials, opening hours and the way the business talks.
-- Kept on settings rather than in a new table because there is exactly one
-- profile per workspace and it is read on every voice turn.
--
-- Plain text, not JSON: a small prompt-shaped profile is what the assistant
-- consumes, and the owner may want to correct it by hand.

ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS business_profile text,
  ADD COLUMN IF NOT EXISTS business_profile_source text,
  ADD COLUMN IF NOT EXISTS business_profile_updated_at timestamptz;

CREATE SCHEMA IF NOT EXISTS supabase_migrations;
CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (
  version text PRIMARY KEY,
  statements text[],
  name text
);

INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260831070000', 'payment_reminders_opt_in') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20261113090000', 'voice_model_bucket') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20261125150000', 'running_costs') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20261125170000', 'business_knowledge') ON CONFLICT (version) DO NOTHING;

COMMIT;
