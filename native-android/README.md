# JW Garden Services CRM — native Android

This is the native Kotlin/Jetpack Compose rewrite. It lives beside the working
web/Capacitor app and shares its Supabase project, database, RLS policies and
Edge Functions. Nothing in this directory is wired into the web build or the
CI workflows yet, so the existing app keeps working while the rewrite lands.

## Status

The foundation is in place:

- Gradle/Compose project with the `uk.co.jwgardenservices.crm` application id
  and the same launcher icon as the current app.
- Brand theme converted from the web app's `oklch` palette
  (`src/styles.css`) to Compose colours, light and dark.
- Supabase Kotlin client configured for PKCE auth and the
  `uk.co.jwgardenservices.crm://password-reset` deep link.
- Email/password sign-in, sign-up and password-reset screens; the app switches
  between the auth screen and the workspace from the SDK's session state.
- Navigation matching the web information architecture: Dashboard, Schedule,
  Clients, Invoices on the phone bar; Enquiries, Quotes, Visits and Settings
  behind More.
- A read-only vertical slice of the main screens: today's round, month totals,
  unbilled work, upcoming jobs, invoices, visits and a searchable client list.
  Clients can be added, edited and deleted from the phone.
- The pure business logic that is easiest to get wrong has been ported with
  tests: money/rounding, visit pricing, visit money fields, quote totals and
  expiry, repeating-series arithmetic (including DST and month-end clamping),
  job/visit pairing, and forgiving search.
- An offline-first data layer: Room caches clients, visits, jobs, invoices and
  settings; writes update the cache immediately and are queued in an outbox;
  WorkManager replays the queue and refreshes the cache when the network
  returns. Signing out clears the device cache.
- The visit log: create, edit and delete unbilled visits, record a missed visit,
  add hours and materials, choose hourly or day-rate billing for that visit
  (defaulting from the client record), and auto-complete the matching scheduled
  job in the same queued batch. Money is locked once the visit is on a sent
  invoice.
- The schedule: book a single job or a whole repeating run, edit/move by
  changing the date and time, complete a job with a new visit or one already
  logged that day, mark it missed, and delete one job or the whole run.
- Move a job quickly with Next day / In 2 days / Next week presets or a date
  picker. The time of day is carried over, and a series visit moves alone.
- Month grid: tap a day to show its jobs in the agenda, drag the month
  backwards/forwards, and long-press a job to move it onto another day. Staff
  can view and select days but cannot drag. Days with more than two jobs show
  `+N`; the agenda below lists every job from the selected day onward.
- Native Material date pickers are used for visit dates, job dates, invoice and
  draft-invoice due dates, and quote validity, while still storing ISO
  yyyy-MM-dd values.
- Appearance: choose Follow system, Light or Dark in Settings. The choice is
  persisted with DataStore and applied immediately.
- Job sheets: tickable tasks (cached in Room and queued offline) and photos
  uploaded to Supabase Storage with signed-URL thumbnails, captions and delete.
  Photos are rotated from EXIF and compressed on-device to at most 1600 px
  before upload; adding photos needs a connection.
- Series-wide edit: move every remaining visit to a new time, and optionally
  re-space the run at a new frequency. Completed and past visits are left alone.
  The full run is fetched from the server when online and the changes are queued
  as one batch.
- Invoices: create from unbilled visits and/or a retainer, render a branded PDF
  on the device with the platform PdfDocument, share it, email it through the
  existing Edge Function, mark paid, and delete. Paid/delete changes are queued
  offline; creating and emailing an invoice need a connection because numbering
  and email delivery are server-side.
- Invoice lines are cached in Room, so a PDF can be rebuilt with no signal.
- Draft invoices can be corrected: change the due date and notes, or rebuild the
  lines from their visits through the existing `rebuild-invoice` function.
  Sending freezes the invoice at what the customer received.
- Invoice email history: each invoice shows its last attempt and an expandable
  history of sent/delivered/opened/clicked/failed events, cached in Room.
- Monzo is not included in this native build for now. Invoices can still be
  marked paid by hand; the bank feed remains available in the web app.
- Quotes: create and edit draft quotes for a client or free text, with lines,
  VAT totals and server-generated numbering; send through the existing
  `send-quote-email` Edge Function with the public accept link; share the link;
  mark accepted or declined; and delete. The send step lets you add a message
  above the standard quote table. Creating/editing/sending need a connection
  because numbering and email are server-side; status changes and deletion are
  queued offline. The customer still accepts on the website.
- Enquiries: the website inbox, with status changes, conversion to a client
  (client + enquiry updated in one queued batch), and delete. Cached in Room.
  Starting a quote from an enquiry prefills the contact details and message and
  links the new quote back to the enquiry. Email and Call shortcuts open the
  phone's mail and dialer apps.
- Settings: business details, sending/reply-to email, website address, VAT,
  invoice prefix, payment terms, bank details, quote defaults and the invoice
  email subject/body templates, plus the ready-made website enquiry link.
  Changes are saved locally and queued.
- Team access: the owner can invite employees by email and remove them. A
  pending invite is linked to the account when that email signs up.
- Owner/staff workspaces: `my_workspace` decides the role. Staff see only
  Schedule and Clients, with names/addresses/notes and no rates or contact
  details. They complete jobs through `staff_complete_job`; owner-only screens
  and cached owner data are hidden and cleared on a staff device.

## Reliability and extras

- Sync safety: a change the server rejects is kept as "failed" (red banner, tap
  to retry or discard) instead of being dropped. Only connection problems, 5xx,
  429 and expired sessions are retried automatically.
- Signing out warns when changes have not synced yet.
- Room has a real migration (10 to 11) and exports its schema to `app/schemas`.
  From version 11 every schema change needs a migration so the outbox survives
  an update.
- Large tables are fetched in 1,000-row pages, so accounts past Supabase's
  response cap no longer lose data.
- Cloud backup and device transfer are disabled (client data and the login
  session stay on the phone).
- Call / text / navigate buttons on client cards and job sheets.
- A daily 18:00 notification built from the offline cache: tomorrow's jobs and
  overdue invoices. Overdue invoices show "Send reminder" on the card.
- Dashboard: six-month invoiced vs paid chart and top clients.
- Settings: CSV export of invoices and visits for your accountant.
- Ready to bill: one tap creates an invoice from every unbilled visit a client
  has, from the top of the Invoices screen.
- Client photo history: the camera icon on a client card shows photos from all
  of their jobs (owner only).
- App lock: optional fingerprint / phone-PIN gate. On by default, toggle in
  Settings → Security; fails open if the phone has no screen lock. Locked
  whenever the app leaves the foreground.
- R8 minification and resource shrinking are on for release builds.
- Crash reporting through Sentry, inactive until a `SENTRY_DSN` GitHub secret
  is set; then the workflow bakes it into the APK automatically.

## Remaining polish

Everything in the original rewrite plan is now native, except Monzo which is
deliberately left on the web for now. The smaller known gaps:

- The month grid shows at most two job chips per day; `+N` points at the agenda.
- Drag-to-move does not auto-scroll near the edges of a long month.
- R8 needs a first run on a device — if the app misbehaves after an update,
  tell me and it can be turned off again.
- Sentry needs a project and a `SENTRY_DSN` secret in the GitHub repo.
- The month grid shows at most two job chips per day; `+N` points at the agenda.

## Code layout

- `CrmRepository.kt` holds the cache, sync plumbing and client CRUD;
  invoices/quotes/enquiries/settings/team live in `CrmRepositoryBilling.kt`
  and jobs/visits/tasks/photos in `CrmRepositoryWork.kt` (extension functions
  on the same class — behaviour unchanged).
- `ScheduleScreen.kt` keeps the month view and agenda; the booking, completion,
  move and series dialogs live in `ScheduleDialogs.kt`.
- Holding a dragged job at the top or bottom edge of the month grid pages the
  month backwards or forwards.

## Build requirements

- JDK 17
- Android SDK Platform 35 and Build Tools 35 (the compile SDK is 35)
- An emulator or device on API 24+

This container has neither a JDK nor an Android platform installed by default,
so the project provisions them under `/tmp` when run here. On a machine with the
toolchain:

```sh
cd native-android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Verified in this environment:

- `:app:compileDebugKotlin` — passes, including Room/KSP generation.
- `:app:testDebugUnitTest` — passes (63 tests).
- `:app:assembleDebug` — compile and test stages pass, but APK packaging is
  killed by this container's memory limit while dexing.

APK packaging is delegated to GitHub Actions, which has enough memory.

## Building the APK with GitHub Actions

The `Native Android APK` workflow (`.github/workflows/native-android.yml`) runs
the unit tests and builds the APK on a GitHub runner. Start it from **Actions →
Native Android APK → Run workflow**, or push a change under `native-android/`.

- If `android/keystore/release.jks` exists and `ANDROID_KEYSTORE_PASSWORD` is
  set, it builds a signed release APK. The native app uses the same application
  id and key as the Capacitor app, so it installs over previous builds.
- Without a key it builds a debug APK, which installs but cannot update over a
  differently-signed build.

Download the APK from the run's **Artifacts** section.

## Configuration

`gradle.properties` carries the public Supabase URL and publishable key, the
same values committed in the web app's `.env`. They are not secrets: row-level
security is what protects the data. Override them in `local.properties` or on
the command line if the project moves.

For password-reset links to return to the app, add this URL to Supabase under
Authentication → URL Configuration → Redirect URLs:

```
uk.co.jwgardenservices.crm://password-reset
```

## Architecture

```
app/src/main/java/uk/co/jwgardenservices/crm/
  core/             build-time configuration
  data/model/       Room entities + Supabase wire models (kotlinx.serialization)
  data/local/       Room database, DAOs and the outbox table
  data/offline/     outbox replay, SyncEngine, SyncWorker/SyncScheduler
  data/remote/      one Supabase client for the app
  data/repository/  AuthRepository, CrmRepository, ServiceLocator
  domain/           pure business rules (money, pricing, visits, series, search)
  ui/               Compose screens, theme and shared components
```

The domain layer is deliberately free of Android types and has no dependency on
Supabase, so the rules can be tested on the JVM and reused by the sync layer.
The UI observes Room through view models, so a local edit is visible before it
has reached the server. The web app's `runOrQueue` is now a Room-backed outbox:
entries are replayed in order, stop at the first network failure, and are
dropped with a surfaced error only when the server permanently rejects them.

## Relationship to the web app

Do not delete the web app until the native rewrite reaches feature parity. The
database, Edge Functions, invoice numbering and Monzo integration stay exactly
as they are; the native app is another client of the same backend.
