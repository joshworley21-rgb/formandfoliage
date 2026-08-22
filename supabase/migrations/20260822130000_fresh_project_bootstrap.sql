-- Things a working project needs that no migration ever created, because they
-- were set up by hand in the old hosting provider's dashboard. Without these a
-- brand-new Supabase project comes up subtly broken.

-- 1. Storage buckets. Policies for both already exist in earlier migrations,
--    but the buckets themselves did not, so invoice PDFs and job photos had
--    nowhere to upload to. Private: files are reached through signed URLs.
INSERT INTO storage.buckets (id, name, public)
VALUES ('invoices', 'invoices', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO storage.buckets (id, name, public)
VALUES ('job-photos', 'job-photos', false)
ON CONFLICT (id) DO NOTHING;

-- 2. settings.enquiry_key was added with a one-off backfill and no default, so
--    every settings row created afterwards got NULL — meaning a new account had
--    no working enquiry link at all. Give it a default and catch any stragglers.
ALTER TABLE public.settings
  ALTER COLUMN enquiry_key SET DEFAULT substr(replace(gen_random_uuid()::text, '-', ''), 1, 18);

UPDATE public.settings
  SET enquiry_key = substr(replace(gen_random_uuid()::text, '-', ''), 1, 18)
  WHERE enquiry_key IS NULL;
