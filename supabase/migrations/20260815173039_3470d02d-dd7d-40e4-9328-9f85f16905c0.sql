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