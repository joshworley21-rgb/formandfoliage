-- A visit that did not happen, recorded rather than forgotten.
--
-- Turning up to a locked gate, or a garden too waterlogged to touch, is worth
-- writing down: it explains a gap in the round to anyone looking back, and it
-- is the answer to "did you come in March?". Until now the only ways to record
-- one were to log it and remember never to bill it, or not to log it at all —
-- one of which quietly overcharges a client and the other loses the history.
--
-- Kept apart from `billed`, deliberately. Marking a missed visit as billed
-- would say an invoice covered it, which is untrue and would confuse the
-- reconciliation the moment anyone looked. This says what actually happened
-- and every count of unbilled work skips it.

ALTER TABLE public.visits
  ADD COLUMN IF NOT EXISTS missed boolean NOT NULL DEFAULT false;

-- Every "what is waiting to be invoiced" query is now "not billed and not
-- missed", on a table that grows by a few rows a day forever.
CREATE INDEX IF NOT EXISTS visits_billable_idx
  ON public.visits(user_id, visit_date DESC)
  WHERE billed = false AND missed = false;
