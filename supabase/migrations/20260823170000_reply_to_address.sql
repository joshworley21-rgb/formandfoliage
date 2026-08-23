-- Let the From address and the reply address differ.
--
-- One field was doing both jobs, so sending invoices from a send-only address
-- would have quietly dropped every customer reply. Nothing needs two addresses
-- today — this stays empty, and everything falls back to `email` — but the
-- fallback is what makes adding an invoices@ alias later a Settings change
-- rather than a code change.

ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS reply_to_email text;

COMMENT ON COLUMN public.settings.email IS
  'The From address on invoice and quote emails. Must be on a domain verified with Resend; it does not need a mailbox.';
COMMENT ON COLUMN public.settings.reply_to_email IS
  'Where customer replies, website enquiries and quote answers land, and the contact address printed on the invoice. Falls back to email when blank.';
