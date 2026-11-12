# UI Component Development Skill

## Scope & Principles
- Use Tailwind CSS and React/TypeScript.
- Follow mobile-first utility patterns.
- Do not build custom unstyled modals, dialogs, or dropdowns from scratch; use Radix / shadcn primitives.

## Command Execution
- Add shadcn primitives via Bun:
  `bunx --bun shadcn@latest add <component-name>`
- Verify imported components exist in `src/components/ui/`.
- Ensure all interactive elements include explicit focus states (`focus-visible:ring-2`) and touch targets of at least 48px.
