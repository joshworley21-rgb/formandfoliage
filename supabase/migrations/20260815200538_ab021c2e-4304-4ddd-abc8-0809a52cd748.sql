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