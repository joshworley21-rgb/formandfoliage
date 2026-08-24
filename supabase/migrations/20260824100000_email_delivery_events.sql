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
