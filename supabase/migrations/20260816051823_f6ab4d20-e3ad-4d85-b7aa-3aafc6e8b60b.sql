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