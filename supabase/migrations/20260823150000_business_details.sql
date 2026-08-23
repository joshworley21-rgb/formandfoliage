-- Pre-fill the business details so Settings is populated the first time it is
-- opened, rather than being a form to fill in on a phone.
--
-- These are column defaults rather than values in the app, so they apply to the
-- settings row whenever it is created, and they never ship inside the APK.

ALTER TABLE public.settings
  ALTER COLUMN address SET DEFAULT '24 Portland Close
Bierton
HP22 7DG',
  ALTER COLUMN email SET DEFAULT 'info@jw-gardenservices.co.uk',
  ALTER COLUMN payment_terms SET DEFAULT 'Payment due within 14 days.',
  -- The invoice PDF reads this as "Label: value" lines, so labelling them here
  -- is what turns it into a payment block rather than loose numbers.
  ALTER COLUMN bank_details SET DEFAULT 'Account name: Joshua Worley trading as JW Garden Services
Sort code: 04-00-03
Account number: 64775630';

-- Fill in a settings row that already exists, but only where a field is still
-- empty — anything already typed in the app wins over these.
UPDATE public.settings SET
  business_name = COALESCE(NULLIF(btrim(business_name), ''), 'JW Garden Services'),
  address       = COALESCE(NULLIF(btrim(address), ''), '24 Portland Close
Bierton
HP22 7DG'),
  email         = COALESCE(NULLIF(btrim(email), ''), 'info@jw-gardenservices.co.uk'),
  invoice_prefix = COALESCE(NULLIF(btrim(invoice_prefix), ''), 'JW-'),
  payment_terms = COALESCE(NULLIF(btrim(payment_terms), ''), 'Payment due within 14 days.'),
  bank_details  = COALESCE(NULLIF(btrim(bank_details), ''), 'Account name: Joshua Worley trading as JW Garden Services
Sort code: 04-00-03
Account number: 64775630');

-- Phone is deliberately left blank: no number was given. Add it in Settings and
-- it will appear on invoices alongside the address.
