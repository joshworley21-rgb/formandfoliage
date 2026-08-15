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