ALTER TABLE public.settings ADD COLUMN IF NOT EXISTS enquiry_key TEXT;
-- gen_random_uuid() is core Postgres; pgcrypto's gen_random_bytes lives in the
-- extensions schema and is only reachable when that is on the search_path,
-- which made this statement fail when replayed against a fresh project.
UPDATE public.settings SET enquiry_key = substr(replace(gen_random_uuid()::text, '-', ''), 1, 18) WHERE enquiry_key IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS settings_enquiry_key_idx ON public.settings(enquiry_key);