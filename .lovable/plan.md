# JW Garden Services — CRM

A private, mobile-friendly CRM for tracking clients, visits and hours, and sending finished invoices as a PDF by email in one click.

## Brand

Your JW logo (leaf mark, mid green + dark brown) drives the design: deep green primary, warm brown accent, cream/off-white surfaces. Logo in the app header and on every invoice PDF; also used as the favicon.

## Screens

**Sign in** — single private login for you (email + password). Nobody else can see the data.

**Dashboard** — this week's visits and hours, unbilled totals per client, quick "Log a visit" button.

**Clients** — name, address, phone, email, notes, and billing setup: hourly rate, fixed per-visit price, or both (some clients hourly, some fixed).

**Visits** — log manually: client, date, hours worked, work done, and any extra materials/charges. Each visit is priced automatically from that client's billing setup and marked unbilled or billed.

**Invoices** — pick a client, tick the unbilled visits (or "select all"), preview the invoice, then one click to email the PDF to the client. Invoice list shows sent date, total, and status (sent / paid), with a manual "mark as paid" toggle.

## One-click invoicing

Pressing Send: generates a branded PDF (logo, your business details, itemised visits with dates/hours/prices, totals), emails it to the client's address with a short covering message, saves a copy against the invoice record, and marks those visits billed. You can also download the PDF instead of emailing.

Invoice numbering is sequential (JW-0001, JW-0002…).

## Recurring invoices

Any client can be put on a repeating schedule: weekly, fortnightly, every 4 weeks, monthly, quarterly, or a custom interval, with a start date and optional end date.

On each due date the app builds the invoice automatically from that period's logged visits (plus any fixed retainer amount for the client) and either sends it straight away or drops it in a "Ready to send" list for you to approve with one tap — your choice per client. If a period has no visits and no fixed charge, it's skipped rather than sending a £0 invoice.

A Recurring tab shows every schedule, its next due date, and lets you pause, edit, or skip the next run.

## Scheduling and calendar

**Schedule view** — month, week and day calendars showing planned jobs, colour-coded by status (scheduled, done, missed, cancelled). On the phone, the day and week views are the default with a simple agenda list; month gives the overview.

**Booking a job** — tap a day (or "New job") to schedule a client, date, start time, expected duration and notes. Jobs can repeat on their own maintenance pattern — weekly, fortnightly, every 4 weeks, monthly — generating the upcoming visits automatically. Drag or edit to move a job to another day; editing a repeat asks whether to change just that one or the whole series.

**Closing the loop** — from a scheduled job you tap "Complete" and confirm the actual hours worked, which turns it into a logged visit ready for invoicing. Jobs you didn't get to can be marked missed or rescheduled, so nothing silently disappears.

**Dashboard** gains a "Today / next 7 days" round list with client address and phone for quick directions and calls, plus a count of overdue jobs still awaiting completion.



## What's needed from you

- Lovable Cloud is enabled for the database, your login, and PDF storage.
- Email sending needs a Resend account (free tier is fine) and a verified sender address — I'll walk you through it and ask for the API key at that step.
- Your business details for the invoice footer: trading address, phone, and whether you're VAT registered.

## Technical notes

- TanStack Start routes: `/auth` (public), `/_authenticated/` for dashboard, clients, visits, invoices, recurring.
- PDF generated in a server function and stored in a private Cloud storage bucket; emailed via Resend from the same server function.
- Cloud tables: `clients`, `visits`, `invoices`, `invoice_items`, `recurring_schedules`, `settings` — all RLS-scoped to your user id, with explicit grants.
- Visit pricing derived server-side from the client's `billing_mode` (`hourly` | `fixed` | both), stored as a snapshot on the invoice item so later rate changes don't rewrite history.
- Recurring runs handled by a scheduled job (pg_cron) hitting a `/api/public/run-recurring-invoices` route protected by a shared secret; it computes due schedules, builds invoices idempotently per period, and auto-sends or queues based on the schedule's setting.
