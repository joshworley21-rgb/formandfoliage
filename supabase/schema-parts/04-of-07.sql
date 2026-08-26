-- JW Garden Services CRM schema — part 4 of 7.
--
-- GENERATED FILE. Run the parts in order, one at a time, in the Supabase
-- SQL Editor. Each part is its own transaction: if one fails, nothing in
-- that part is applied and you can fix it and run the same part again.
--
-- Only needed if you are building the database by hand. The
-- "Set up Supabase" GitHub Action does all of this without any pasting.

BEGIN;

-- ---------- 20260816051823_f6ab4d20-e3ad-4d85-b7aa-3aafc6e8b60b.sql ----------

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

-- ---------- 20260818184700_9ab9f918-63a7-4b9c-9f20-0652e79af668.sql ----------

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

-- ---------- 20260818184742_91e41a57-08a0-4aa6-a09f-6e778b236c36.sql ----------

ALTER TABLE public.settings ADD COLUMN IF NOT EXISTS enquiry_key TEXT;
-- gen_random_uuid() is core Postgres; pgcrypto's gen_random_bytes lives in the
-- extensions schema and is only reachable when that is on the search_path,
-- which made this statement fail when replayed against a fresh project.
UPDATE public.settings SET enquiry_key = substr(replace(gen_random_uuid()::text, '-', ''), 1, 18) WHERE enquiry_key IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS settings_enquiry_key_idx ON public.settings(enquiry_key);

CREATE SCHEMA IF NOT EXISTS supabase_migrations;
CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (
  version text PRIMARY KEY,
  statements text[],
  name text
);

INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260816051823', 'f6ab4d20-e3ad-4d85-b7aa-3aafc6e8b60b') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260818184700', '9ab9f918-63a7-4b9c-9f20-0652e79af668') ON CONFLICT (version) DO NOTHING;
INSERT INTO supabase_migrations.schema_migrations (version, name)
  VALUES ('20260818184742', '91e41a57-08a0-4aa6-a09f-6e778b236c36') ON CONFLICT (version) DO NOTHING;

COMMIT;
