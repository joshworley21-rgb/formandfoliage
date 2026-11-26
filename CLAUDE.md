# JW Garden Services CRM

## Stack & commands (use bun)
- React 19, TanStack Start/Router/Query, Supabase, Tailwind v4 + shadcn/Radix, Vite 8
- Android app via Capacitor 8 (`android/`, `native-android/`); PWA via vite-plugin-pwa
- Dev: `bun run dev` | Build: `bun run build` | Lint: `bun run lint`
- Typecheck: `bun run typecheck` | Test: `bun test` | Format: `bun run format`
- Android: `bun run app:sync` then `bun run app:open`

## Directory map
- `src/routes/` file-based routes; `_authenticated/` = signed-in screens (dashboard, clients, schedule, invoices, visits, enquiries, quotes, settings)
- `src/components/` shared UI (AppShell, dialogs); `components/ui/` = shadcn primitives
- `src/hooks/` data layer (`use-crm.ts`, `use-quotes.ts`, `use-workspace.ts`)
- `src/lib/` offline queue, session, query persistence, invoicing, pricing helpers
- `src/integrations/supabase/` generated client/types (do not hand-edit)
- `supabase/` migrations and functions; `scripts/` build helpers
- Generated, never edit: `src/routeTree.gen.ts`

## Architecture rules
- All writes go through `runOrQueue()` (offline queue). Never call supabase directly for mutations.
- Query cache persistence lives only in `__root.tsx` (PersistQueryClientProvider).
- Auth is offline-safe: use `currentUser()` from `lib/session`, not `getSession()`.
- Staff role sees only schedule/clients; owner-only pages use `<OwnerOnly>`.

## UI & code guardrails
- Compact mobile headers; strong contrast between header cards and background
- Touch targets at least 44px; respect safe-area insets
- Use theme tokens from `styles.css` only; no hardcoded colors
- Loading, empty and error state on every list; confirm destructive actions
- Zero dead or duplicate code; no comments unless the WHY is non-obvious

## Workflow
- Keep responses concise; batch file edits
- Remind the user to run /compact between phases and /clear when switching tasks
- Don't rewrite pushed git history (Lovable sync)
