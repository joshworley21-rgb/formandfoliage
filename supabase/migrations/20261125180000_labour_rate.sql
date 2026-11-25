-- What an hour of the owner's (or a employee's) time costs the business.
--
-- The dashboard can show income, materials and running costs without it, but
-- a profit figure that treats labour as free is not a profit figure. This is a
-- single workspace rate for now; a per-person rate belongs here when the team
-- grows.

ALTER TABLE public.settings
  ADD COLUMN IF NOT EXISTS labour_rate numeric(10,2) NOT NULL DEFAULT 0;
