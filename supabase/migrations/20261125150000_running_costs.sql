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
