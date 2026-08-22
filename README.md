# JW Garden Services CRM

A CRM for a gardening business: clients, a visit schedule, hours and materials,
one-click invoicing, quotes customers can accept online, and a website enquiry
inbox.

It runs as a standalone Android app and as a website, from the same code. The
only thing behind it is Supabase — there is no server of our own to keep alive.

## How it fits together

| Piece | What it does |
|---|---|
| React SPA (TanStack Router/Start, Vite) | The whole app. Runs in the browser or inside the Android WebView. |
| Supabase | Postgres with row-level security, auth, and file storage for invoice PDFs. |
| Edge Functions | The few jobs that need a server: invoice numbering, sending email, the public enquiry and quote endpoints, and overdue reminders. See `supabase/functions/README.md`. |
| Capacitor | Wraps the built app as an Android APK. See `ANDROID-APK.md`. |

Invoice and quote PDFs are generated on the device with jsPDF, so no rendering
service is involved.

## Development

Needs [Bun](https://bun.sh).

```sh
bun install
bun run dev      # http://localhost:8080
```

| Command | What it does |
|---|---|
| `bun run dev` | Development server |
| `bun run build` | Production web build into `.output/` |
| `bun run build:app` | Web build plus `dist-app/` for the Android app |
| `bun run app:sync` | `build:app`, then copy into the Android project |
| `bun run app:open` | Open the Android project in Android Studio |
| `bun run lint` | ESLint |
| `bun run format` | Prettier |

## Configuration

`.env` holds the Supabase URL and publishable key. Both are public client
values, which is why they are committed.

Secrets are never kept here. The Resend API key lives only in the Edge Function
secrets (`supabase secrets set RESEND_API_KEY=...`), and the service-role key
only in Supabase itself. Nothing in this repo needs either.

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
