-- Split "who the email comes from" out from "where replies should go".
--
-- Until now one address did both jobs, which forces a choice: send invoices
-- from invoices@ and lose replies, or send from info@ and mix invoice traffic
-- in with general enquiries. Two columns lets a customer see invoices@ on the
-- invoice and still have Reply land in whichever inbox is being watched.

ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS reply_to_email text;

ALTER TABLE public.settings
  ALTER COLUMN email SET DEFAULT 'invoices@jw-gardenservices.co.uk',
  ALTER COLUMN reply_to_email SET DEFAULT 'info@jw-gardenservices.co.uk';

COMMENT ON COLUMN public.settings.email IS
  'The From address on invoice and quote emails. Must be on a domain verified with Resend.';
COMMENT ON COLUMN public.settings.reply_to_email IS
  'Where customer replies land, and the contact address printed on the invoice. Falls back to email when blank.';

-- Move an existing row over, but only where the address is still the one this
-- project shipped as a default. Anything typed into Settings is left exactly as
-- it is, and a null reply_to_email keeps the old single-address behaviour.
UPDATE public.settings SET
  email          = 'invoices@jw-gardenservices.co.uk',
  reply_to_email = 'info@jw-gardenservices.co.uk'
WHERE btrim(COALESCE(email, '')) IN ('', 'info@jw-gardenservices.co.uk');
