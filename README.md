# JW Garden Services CRM

A CRM for a gardening business: clients, a visit schedule, hours and materials,
one-click invoicing, quotes customers can accept online, and a website enquiry
inbox.

It runs as a standalone Android app and as a website, from the same code. The
only thing behind it is Supabase — there is no server of our own to keep alive.

## How it fits together

| Piece                                   | What it does                                                                                                                                                                               |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| React SPA (TanStack Router/Start, Vite) | The whole app. Runs in the browser or inside the Android WebView.                                                                                                                          |
| Supabase                                | Postgres with row-level security, auth, and file storage for invoice PDFs.                                                                                                                 |
| Edge Functions                          | The few jobs that need a server: invoice numbering, sending email, the public enquiry and quote endpoints, overdue reminders, and the Monzo bank feed. See `supabase/functions/README.md`. |
| Capacitor                               | Wraps the built app as an Android APK. See `ANDROID-APK.md`.                                                                                                                               |

Invoice and quote PDFs are generated on the device with jsPDF, so no rendering
service is involved.

## Development

Needs [Bun](https://bun.sh).

```sh
bun install
bun run dev      # http://localhost:8080
```

| Command             | What it does                                    |
| ------------------- | ----------------------------------------------- |
| `bun run dev`       | Development server                              |
| `bun run build`     | Production web build into `.output/`            |
| `bun run build:app` | Web build plus `dist-app/` for the Android app  |
| `bun run app:sync`  | `build:app`, then copy into the Android project |
| `bun run app:open`  | Open the Android project in Android Studio      |
| `bun run lint`      | ESLint                                          |
| `bun run format`    | Prettier                                        |

## Configuration

`.env` holds the Supabase URL and publishable key. Both are public client
values, which is why they are committed.

Secrets are never kept here. The Resend API key and the Monzo OAuth credentials
live only in the Edge Function secrets (`supabase secrets set RESEND_API_KEY=...`,
`MONZO_CLIENT_ID`, `MONZO_CLIENT_SECRET`), and the service-role key only in
Supabase itself. Nothing in this repo needs any of them.

## Getting paid

Invoices can always be marked paid by hand. Connecting Monzo under Settings adds
a bank feed on top: payments into the account appear on the Invoices screen with
a suggestion of which invoice they settle and a sentence explaining the guess,
and a tap confirms it. Nothing is ever marked paid on its own — a wrongly
settled invoice stops being chased, which costs more than a tap. Setup, and the
limits of Monzo's developer API, are in `supabase/functions/README.md`.

## Setting up Supabase from scratch

`supabase/SETUP.md` walks through a new project end to end: schema, auth
providers, the email key, the reminder schedule, and moving data over.

`scripts/test-migrations.sh` builds a database from `supabase/migrations/` alone
against a local PostgreSQL and checks it behaves — worth running after any
schema change, since it catches the case where the live database only works
because something was once done by hand.

## Deploying

- **Android app** — `ANDROID-APK.md`. GitHub Actions can build the APK for you.
- **Edge Functions** — `supabase/functions/README.md`.
- **Website** — `bun run build` produces a static client in `.output/public`,
  which any static host will serve. It is needed for the public enquiry form and
  quote links, which customers open in a browser.

## Database changes

SQL migrations live in `supabase/migrations/`, applied with the Supabase CLI:

```sh
supabase db push
```

After changing the schema, regenerate the TypeScript types so the app matches:

```sh
supabase gen types typescript --linked > src/integrations/supabase/types.ts
```
