-- What the materials on a visit actually were.
--
-- Until now a visit recorded only a materials figure, so an invoice could say
-- a customer owed £18 for materials with nothing anywhere saying what they
-- were — not on the invoice, and not in the app three months later when they
-- ask. This is the line that answers it: "2 bags compost, bark chippings".
--
-- Free text rather than a table of priced rows. Nobody itemises a bag of
-- compost on a phone in a garden, and a sentence that gets written beats a
-- structure that does not.
ALTER TABLE public.visits
  ADD COLUMN IF NOT EXISTS materials_note text;
