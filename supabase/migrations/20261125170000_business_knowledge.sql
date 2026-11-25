-- What Secretary knows about the business.
--
-- The app already ships a hand-written profile distilled from the website.
-- This column lets the owner replace it with one learned from the live site:
-- services, areas, credentials, opening hours and the way the business talks.
-- Kept on settings rather than in a new table because there is exactly one
-- profile per workspace and it is read on every voice turn.
--
-- Plain text, not JSON: a small prompt-shaped profile is what the assistant
-- consumes, and the owner may want to correct it by hand.

ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS business_profile text,
  ADD COLUMN IF NOT EXISTS business_profile_source text,
  ADD COLUMN IF NOT EXISTS business_profile_updated_at timestamptz;
