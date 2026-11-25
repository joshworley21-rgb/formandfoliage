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
- Navigation: Dashboard, Schedule, Business and Invoices on the phone bar;
  Clients, Enquiries, Quotes and Visits behind More; Settings in the top bar.
  Business is the old Assistant slot, reworked into running costs, the
  business coach and Secretary (the voice assistant).
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

## Tax set aside

- Every non-draft invoice shows "Save for tax: ~£X" at the rate chosen in
  Settings → Tax (default 30%, stored on the device).
- The dashboard shows a tax-year pot: 30% of invoiced for the current UK
  self-assessment year (6 April – 5 April), drafts excluded.
- The dashboard also estimates the HMRC bill: income tax after the £12,570
  allowance (20%/40%/45%) plus Class 4 NICs (6%/2%), on estimated profit —
  net invoiced minus materials logged, for the tax year. Assumes no other
  income and England/Wales/NI rates; 2025/26 frozen thresholds.
- The percentage is a rule of thumb — tax is on profit, so the knob absorbs
  running costs. It is guidance, not a calculation.

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
- Dashboard: today's jobs with client names, the week's job count, the
  six-month invoiced vs paid chart and top clients. Settings is in the
  top-bar gear only.
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

## Schedule, routes and messages

- **Route**: tapping a day in Schedule shows a **Route** button above its jobs.
  It lists that day's stops in time order and opens Google Maps directions
  with the client addresses as waypoints (up to nine; the rest are listed and
  skipped). No maps key and no server call are involved — the phone's maps app
  does the work. Jobs without an address are shown but left out of the route.
- **Text and WhatsApp**: the message icon on a client, in a job sheet, and on
  each route stop opens an editable draft. The draft can go to the phone's SMS
  app or WhatsApp (`wa.me`, with UK leading zeros converted to `+44`). Messages
  are never sent automatically: the app opens with the draft and the owner
  taps send. The job sheet and route stops prefill "I'm on my way"; the client
  card prefills a greeting.

## Business running

- The **Business** tab is the money screen for the owner: **Overview**,
  **Costs**, **Coach** and **Secretary**. Overview leads with this month's net position
  (logged work minus materials and recurring costs), the health score and the
  cost breakdown; Costs stores the recurring bills and converts every
  frequency to a monthly equivalent; Coach lists what needs attention, what
  is an opportunity and what is already working.
- Running costs are real synced records (`running_costs`), not a local note.
  They are owner-only, follow the same offline outbox as visits and invoices,
  and are added or edited from the Costs tab.
- The health score is calculated on the phone from five things: overdue cash,
  net margin, running-cost share, unbilled work and how full the week is. No
  model or server call is involved.
- **Secretary** is the voice assistant inside Business. It is also on the mic
  button in the Dashboard header. Common business questions are answered
  locally from the CRM and the stored profile: services, areas, credentials,
  running costs, monthly profit, break-even, the lowest-earning client, what
  to chase first and the health score. These never need the cloud.
- **Teaching it the business**: Settings → Business knowledge. There are two
  scans:
  - **Scan on phone** is free: the app reads the home page plus about,
    services, areas and contact pages with OkHttp, strips the HTML and keeps
    the service names, places, credentials and contact lines it can see. No
    API key, no OpenAI charge.
  - **Use AI** sends the same public pages to the `business-profile` Edge
    Function for a better-written profile. If that route is unavailable it
    falls back to the free scan automatically.

  Both write the profile to the settings row; Secretary prefers it over the
  bundled `business_knowledge.txt`, which remains the fallback. The profile
  can be edited or cleared by hand, and nothing crawls in the background.
  Deploy `business-profile` and apply the `business_knowledge` migration for
  the AI option; the `OPENAI_API_KEY` secret is shared with `voice-route`.

## Voice assistant (offline speech to text)

- The **Secretary** screen (Business → Secretary, or the mic in the Dashboard
  header) is laid out like a phone call: a status orb, a rolling transcript
  and one button. Opening it is silent and does not dial; pressing the green
  **Call** button starts the foreground service, plays a UK ringback while the
  recogniser and voice warm up, then the assistant answers and the orb becomes
  **Connected**. The microphone permission is requested on that first call,
  not on opening the screen. Speech is recognised entirely on the phone and
  partial words appear as an italic bubble while the recognizer is still
  deciding. Staff keep their shorter bar.
- **The call survives the screen.** Leaving the Secretary screen unbinds the screen
  but does not stop a connected call; the microphone closes while you are
  elsewhere and reopens on return. The recognizer itself is process-scoped
  (`SpeechEngine`), so even **End call** followed by a new call reuses the
  loaded 190 MB model — it is rebuilt only when Android kills the app.
  **End call** closes the mic, stops any ringback and drops the notification;
  the screen then offers the green **Call** button again.
- **Rolling transcript**: every recognised sentence and every spoken reply is
  kept in the service (last 40 turns) and shown as a bubble, so the question
  does not disappear when the answer is read out. Leaving and re-entering the
  tab keeps the conversation; the bin icon clears it.
- **Endpoint detection** commits a line 0.4 s after you stop speaking (or
  0.65 s of trailing silence before any words were recognised), so each
  command is finalised without a tap. Shortening these is the cheapest
  latency win in the whole pipeline; any shorter starts cutting people off
  mid-sentence.
- **Returning to the app**: the task is retained (`alwaysRetainTaskState`) and
  the last signed-in workspace is cached process-wide, so a recreated
  activity renders the workspace immediately instead of flashing the
  "Opening your workspace…" splash while the session revalidates. With no call
  active, the assistant releases the recogniser and voice after 30 s in the
  background: a resident 190–660 MB model makes the process the first thing
  Android reclaims, which cold-starts the app on return. The next call
  reloads behind the ringback.
- **Call screen**: the Secretary screen is a dark call sheet — a glowing orb,
  "Secretary", a ticking call timer, animated speaking bars, a status
  pill, the rolling transcript, and Mute / Speaker / End controls. Mute stops
  the recogniser loop rather than just hiding it; Speaker routes to the
  loudspeaker best-effort; End hangs up and stops speech immediately.
- **Dialling and pickup**: the green button rings out with a generated UK
  ringback (400 + 450 Hz, 0.4/0.2/0.4 s cadence) instead of showing a model
  loading spinner. When the recogniser is listening it stops, the status
  changes to Dialling… → Connected, and the assistant answers with "Hello,
  how can I help?" before reopening the mic. The greeting is also shown in
  the transcript. The clip is generated with the cached
  filler, so it costs nothing on later calls. Re-entering the tab does not
  repeat it, and it is skipped if the caller has already started speaking.
  Settings → Secretary settings → Call turns the greeting off (the ringback
  remains).
- **Warm start**: once the assistant has been used, the app loads the
  recogniser in the background after launch, so a returning caller opens the
  tab to a warm model instead of watching the model load. The voice warms in
  parallel at call start.
- **First reply**: the business snapshot (Room + DataStore reads) is built
  only when a turn actually goes to the cloud. The local commands — diary,
  hours, notes, invoices, takings — answer without it. A cloud turn still
  covers its wait with the cached "Let me check." after 300 ms.
- **Speech models**: none are bundled. The default is **Moonshine v2 base**
  (135 MB, offline, English), downloaded from Settings; **Nemotron 3.5
  streaming** (662 MB, true streaming with live words) is the optional
  alternative. Only the selected engine is held in memory, and it is released
  30 s after the app goes to the background so the process stays small.
- **Client-name biasing**: two layers. First, each call builds a `bpe.vocab`
  from the model's `tokens.txt` (equal scores, so ssentencepiece segments by
  longest match — the pattern sherpa's own CI uses) and passes the names to
  `createStream("SMITHS :2.0\n…")`, which biases the decoder while it is
  listening. Longest match does not always equal the model's true merges, so
  a second layer replaces near misses in the final transcript with the
  canonical name (one edit for five-letter names, two for nine or more). The
  correction also applies to Moonshine. Both layers are logged.
- **Nemotron 3.5 (experimental)**: Settings → Secretary settings → Nemotron
  ASR downloads NVIDIA Nemotron 3.5 Speech Streaming 0.6B int8 (English/en
  only, about 662 MB) over Wi-Fi or mobile data. It is a **true streaming**
  online transducer, so it produces live partial words, from a 2026-era model
  trained for noisy phone audio. It holds about 650 MB
  in memory while selected and is released when switching back. NVIDIA Open
  Model License, commercial use permitted.
- **Quick resume**: a new call checks whether the selected recogniser and the
  voice are already loaded. If they are, it skips the dial tone and connects
  straight to the pickup; only a cold call rings out while the models load.
- **Moonshine v2 (experimental)**: Settings → Secretary settings →
  Moonshine ASR downloads the 135 MB base English model (MIT) over Wi-Fi or
  mobile data. It is
  offline, so it has no live partial words; the call buffers one utterance
  and decodes it after the VAD endpoint. It is the default engine, loaded
  during the ringback, and can be removed from the same card.
- **Not in git**: the sherpa AAR and the Kokoro fallback model are binaries;
  the 640 KB Silero VAD model is checked in. The Phonon runtime is compiled by
  the APK workflow from the pinned xn-ptts tag. `:app:downloadSherpa` fetches
  the AAR and `:app:downloadKokoro` extracts the Kokoro archive (SHA-256
  checked); `preBuild` depends on both, so a fresh clone needs a connection
  for its first build only.
- **Command classifier**: the deterministic rules answer the common commands
  instantly on the phone. Anything they cannot place goes to the optional
  cloud classifier, or gets a short help line. The local Qwen GGUF and the
  llama.cpp AAR were removed in the audit: the model was never on the runtime
  path and cost a 469 MB download, and the rules plus cloud cover every
  command.
- **Local actions**: `VoiceActionRunner` answers the diary and money
  questions straight from the Room cache — "what's next" (next booked job),
  "where's my next job" (its address), "what have I got on" for today,
  tomorrow, a weekday, this week or next week, "when is Smiths next in" (that
  client's next booking), "mark Smiths done", "who owes me" (unpaid invoice
  totals by client, or one client when named), "has Smiths' invoice been
  paid", "what's ready to invoice" (unbilled work by client), "what have I
  earned this week" / "last month" / "today", "how many hours this week",
  "when did I last visit Smiths", "Smiths' phone number/address", "who's my
  best client", "who are my clients", and "note for Smiths that the gate
  sticks" (a dated line appended to their notes). Two commands joined by
  "then", "and then" or "and" (when both sides are commands) run in order and
  produce one combined reply. A command missing a slot — "log two hours" with
  no client, or "log Smiths" with no hours — is remembered and completed by
  the next sentence ("two and a half"), so the conversation takes two turns
  instead of failing. Client names are matched with a one-letter tolerance
  for names of five letters or more.
- **Confirmation and undo**: "mark Smiths done" changes money, so the
  assistant asks "Mark Smiths's job done, three hours? Say yes to confirm."
  Only yes/no completes it; anything else cancels and is handled as a new
  command. After a logged visit or client note, "undo" / "undo that" /
  "scratch that" reverses it (the visit is deleted, the note restored). Both
  run before the classifier, so the pending action cannot be lost.
- **Memory and business context**: before each sentence the assistant is given
  a compact snapshot — business name and VAT, today's date, up to 30 client
  names, the next five jobs, what is owed, this week's takings and anything
  the owner asked it to remember. "Remember that the Smiths' gate sticks"
  stores a local fact; "what do you remember" reads them back; "forget …" or
  "forget everything" removes them. Facts and the last 24 turns live in a
  DataStore file (`assistant_memory`), so they survive restarts. Nothing is
  sent anywhere unless a turn goes to the cloud.
- **Semantic fallback**: between the rules and the cloud, a small weighted
  phrase matcher expands trade slang into synonym groups (log/stick/put/chalk,
  done/sorted/squared, owe/outstanding/unpaid) and scores it against a
  curated corpus of British phrasings. It answers locally only when the match
  is clear and ahead of the runner-up — reads at 0.60, writes at 0.70 — so
  "stick Simon down for a half day" is understood without a cloud call.
  Ambiguous turns still go to `voice-route`. It is not an LLM: no download,
  no model, well under a millisecond.
- **Conversational follow-up**: a finished answer ends with a short
  "Anything else?" (rotated through a few phrasings), so the call keeps its
  rhythm instead of going quiet. Replies that already end in a question —
  slot filling, confirmation, help — are left alone. Settings → Call turns it
  off. The mic reopens after the nudge as usual.
- **Conversational confirmation**: "mark Smiths done" accepts natural
  affirmatives ("yeah spot on", "do it", "cheers"), and a changed duration
  ("no, four hours", "make it four") updates the pending hours and re-confirms
  instead of cancelling. Only an explicit "no", "leave it" or "forget it"
  cancels; anything else is handled as a new command.
- **Fast path**: the deterministic rules answer the common, unambiguous
  commands (hours, diary, next job, money owed, week's takings) immediately.
  Anything else is classified by the cloud function in about a second. If the
  cloud is unavailable the rules answer, and an unrecognised sentence gets a
  short apology. There is no on-device language model: a 0.5B took 19 s to
  classify "hello" on this phone, so it was removed rather than left as a
  trap.
- **Open conversation** (off by default): when the rules cannot place a
  sentence, the assistant can send it to `voice-route` with the business
  snapshot and the last six turns. The function classifies and, if the turn
  needs an answer, produces it in the **same response**, so a sentence costs
  one request and about a second. The switch is labelled with its cost; left
  off, unrecognised sentences get an instant one-line help reply and nothing
  leaves the phone.
- **Hybrid routing**: the classifier sets `requires_cloud: true` for live pricing,
  supplier data, dynamic quote calculations, weather/traffic or anything not
  stored on the phone. The service then says "Checking online rates…" and
  `OpenAiCloudRouter` posts the turn to the `voice-route` Edge Function, which
  calls OpenAI (`gpt-4o-mini`) with two function tools —
  `fetch_live_pricing(material_type)` and
  `calculate_commercial_quote(client, scope)`. Only those turns leave the
  phone; everything else stays local. The returned sentence is read aloud by
  Kokoro. Tool results come from an illustrative rate card in the function
  (labelled as an estimate); swap `RATE_CARD`/`calculateQuote` for a real
  supplier API when one exists.
- **Cloud key**: no OpenAI key is baked into the APK. It lives in the
  Supabase project as a secret (`supabase secrets set OPENAI_API_KEY=...`)
  and is only reachable by a signed-in user's token through `voice-route`.
  Without the secret, cloud-routed turns speak "I couldn't reach the online
  rates" and the local path keeps working.
- The streaming recogniser is created from the downloaded model files. Its
  endpoint detector (roughly 0.4–0.65 s of trailing silence) decides the
  speaker has stopped and commits the line; Silero VAD is the primary
  endpoint.
- **Settings → Secretary settings** opens the voice controls: Phonon 31m and
  its eight catalog voices, the call options and the memory/cloud switches.
  A preview button auditions the selected Phonon voice.
- **Conversation flow**: there is no extra "Got it." before local answers —
  the reply itself is spoken as soon as the action finishes. Only a
  cloud-routed turn says "Checking online rates…" first.
- **Audio focus**: transient focus is requested for each spoken sentence and
  released as soon as it finishes, so a call or the van's media takes priority
  without being paused for the whole time the call is connected; losing focus
  stops Kokoro mid-sentence.
- **Voice**: reply text is synthesised by **Phonon 31m** (Apache 2.0,
  ~62 MB, 24 kHz float PCM streamed while it speaks) through the Rust
  runtime built by the APK workflow and called via JNA. Kokoro int8 stays
  only as an internal fallback if Phonon fails to load; it is not offered in
  Settings and never warms unless needed. Audio focus is transient per
  sentence, microphone frames are discarded while the assistant speaks, and
  the mic reopens when playback finishes.
- **Phonon 31m** (optional, Apache 2.0, Settings → Secretary settings →
  Phonon 31m): about 62 MB and streamed as 24 kHz float PCM while it speaks.
  The Rust runtime (`libptts_ffi.so`) is built for arm64-v8a by the APK
  workflow — no native binary is committed — and called through JNA. While
  it is on it replaces the other local voices; Kokoro remains the fallback.
  The 40m models are CC BY-NC and deliberately not offered. Settings →
  Phonon 31m has a **Voice** picker limited to eight catalog voices: Zoey,
  Easton, Juniper, Micah, Maeve, Freya, Alfie and Hugh. Selecting one
  downloads its ~256 KB `voices/<name>.safetensors` file, reloads the engine
  and the **Preview Phonon voice** button auditions it. "Remove old model
  downloads" deletes any voice outside that list.
- **Call feel**: the microphone runs on `VOICE_COMMUNICATION` with
  `AcousticEchoCanceler` kept on for barge-in, while `AutomaticGainControl` is
  explicitly disabled — Android's AGC ducks and clips the speech harmonics on
  that source, which the recogniser reads as worse audio. Talking over it for
  about 300 ms cuts playback and hands the floor back immediately. Turn ends
  are detected by **Silero VAD** (a 640 KB
  `silero_vad.onnx` checked into assets) with the recognizer's endpoint as a
  fallback. Replies split at clauses and conjunctions every ~6 words so
  the first audio starts sooner, and four cached "thinker" clips ("Let me
  check.", "One moment.", …) rotate to cover a slow cloud turn. Settings →
  Microphone switches to `VOICE_RECOGNITION` for accuracy at the cost of
  barge-in.
- `:app:downloadKokoro` extracts only the English pieces from
  `kokoro-int8-multi-lang-v1_0.tar.bz2`: `model.int8.onnx` (109 MB),
  `voices.bin` (27 MB), `tokens.txt`, the US/GB lexicons and the 19 MB
  `espeak-ng-data` phonemizer. The Chinese dictionaries are skipped. int8
  keeps the APK installable; the optional fp32 model comes from the Storage
  bucket when it has been published, and from the same release otherwise.
- LiveKit, Vosk, the VPS call path, the Pipecat WebSocket session and the
  JitPack repository they needed have all been removed. Speech runs on the
  vendored official `sherpa-onnx-1.13.8.aar`; neither model weights nor the
  AAR are committed.
- Known limits: the recognisers are English-only, punctuation and casing are
  not restored, and the first call after a cold start waits for the selected
  model to load behind the ringback.
- sherpa-onnx is Apache 2.0; Moonshine is MIT; Nemotron follows the NVIDIA
  Open Model License; Phonon is Apache 2.0; Kokoro-82M is Apache 2.0.

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

The CI workflow publishes the installable APK as the `jw-crm-native-apk`
artifact on every native-android change.
