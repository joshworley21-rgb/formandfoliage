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

-- An address typed into Settings survives a later migration run: the business
-- details backfill only fills blanks. A migration quietly rewriting the address
-- invoices are sent from would be hard to notice and expensive to discover.
INSERT INTO public.settings (user_id, email, business_name)
  VALUES ('22222222-2222-2222-2222-222222222222', 'billing@example.com', 'Someone Else Ltd');
DO $$
DECLARE _e text; _n text;
BEGIN
  UPDATE public.settings SET
    business_name = COALESCE(NULLIF(btrim(business_name), ''), 'JW Garden Services'),
    email         = COALESCE(NULLIF(btrim(email), ''), 'info@jw-gardenservices.co.uk');

  SELECT email, business_name INTO _e, _n FROM public.settings
    WHERE user_id = '22222222-2222-2222-2222-222222222222';
  IF _e <> 'billing@example.com' OR _n <> 'Someone Else Ltd' THEN
    RAISE EXCEPTION 'the backfill overwrote hand-typed details: % / %', _e, _n;
  END IF;
END $$;
DELETE FROM public.settings WHERE user_id = '22222222-2222-2222-2222-222222222222';

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

-- The business details are pre-filled, so Settings is not an empty form.
DO $$
DECLARE _s record;
BEGIN
  SELECT business_name, address, email, reply_to_email, invoice_prefix,
         payment_terms, bank_details
    INTO _s FROM public.settings
    WHERE user_id = '11111111-1111-1111-1111-111111111111';
  IF _s.business_name IS DISTINCT FROM 'JW Garden Services' THEN
    RAISE EXCEPTION 'business name not pre-filled: %', _s.business_name;
  END IF;
  IF _s.address IS NULL OR _s.address NOT LIKE '%HP22 7DG%' THEN
    RAISE EXCEPTION 'address not pre-filled: %', _s.address;
  END IF;
  IF _s.email IS DISTINCT FROM 'info@jw-gardenservices.co.uk' THEN
    RAISE EXCEPTION 'invoice email not pre-filled: %', _s.email;
  END IF;
  -- One mailbox, so the reply address stays empty and everything falls back to
  -- the address above. A default here would send replies somewhere unread.
  IF _s.reply_to_email IS NOT NULL THEN
    RAISE EXCEPTION 'reply-to should default to empty, got %', _s.reply_to_email;
  END IF;
  IF _s.invoice_prefix IS DISTINCT FROM 'JW-' THEN
    RAISE EXCEPTION 'invoice prefix wrong: %', _s.invoice_prefix;
  END IF;
  IF _s.payment_terms IS NULL THEN RAISE EXCEPTION 'payment terms not pre-filled'; END IF;
  -- The invoice PDF splits these on ":" to build the payment block, so every
  -- line has to be labelled or the invoice shows bare numbers.
  IF _s.bank_details IS NULL
     OR _s.bank_details NOT LIKE '%Sort code: %'
     OR _s.bank_details NOT LIKE '%Account number: %'
     OR _s.bank_details NOT LIKE '%Account name: %' THEN
    RAISE EXCEPTION 'bank details missing or not labelled: %', _s.bank_details;
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
