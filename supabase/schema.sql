-- JW Garden Services CRM — complete database schema.
--
-- GENERATED FILE. Do not edit by hand: it is every file in
-- supabase/migrations/ joined in order by scripts/build-schema-sql.sh.
--
-- Run this once against a brand-new Supabase project: open the SQL Editor
-- in the dashboard, paste the whole file, and press Run. It is wrapped in a
-- transaction, so if anything fails nothing is left half-applied.
--
-- Already have a database built by the CLI? You do not need this file.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────
-- 20260815142242_e9728a50-33bd-4f4f-af97-04368bfcbb71.sql
-- ─────────────────────────────────────────────────────────────────────

CREATE TYPE public.billing_mode AS ENUM ('hourly','fixed');
CREATE TYPE public.job_status AS ENUM ('scheduled','done','missed','cancelled');
CREATE TYPE public.invoice_status AS ENUM ('draft','sent','paid');
CREATE TYPE public.repeat_frequency AS ENUM ('weekly','fortnightly','four_weekly','monthly','quarterly');

CREATE OR REPLACE FUNCTION public.set_updated_at() RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END; $$ LANGUAGE plpgsql SET search_path = public;

CREATE TABLE public.clients (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  name text NOT NULL,
  address text,
  phone text,
  email text,
  notes text,
  billing_mode public.billing_mode NOT NULL DEFAULT 'hourly',
  hourly_rate numeric(10,2) NOT NULL DEFAULT 0,
  fixed_price numeric(10,2) NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.job_series (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  client_id uuid NOT NULL REFERENCES public.clients(id) ON DELETE CASCADE,
  frequency public.repeat_frequency NOT NULL DEFAULT 'weekly',
  start_date date NOT NULL,
  end_date date,
  start_time time NOT NULL DEFAULT '09:00',
  duration_minutes integer NOT NULL DEFAULT 60,
  notes text,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.visits (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  client_id uuid NOT NULL REFERENCES public.clients(id) ON DELETE CASCADE,
  visit_date date NOT NULL DEFAULT current_date,
  hours numeric(6,2) NOT NULL DEFAULT 0,
  description text,
  materials_cost numeric(10,2) NOT NULL DEFAULT 0,
  amount numeric(10,2) NOT NULL DEFAULT 0,
  billed boolean NOT NULL DEFAULT false,
  invoice_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.scheduled_jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  client_id uuid NOT NULL REFERENCES public.clients(id) ON DELETE CASCADE,
  series_id uuid REFERENCES public.job_series(id) ON DELETE SET NULL,
  starts_at timestamptz NOT NULL,
  duration_minutes integer NOT NULL DEFAULT 60,
  status public.job_status NOT NULL DEFAULT 'scheduled',
  notes text,
  visit_id uuid REFERENCES public.visits(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.invoices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  client_id uuid NOT NULL REFERENCES public.clients(id) ON DELETE CASCADE,
  number text NOT NULL,
  issue_date date NOT NULL DEFAULT current_date,
  due_date date,
  period_start date,
  period_end date,
  subtotal numeric(10,2) NOT NULL DEFAULT 0,
  vat_amount numeric(10,2) NOT NULL DEFAULT 0,
  total numeric(10,2) NOT NULL DEFAULT 0,
  status public.invoice_status NOT NULL DEFAULT 'draft',
  sent_at timestamptz,
  paid_at timestamptz,
  pdf_path text,
  notes text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.visits ADD CONSTRAINT visits_invoice_fk FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON DELETE SET NULL;

CREATE TABLE public.invoice_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  invoice_id uuid NOT NULL REFERENCES public.invoices(id) ON DELETE CASCADE,
  visit_id uuid REFERENCES public.visits(id) ON DELETE SET NULL,
  item_date date,
  description text NOT NULL,
  quantity numeric(10,2) NOT NULL DEFAULT 1,
  unit_price numeric(10,2) NOT NULL DEFAULT 0,
  amount numeric(10,2) NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.recurring_schedules (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  client_id uuid NOT NULL REFERENCES public.clients(id) ON DELETE CASCADE,
  frequency public.repeat_frequency NOT NULL DEFAULT 'monthly',
  start_date date NOT NULL DEFAULT current_date,
  end_date date,
  next_run_date date NOT NULL DEFAULT current_date,
  auto_send boolean NOT NULL DEFAULT false,
  retainer_amount numeric(10,2) NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  last_run_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.settings (
  user_id uuid PRIMARY KEY,
  business_name text NOT NULL DEFAULT 'JW Garden Services',
  address text,
  phone text,
  email text,
  vat_registered boolean NOT NULL DEFAULT false,
  vat_number text,
  vat_rate numeric(5,2) NOT NULL DEFAULT 20,
  invoice_prefix text NOT NULL DEFAULT 'JW-',
  invoice_counter integer NOT NULL DEFAULT 0,
  payment_terms text,
  bank_details text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON public.clients, public.job_series, public.visits, public.scheduled_jobs, public.invoices, public.invoice_items, public.recurring_schedules, public.settings TO authenticated;
GRANT ALL ON public.clients, public.job_series, public.visits, public.scheduled_jobs, public.invoices, public.invoice_items, public.recurring_schedules, public.settings TO service_role;

ALTER TABLE public.clients ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.job_series ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.visits ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.scheduled_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invoice_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.recurring_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.settings ENABLE ROW LEVEL SECURITY;

CREATE POLICY "own clients" ON public.clients FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "own job_series" ON public.job_series FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "own visits" ON public.visits FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "own scheduled_jobs" ON public.scheduled_jobs FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "own invoices" ON public.invoices FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "own invoice_items" ON public.invoice_items FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "own recurring_schedules" ON public.recurring_schedules FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "own settings" ON public.settings FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

CREATE INDEX idx_visits_user_client ON public.visits(user_id, client_id, billed);
CREATE INDEX idx_jobs_user_start ON public.scheduled_jobs(user_id, starts_at);
CREATE INDEX idx_invoices_user ON public.invoices(user_id, issue_date DESC);

CREATE TRIGGER t_clients_updated BEFORE UPDATE ON public.clients FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER t_visits_updated BEFORE UPDATE ON public.visits FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER t_jobs_updated BEFORE UPDATE ON public.scheduled_jobs FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER t_series_updated BEFORE UPDATE ON public.job_series FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER t_invoices_updated BEFORE UPDATE ON public.invoices FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER t_recurring_updated BEFORE UPDATE ON public.recurring_schedules FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER t_settings_updated BEFORE UPDATE ON public.settings FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE OR REPLACE FUNCTION public.next_invoice_number(_user_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE _prefix text; _n integer;
BEGIN
  INSERT INTO public.settings(user_id) VALUES (_user_id) ON CONFLICT (user_id) DO NOTHING;
  UPDATE public.settings SET invoice_counter = invoice_counter + 1
    WHERE user_id = _user_id RETURNING invoice_prefix, invoice_counter INTO _prefix, _n;
  RETURN _prefix || lpad(_n::text, 4, '0');
END; $$;
-- ─────────────────────────────────────────────────────────────────────
-- 20260815142454_e46b3920-bac9-4937-bb56-cecdfd09cb07.sql
-- ─────────────────────────────────────────────────────────────────────

REVOKE ALL ON FUNCTION public.next_invoice_number(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.next_invoice_number(uuid) TO service_role;
-- ─────────────────────────────────────────────────────────────────────
-- 20260815142515_069e4a8a-57f4-4cf5-b7cf-9232797da54e.sql
-- ─────────────────────────────────────────────────────────────────────

CREATE POLICY "own invoice files" ON storage.objects FOR ALL TO authenticated
USING (bucket_id = 'invoices' AND (storage.foldername(name))[1] = auth.uid()::text)
WITH CHECK (bucket_id = 'invoices' AND (storage.foldername(name))[1] = auth.uid()::text);
-- ─────────────────────────────────────────────────────────────────────
-- 20260815173039_3470d02d-dd7d-40e4-9328-9f85f16905c0.sql
-- ─────────────────────────────────────────────────────────────────────

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
-- ─────────────────────────────────────────────────────────────────────
-- 20260815173257_7c776459-963f-4227-966f-b20889287303.sql
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS email_subject_template text NOT NULL DEFAULT 'Invoice {{invoice_number}} from {{business_name}}',
  ADD COLUMN IF NOT EXISTS email_body_template text NOT NULL DEFAULT 'Hi {{client_name}},

Please find attached invoice {{invoice_number}} for recent garden work.

Total due: {{total}}{{due_clause}}.

Many thanks,
{{business_name}}';
-- ─────────────────────────────────────────────────────────────────────
-- 20260815190004_0c688e25-f800-4957-a2f6-e940f7baef90.sql
-- ─────────────────────────────────────────────────────────────────────

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
-- ─────────────────────────────────────────────────────────────────────
-- 20260815190017_9f791bb6-a13b-4f09-b959-0911a831397b.sql
-- ─────────────────────────────────────────────────────────────────────

CREATE POLICY "own job photo files" ON storage.objects FOR ALL TO authenticated
USING (bucket_id = 'job-photos' AND (storage.foldername(name))[1] = auth.uid()::text)
WITH CHECK (bucket_id = 'job-photos' AND (storage.foldername(name))[1] = auth.uid()::text);
-- ─────────────────────────────────────────────────────────────────────
-- 20260815190713_552118b0-5528-4ce4-8f01-43b6fb88d940.sql
-- ─────────────────────────────────────────────────────────────────────

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
-- ─────────────────────────────────────────────────────────────────────
-- 20260815200538_ab021c2e-4304-4ddd-abc8-0809a52cd748.sql
-- ─────────────────────────────────────────────────────────────────────

-- Roles
CREATE TYPE public.app_role AS ENUM ('owner', 'staff');

CREATE TABLE public.team_members (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id uuid NOT NULL,
  user_id uuid,
  email text NOT NULL,
  name text,
  role public.app_role NOT NULL DEFAULT 'staff',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (owner_id, email)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON public.team_members TO authenticated;
GRANT ALL ON public.team_members TO service_role;

ALTER TABLE public.team_members ENABLE ROW LEVEL SECURITY;

CREATE POLICY "owner manages team" ON public.team_members
  FOR ALL TO authenticated USING (owner_id = auth.uid()) WITH CHECK (owner_id = auth.uid());

CREATE POLICY "member reads own membership" ON public.team_members
  FOR SELECT TO authenticated USING (user_id = auth.uid());

CREATE TRIGGER t_team_members_updated BEFORE UPDATE ON public.team_members
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- Helpers
CREATE OR REPLACE FUNCTION public.is_staff(_uid uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.team_members
    WHERE user_id = _uid AND role = 'staff'
  );
$$;

CREATE OR REPLACE FUNCTION public.owner_for(_uid uuid)
RETURNS uuid LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT COALESCE(
    (SELECT owner_id FROM public.team_members WHERE user_id = _uid AND role = 'staff' LIMIT 1),
    _uid
  );
$$;

CREATE OR REPLACE FUNCTION public.my_workspace()
RETURNS TABLE (owner_id uuid, role public.app_role)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT public.owner_for(auth.uid()),
         CASE WHEN public.is_staff(auth.uid()) THEN 'staff'::public.app_role ELSE 'owner'::public.app_role END;
$$;

-- Link a signed-in user to a pending invite for their email
CREATE OR REPLACE FUNCTION public.claim_staff_invite()
RETURNS uuid LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public AS $$
DECLARE _owner uuid; _email text;
BEGIN
  _email := lower(coalesce(auth.jwt() ->> 'email', ''));
  IF _email = '' THEN RETURN NULL; END IF;
  UPDATE public.team_members
     SET user_id = auth.uid()
   WHERE user_id IS NULL AND lower(email) = _email
   RETURNING owner_id INTO _owner;
  RETURN _owner;
END; $$;

GRANT EXECUTE ON FUNCTION public.is_staff(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.owner_for(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.my_workspace() TO authenticated;
GRANT EXECUTE ON FUNCTION public.claim_staff_invite() TO authenticated;

-- Staff access to jobs and job sheets (no money tables)
CREATE POLICY "staff read jobs" ON public.scheduled_jobs
  FOR SELECT TO authenticated
  USING (public.is_staff(auth.uid()) AND user_id = public.owner_for(auth.uid()));

CREATE POLICY "staff update jobs" ON public.scheduled_jobs
  FOR UPDATE TO authenticated
  USING (public.is_staff(auth.uid()) AND user_id = public.owner_for(auth.uid()))
  WITH CHECK (public.is_staff(auth.uid()) AND user_id = public.owner_for(auth.uid()));

CREATE POLICY "staff read series" ON public.job_series
  FOR SELECT TO authenticated
  USING (public.is_staff(auth.uid()) AND user_id = public.owner_for(auth.uid()));

CREATE POLICY "staff manage job tasks" ON public.job_tasks
  FOR ALL TO authenticated
  USING (public.is_staff(auth.uid()) AND user_id = public.owner_for(auth.uid()))
  WITH CHECK (public.is_staff(auth.uid()) AND user_id = public.owner_for(auth.uid()));

CREATE POLICY "staff manage job photos" ON public.job_photos
  FOR ALL TO authenticated
  USING (public.is_staff(auth.uid()) AND user_id = public.owner_for(auth.uid()))
  WITH CHECK (public.is_staff(auth.uid()) AND user_id = public.owner_for(auth.uid()));

-- Client info for staff: name / address / notes only
CREATE OR REPLACE FUNCTION public.staff_clients()
RETURNS TABLE (id uuid, name text, address text, notes text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS $$
  SELECT c.id, c.name, c.address, c.notes
  FROM public.clients c
  WHERE c.user_id = public.owner_for(auth.uid())
    AND c.active
  ORDER BY c.name;
$$;
GRANT EXECUTE ON FUNCTION public.staff_clients() TO authenticated;

-- Completing a job as staff: prices the visit server-side
CREATE OR REPLACE FUNCTION public.staff_complete_job(_job_id uuid, _hours numeric, _description text DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public AS $$
DECLARE _job public.scheduled_jobs; _client public.clients; _amount numeric; _visit uuid; _owner uuid;
BEGIN
  _owner := public.owner_for(auth.uid());
  SELECT * INTO _job FROM public.scheduled_jobs WHERE id = _job_id AND user_id = _owner;
  IF _job.id IS NULL THEN RAISE EXCEPTION 'Job not found'; END IF;
  SELECT * INTO _client FROM public.clients WHERE id = _job.client_id;
  _amount := CASE WHEN _client.billing_mode = 'fixed'
                  THEN coalesce(_client.fixed_price, 0)
                  ELSE coalesce(_client.hourly_rate, 0) * coalesce(_hours, 0) END;
  INSERT INTO public.visits (user_id, client_id, visit_date, hours, description, amount)
  VALUES (_owner, _job.client_id, (_job.starts_at AT TIME ZONE 'UTC')::date, coalesce(_hours, 0),
          coalesce(_description, _job.notes), round(_amount, 2))
  RETURNING id INTO _visit;
  UPDATE public.scheduled_jobs SET status = 'done', visit_id = _visit WHERE id = _job_id;
  RETURN _visit;
END; $$;
GRANT EXECUTE ON FUNCTION public.staff_complete_job(uuid, numeric, text) TO authenticated;

-- Storage: staff can work with the owner's job photos
DROP POLICY IF EXISTS "own job photo files" ON storage.objects;
CREATE POLICY "job photo files" ON storage.objects FOR ALL TO authenticated
USING (bucket_id = 'job-photos' AND (storage.foldername(name))[1] = public.owner_for(auth.uid())::text)
WITH CHECK (bucket_id = 'job-photos' AND (storage.foldername(name))[1] = public.owner_for(auth.uid())::text);
-- ─────────────────────────────────────────────────────────────────────
-- 20260815200555_fdfd4163-5294-4c83-b1d9-5a87ffa009ed.sql
-- ─────────────────────────────────────────────────────────────────────

REVOKE ALL ON FUNCTION public.is_staff(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.owner_for(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.my_workspace() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.claim_staff_invite() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.staff_clients() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.staff_complete_job(uuid, numeric, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_staff(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.owner_for(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.my_workspace() TO authenticated;
GRANT EXECUTE ON FUNCTION public.claim_staff_invite() TO authenticated;
GRANT EXECUTE ON FUNCTION public.staff_clients() TO authenticated;
GRANT EXECUTE ON FUNCTION public.staff_complete_job(uuid, numeric, text) TO authenticated;
-- ─────────────────────────────────────────────────────────────────────
-- 20260816051823_f6ab4d20-e3ad-4d85-b7aa-3aafc6e8b60b.sql
-- ─────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION public.is_staff(_uid uuid)
RETURNS boolean
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO 'public'
AS $function$
  SELECT CASE
    WHEN _uid IS NULL OR _uid <> auth.uid() THEN false
    ELSE EXISTS (
      SELECT 1 FROM public.team_members
      WHERE user_id = _uid AND role = 'staff'
    )
  END;
$function$;

CREATE OR REPLACE FUNCTION public.owner_for(_uid uuid)
RETURNS uuid
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO 'public'
AS $function$
  SELECT CASE
    WHEN _uid IS NULL OR _uid <> auth.uid() THEN NULL
    ELSE COALESCE(
      (SELECT owner_id FROM public.team_members WHERE user_id = _uid AND role = 'staff' LIMIT 1),
      _uid
    )
  END;
$function$;

REVOKE ALL ON FUNCTION public.is_staff(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.owner_for(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.my_workspace() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.staff_clients() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.claim_staff_invite() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.staff_complete_job(uuid, numeric, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.next_invoice_number(uuid) FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.is_staff(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.owner_for(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.my_workspace() TO authenticated;
GRANT EXECUTE ON FUNCTION public.staff_clients() TO authenticated;
GRANT EXECUTE ON FUNCTION public.claim_staff_invite() TO authenticated;
GRANT EXECUTE ON FUNCTION public.staff_complete_job(uuid, numeric, text) TO authenticated;
-- ─────────────────────────────────────────────────────────────────────
-- 20260818184700_9ab9f918-63a7-4b9c-9f20-0652e79af668.sql
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE public.enquiries (
  id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES auth.users ON DELETE CASCADE,
  name TEXT NOT NULL,
  email TEXT,
  phone TEXT,
  address TEXT,
  message TEXT,
  source TEXT NOT NULL DEFAULT 'website',
  status TEXT NOT NULL DEFAULT 'new',
  client_id UUID REFERENCES public.clients(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT enquiries_status_check CHECK (status IN ('new','contacted','converted','closed'))
);
GRANT SELECT, INSERT, UPDATE, DELETE ON public.enquiries TO authenticated;
GRANT ALL ON public.enquiries TO service_role;
ALTER TABLE public.enquiries ENABLE ROW LEVEL SECURITY;
CREATE POLICY "own enquiries" ON public.enquiries
  FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE INDEX enquiries_user_created_idx ON public.enquiries(user_id, created_at DESC);
CREATE TRIGGER t_enquiries_updated BEFORE UPDATE ON public.enquiries
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
-- ─────────────────────────────────────────────────────────────────────
-- 20260818184742_91e41a57-08a0-4aa6-a09f-6e778b236c36.sql
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE public.settings ADD COLUMN IF NOT EXISTS enquiry_key TEXT;
-- gen_random_uuid() is core Postgres; pgcrypto's gen_random_bytes lives in the
-- extensions schema and is only reachable when that is on the search_path,
-- which made this statement fail when replayed against a fresh project.
UPDATE public.settings SET enquiry_key = substr(replace(gen_random_uuid()::text, '-', ''), 1, 18) WHERE enquiry_key IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS settings_enquiry_key_idx ON public.settings(enquiry_key);
-- ─────────────────────────────────────────────────────────────────────
-- 20260822120000_quotes.sql
-- ─────────────────────────────────────────────────────────────────────

-- Quotes: the missing step between an enquiry and an invoice.
--
-- An enquiry arrives with no client record attached, so a quote holds its own
-- copy of who it is for and only points at a client once one exists. Accepting a
-- quote is what creates the client.

CREATE TYPE public.quote_status AS ENUM ('draft','sent','accepted','declined','expired');

CREATE TABLE public.quotes (
  id uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users ON DELETE CASCADE,
  -- Both optional: a quote can be written straight for an existing client, or
  -- raised off an enquiry before that client exists.
  client_id uuid REFERENCES public.clients(id) ON DELETE SET NULL,
  enquiry_id uuid REFERENCES public.enquiries(id) ON DELETE SET NULL,
  number text NOT NULL,
  -- Who it is for, as it stood when the quote was written.
  contact_name text NOT NULL,
  contact_email text,
  contact_phone text,
  contact_address text,
  issue_date date NOT NULL DEFAULT current_date,
  valid_until date,
  subtotal numeric(10,2) NOT NULL DEFAULT 0,
  vat_amount numeric(10,2) NOT NULL DEFAULT 0,
  total numeric(10,2) NOT NULL DEFAULT 0,
  status public.quote_status NOT NULL DEFAULT 'draft',
  notes text,
  terms text,
  -- Unguessable key behind the customer's accept/decline link. Built from
  -- gen_random_uuid() rather than pgcrypto's gen_random_bytes: this is a column
  -- default evaluated on every insert, and gen_random_uuid() is core Postgres,
  -- so it cannot break on whatever search_path the inserting role happens to
  -- have. 32 hex characters, 122 bits of randomness.
  public_key text NOT NULL UNIQUE DEFAULT replace(gen_random_uuid()::text, '-', ''),
  sent_at timestamptz,
  decided_at timestamptz,
  decline_reason text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.quote_items (
  id uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users ON DELETE CASCADE,
  quote_id uuid NOT NULL REFERENCES public.quotes(id) ON DELETE CASCADE,
  description text NOT NULL,
  quantity numeric(10,2) NOT NULL DEFAULT 1,
  unit_price numeric(10,2) NOT NULL DEFAULT 0,
  amount numeric(10,2) NOT NULL DEFAULT 0,
  sort_order integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON public.quotes TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.quote_items TO authenticated;
GRANT ALL ON public.quotes TO service_role;
GRANT ALL ON public.quote_items TO service_role;

ALTER TABLE public.quotes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.quote_items ENABLE ROW LEVEL SECURITY;

-- No anon policy on purpose: the customer's accept page reads and updates the
-- quote through the public-quote Edge Function, so nothing is exposed by key
-- guessing beyond what that function chooses to return.
CREATE POLICY "own quotes" ON public.quotes
  FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "own quote_items" ON public.quote_items
  FOR ALL TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

CREATE INDEX quotes_user_created_idx ON public.quotes(user_id, created_at DESC);
CREATE INDEX quotes_public_key_idx ON public.quotes(public_key);
CREATE INDEX quote_items_quote_idx ON public.quote_items(quote_id, sort_order);

CREATE TRIGGER t_quotes_updated BEFORE UPDATE ON public.quotes
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- Quote numbering and defaults, alongside the invoice equivalents.
ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS quote_prefix text NOT NULL DEFAULT 'Q-',
  ADD COLUMN IF NOT EXISTS quote_counter integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS quote_validity_days integer NOT NULL DEFAULT 30,
  ADD COLUMN IF NOT EXISTS quote_terms text;

-- Unlike next_invoice_number, this is callable by the signed-in owner: a quote
-- number carries none of an invoice number's accounting weight, and keeping it
-- client-callable means writing a quote needs no round trip through a function.
-- The guard stops it being used to bump anyone else's counter.
CREATE OR REPLACE FUNCTION public.next_quote_number(_user_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE _prefix text; _n integer;
BEGIN
  IF auth.uid() IS DISTINCT FROM _user_id THEN
    RAISE EXCEPTION 'Not permitted';
  END IF;
  INSERT INTO public.settings(user_id) VALUES (_user_id) ON CONFLICT (user_id) DO NOTHING;
  UPDATE public.settings SET quote_counter = quote_counter + 1
    WHERE user_id = _user_id RETURNING quote_prefix, quote_counter INTO _prefix, _n;
  RETURN _prefix || lpad(_n::text, 4, '0');
END; $$;

REVOKE ALL ON FUNCTION public.next_quote_number(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.next_quote_number(uuid) TO authenticated, service_role;

-- ─────────────────────────────────────────────────────────────────────
-- 20260822130000_fresh_project_bootstrap.sql
-- ─────────────────────────────────────────────────────────────────────

-- Things a working project needs that no migration ever created, because they
-- were set up by hand in the old hosting provider's dashboard. Without these a
-- brand-new Supabase project comes up subtly broken.

-- 1. Storage buckets. Policies for both already exist in earlier migrations,
--    but the buckets themselves did not, so invoice PDFs and job photos had
--    nowhere to upload to. Private: files are reached through signed URLs.
INSERT INTO storage.buckets (id, name, public)
VALUES ('invoices', 'invoices', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO storage.buckets (id, name, public)
VALUES ('job-photos', 'job-photos', false)
ON CONFLICT (id) DO NOTHING;

-- 2. settings.enquiry_key was added with a one-off backfill and no default, so
--    every settings row created afterwards got NULL — meaning a new account had
--    no working enquiry link at all. Give it a default and catch any stragglers.
ALTER TABLE public.settings
  ALTER COLUMN enquiry_key SET DEFAULT substr(replace(gen_random_uuid()::text, '-', ''), 1, 18);

UPDATE public.settings
  SET enquiry_key = substr(replace(gen_random_uuid()::text, '-', ''), 1, 18)
  WHERE enquiry_key IS NULL;

-- 3. The public website address. Customer-facing links (the enquiry form, quote
--    accept links) used to be built from window.location.origin, which inside
--    the Android app is the WebView's own https://localhost — so a quote
--    emailed from the phone reached the customer as a dead link. Kept in
--    settings rather than a build variable so it can be changed without
--    rebuilding the app.
ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS public_site_url text;

-- ─────────────────────────────────────────────────────────────────────
-- 20260823150000_business_details.sql
-- ─────────────────────────────────────────────────────────────────────

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

-- ─────────────────────────────────────────────────────────────────────
-- 20260823170000_reply_to_address.sql
-- ─────────────────────────────────────────────────────────────────────

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

-- ─────────────────────────────────────────────────────────────────────
-- 20260824100000_email_delivery_events.sql
-- ─────────────────────────────────────────────────────────────────────

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

-- ─────────────────────────────────────────────────────────────────────
-- 20260826120000_monzo_bank_feed.sql
-- ─────────────────────────────────────────────────────────────────────

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

-- ─────────────────────────────────────────────────────────────────────
-- Mark these migrations as applied, so the CLI agrees with reality if it
-- is ever used against this project.
-- ─────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS supabase_migrations;
CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (
  version text PRIMARY KEY,
  statements text[],
  name text
);

INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815142242', 'e9728a50-33bd-4f4f-af97-04368bfcbb71') ON CONFLICT (version) DO NOTHING;
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
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815200538', 'ab021c2e-4304-4ddd-abc8-0809a52cd748') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260815200555', 'fdfd4163-5294-4c83-b1d9-5a87ffa009ed') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260816051823', 'f6ab4d20-e3ad-4d85-b7aa-3aafc6e8b60b') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260818184700', '9ab9f918-63a7-4b9c-9f20-0652e79af668') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260818184742', '91e41a57-08a0-4aa6-a09f-6e778b236c36') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260822120000', 'quotes') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260822130000', 'fresh_project_bootstrap') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260823150000', 'business_details') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260823170000', 'reply_to_address') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260824100000', 'email_delivery_events') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260826120000', 'monzo_bank_feed') ON CONFLICT (version) DO NOTHING;

COMMIT;
