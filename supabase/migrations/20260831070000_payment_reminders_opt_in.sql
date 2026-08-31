-- Whether this client may be chased about an unpaid invoice.
--
-- payment-reminders runs on a schedule and, until now, emailed every client
-- with an unpaid invoice: fourteen days after it was sent, then every seven
-- days after that, for as long as it stayed unpaid. Nobody chose that per
-- customer and nothing in the app said it was happening — the first anyone
-- knew was a client mentioning they had had a reminder.
--
-- So it is off, and stays off, until it is turned on for a particular client.
-- Default false rather than true: the existing rows are exactly the customers
-- who were being chased without anyone deciding to, and defaulting to true
-- would keep every one of them chased while claiming to have fixed it.
--
-- Sending an invoice by hand is untouched. This is only the automatic chasing.
ALTER TABLE public.clients
  ADD COLUMN IF NOT EXISTS payment_reminders boolean NOT NULL DEFAULT false;
