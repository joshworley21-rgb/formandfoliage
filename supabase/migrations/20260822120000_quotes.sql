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
