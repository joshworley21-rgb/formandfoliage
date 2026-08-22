-- Behavioural checks against a database built purely from the migrations.
-- Every check raises on failure, so the script's exit code is the result.
\set ON_ERROR_STOP on

INSERT INTO auth.users (id, email) VALUES
  ('11111111-1111-1111-1111-111111111111','owner@example.com'),
  ('22222222-2222-2222-2222-222222222222','other@example.com');

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated;

-- Storage buckets exist. Their policies were written long before anything
-- created the buckets themselves.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM storage.buckets WHERE id = 'invoices') THEN
    RAISE EXCEPTION 'invoices bucket missing';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM storage.buckets WHERE id = 'job-photos') THEN
    RAISE EXCEPTION 'job-photos bucket missing';
  END IF;
  IF EXISTS (SELECT 1 FROM storage.buckets WHERE public) THEN
    RAISE EXCEPTION 'a bucket is public; invoices and job photos must not be';
  END IF;
END $$;

SET ROLE authenticated;
DO $$ BEGIN PERFORM set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111', false); END $$;

-- A new account must get a usable enquiry link without a backfill.
INSERT INTO public.settings (user_id) VALUES ('11111111-1111-1111-1111-111111111111');
DO $$
DECLARE _key text;
BEGIN
  SELECT enquiry_key INTO _key FROM public.settings
    WHERE user_id = '11111111-1111-1111-1111-111111111111';
  IF _key IS NULL OR length(_key) < 12 THEN
    RAISE EXCEPTION 'new settings row has no usable enquiry_key (got %)', _key;
  END IF;
END $$;

-- Quote numbers run in sequence.
DO $$
DECLARE _a text; _b text;
BEGIN
  _a := public.next_quote_number('11111111-1111-1111-1111-111111111111');
  _b := public.next_quote_number('11111111-1111-1111-1111-111111111111');
  IF _a <> 'Q-0001' OR _b <> 'Q-0002' THEN
    RAISE EXCEPTION 'quote numbering wrong: % then %', _a, _b;
  END IF;
END $$;

-- Every quote gets its own unguessable public key from the column default.
INSERT INTO public.quotes (user_id, number, contact_name, total) VALUES
  ('11111111-1111-1111-1111-111111111111','Q-0003','Mrs Green',250.00),
  ('11111111-1111-1111-1111-111111111111','Q-0004','Mr Brown',120.00);
DO $$
DECLARE _distinct int; _rows int; _shortest int;
BEGIN
  SELECT count(DISTINCT public_key), count(*), min(length(public_key))
    INTO _distinct, _rows, _shortest FROM public.quotes;
  IF _distinct <> _rows THEN RAISE EXCEPTION 'public_key values are not unique'; END IF;
  IF _shortest < 24 THEN RAISE EXCEPTION 'public_key too short: %', _shortest; END IF;
END $$;

-- Deleting a quote takes its lines with it.
INSERT INTO public.quote_items (user_id, quote_id, description, quantity, unit_price, amount)
SELECT '11111111-1111-1111-1111-111111111111', id, 'Hedge cutting', 3, 25, 75
  FROM public.quotes WHERE number = 'Q-0003';
DELETE FROM public.quotes WHERE number = 'Q-0003';
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM public.quote_items) THEN
    RAISE EXCEPTION 'quote_items outlived their quote';
  END IF;
END $$;

-- RLS keeps one account's quotes away from another's.
DO $$ BEGIN PERFORM set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222', false); END $$;
DO $$
DECLARE _visible int;
BEGIN
  SELECT count(*) INTO _visible FROM public.quotes;
  IF _visible <> 0 THEN
    RAISE EXCEPTION 'RLS leak: another account can see % quote(s)', _visible;
  END IF;
END $$;

-- And the numbering guard stops one account bumping another's counter.
DO $$
BEGIN
  PERFORM public.next_quote_number('11111111-1111-1111-1111-111111111111');
  RAISE EXCEPTION 'next_quote_number let one account bump another''s counter';
EXCEPTION WHEN sqlstate 'P0001' THEN
  IF sqlerrm <> 'Not permitted' THEN RAISE; END IF;
END $$;

-- Invoice numbering stays service_role only, unlike quote numbering.
DO $$
BEGIN
  PERFORM public.next_invoice_number('22222222-2222-2222-2222-222222222222');
  RAISE EXCEPTION 'next_invoice_number should not be callable by authenticated';
EXCEPTION WHEN insufficient_privilege THEN
  NULL;
END $$;

RESET ROLE;
\echo 'All schema checks passed.'
