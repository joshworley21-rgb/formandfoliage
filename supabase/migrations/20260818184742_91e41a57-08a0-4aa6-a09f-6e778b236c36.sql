ALTER TABLE public.settings ADD COLUMN IF NOT EXISTS enquiry_key TEXT;
UPDATE public.settings SET enquiry_key = encode(gen_random_bytes(9), 'hex') WHERE enquiry_key IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS settings_enquiry_key_idx ON public.settings(enquiry_key);