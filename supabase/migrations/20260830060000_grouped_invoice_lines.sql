-- Marks an invoice line that stands for several visits at once.
--
-- A grouped line names every day it covers in its own description
-- ("Mow, edge — 4 visits: 5, 12, 19, 26 Aug"). It still carries an item_date,
-- the earliest of those days, because item_date is the only thing invoice
-- lines are ordered by and a null would drop a whole round to the bottom of
-- the invoice, underneath work that happened after it.
--
-- Which meant the first date printed twice: once in the line's date column and
-- again at the head of its own list. This flag lets the invoice sort on the
-- date while leaving it off the page.
--
-- Lines already written are ungrouped by definition of the default: a draft
-- can be rebuilt to pick the flag up, and a sent invoice must not change.
ALTER TABLE public.invoice_items
  ADD COLUMN IF NOT EXISTS grouped boolean NOT NULL DEFAULT false;
