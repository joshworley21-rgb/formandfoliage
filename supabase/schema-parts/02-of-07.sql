-- JW Garden Services CRM schema — part 2 of 7.
--
-- GENERATED FILE. Run the parts in order, one at a time, in the Supabase
-- SQL Editor. Each part is its own transaction: if one fails, nothing in
-- that part is applied and you can fix it and run the same part again.
--
-- Only needed if you are building the database by hand. The
-- "Set up Supabase" GitHub Action does all of this without any pasting.

BEGIN;

-- ---------- 20260815142454_e46b3920-bac9-4937-bb56-cecdfd09cb07.sql ----------

REVOKE ALL ON FUNCTION public.next_invoice_number(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.next_invoice_number(uuid) TO service_role;

-- ---------- 20260815142515_069e4a8a-57f4-4cf5-b7cf-9232797da54e.sql ----------

CREATE POLICY "own invoice files" ON storage.objects FOR ALL TO authenticated
USING (bucket_id = 'invoices' AND (storage.foldername(name))[1] = auth.uid()::text)
WITH CHECK (bucket_id = 'invoices' AND (storage.foldername(name))[1] = auth.uid()::text);

-- ---------- 20260815173039_3470d02d-dd7d-40e4-9328-9f85f16905c0.sql ----------

CREATE TYPE public.email_event_status AS ENUM ('sent','failed','skipped');

CREATE TABLE public.invoice_email_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  invoice_id uuid NOT NULL REFERENCES public.invoices(id) ON DELETE CASCADE,
  status public.email_event_status NOT NULL,
  recipient text,
  provider_message_id text,
  detail text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX invoice_email_events_invoice_idx ON public.invoice_email_events(invoice_id, created_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON public.invoice_email_events TO authenticated;
GRANT ALL ON public.invoice_email_events TO service_role;

ALTER TABLE public.invoice_email_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY "own invoice_email_events" ON public.invoice_email_events
  FOR ALL TO authenticated
  USING (auth.uid() = user_id)
  WITH CHECK (auth.uid() = user_id);

-- ---------- 20260815173257_7c776459-963f-4227-966f-b20889287303.sql ----------

ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS email_subject_template text NOT NULL DEFAULT 'Invoice {{invoice_number}} from {{business_name}}',
  ADD COLUMN IF NOT EXISTS email_body_template text NOT NULL DEFAULT 'Hi {{client_name}},

Please find attached invoice {{invoice_number}} for recent garden work.

Total due: {{total}}{{due_clause}}.

Many thanks,
{{business_name}}';

-- ---------- 20260815190004_0c688e25-f800-4957-a2f6-e940f7baef90.sql ----------

CREATE TABLE public.job_tasks (
  id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES auth.users ON DELETE CASCADE,
  job_id UUID NOT NULL REFERENCES public.scheduled_jobs(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  done BOOLEAN NOT NULL DEFAULT false,
  position INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.job_tasks TO authenticated;
GRANT ALL ON public.job_tasks TO service_role;
ALTER TABLE public.job_tasks ENABLE ROW LEVEL SECURITY;
CREATE POLICY "own job tasks" ON public.job_tasks FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE INDEX job_tasks_job_idx ON public.job_tasks(job_id);

CREATE TABLE public.job_photos (
  id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES auth.users ON DELETE CASCADE,
  job_id UUID NOT NULL REFERENCES public.scheduled_jobs(id) ON DELETE CASCADE,
  task_id UUID REFERENCES public.job_tasks(id) ON DELETE SET NULL,
  storage_path TEXT NOT NULL,
  caption TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.job_photos TO authenticated;
GRANT ALL ON public.job_photos TO service_role;
ALTER TABLE public.job_photos ENABLE ROW LEVEL SECURITY;
CREATE POLICY "own job photos" ON public.job_photos FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE INDEX job_photos_job_idx ON public.job_photos(job_id);

-- ---------- 20260815190017_9f791bb6-a13b-4f09-b959-0911a831397b.sql ----------

CREATE POLICY "own job photo files" ON storage.objects FOR ALL TO authenticated
USING (bucket_id = 'job-photos' AND (storage.foldername(name))[1] = auth.uid()::text)
WITH CHECK (bucket_id = 'job-photos' AND (storage.foldername(name))[1] = auth.uid()::text);

-- ---------- 20260815190713_552118b0-5528-4ce4-8f01-43b6fb88d940.sql ----------

CREATE TABLE public.bank_connections (
  id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES auth.users ON DELETE CASCADE,
  provider TEXT NOT NULL DEFAULT 'truelayer',
  access_token TEXT NOT NULL,
  refresh_token TEXT,
  expires_at TIMESTAMPTZ,
  account_id TEXT,
  account_name TEXT,
  last_synced_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Tokens are secrets: only server-side code (service role) may read this table.
GRANT ALL ON public.bank_connections TO service_role;
ALTER TABLE public.bank_connections ENABLE ROW LEVEL SECURITY;
CREATE POLICY "service role manages bank connections" ON public.bank_connections
  FOR ALL TO service_role USING (true) WITH CHECK (true);
CREATE UNIQUE INDEX bank_connections_user_idx ON public.bank_connections(user_id);
CREATE TRIGGER t_bank_connections_updated BEFORE UPDATE ON public.bank_connections
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE public.bank_transactions (
  id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES auth.users ON DELETE CASCADE,
  provider_transaction_id TEXT NOT NULL,
  posted_at TIMESTAMPTZ NOT NULL,
  amount NUMERIC NOT NULL DEFAULT 0,
  currency TEXT NOT NULL DEFAULT 'GBP',
  description TEXT,
  reference TEXT,
  counterparty TEXT,
  invoice_id UUID REFERENCES public.invoices(id) ON DELETE SET NULL,
  matched_at TIMESTAMPTZ,
  ignored BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.bank_transactions TO authenticated;
GRANT ALL ON public.bank_transactions TO service_role;
ALTER TABLE public.bank_transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "own bank transactions" ON public.bank_transactions
  FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE UNIQUE INDEX bank_transactions_unique_idx ON public.bank_transactions(user_id, provider_transaction_id);
CREATE INDEX bank_transactions_posted_idx ON public.bank_transactions(posted_at DESC);
CREATE TRIGGER t_bank_transactions_updated BEFORE UPDATE ON public.bank_transactions
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE SCHEMA IF NOT EXISTS supabase_migrations;
CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (
  version text PRIMARY KEY,
  statements text[],
  name text
);

INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815142454', 'e46b3920-bac9-4937-bb56-cecdfd09cb07') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815142515', '069e4a8a-57f4-4cf5-b7cf-9232797da54e') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815173039', '3470d02d-dd7d-40e4-9328-9f85f16905c0') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815173257', '7c776459-963f-4227-966f-b20889287303') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815190004', '0c688e25-f800-4957-a2f6-e940f7baef90') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815190017', '9f791bb6-a13b-4f09-b959-0911a831397b') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815190713', '552118b0-5528-4ce4-8f01-43b6fb88d940') ON CONFLICT (version) DO NOTHING;

COMMIT;
