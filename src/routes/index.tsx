import { createFileRoute, Link } from "@tanstack/react-router";
import { CalendarDays, ClipboardList, Mail, Repeat } from "lucide-react";
import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "JW Garden Services CRM — Rounds, hours and one-click invoices" },
      {
        name: "description",
        content:
          "Plan the round, log hours and visits, and email branded PDF invoices in one click — built for JW Garden Services.",
      },
      { property: "og:title", content: "JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Plan the round, log visits and send branded PDF invoices in one click.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: Landing,
});

const FEATURES = [
  { icon: CalendarDays, title: "Plan the round", body: "Month, week and day calendar views with repeating jobs." },
  { icon: ClipboardList, title: "Log hours and visits", body: "Complete a job and the visit is priced automatically." },
  { icon: Mail, title: "One-click invoices", body: "Branded PDF invoices emailed straight to the client." },
  { icon: Repeat, title: "Recurring billing", body: "Weekly, fortnightly, four-weekly, monthly or quarterly." },
];

function Landing() {
  return (
    <main className="min-h-screen bg-background">
      <header className="mx-auto flex max-w-5xl items-center justify-between px-5 py-6">
        <Logo />
        <Button asChild size="sm">
          <Link to="/auth">Sign in</Link>
        </Button>
      </header>

      <section className="mx-auto max-w-5xl px-5 pb-16 pt-8">
        <p className="text-sm font-semibold uppercase tracking-[0.18em] text-primary">
          Garden round management
        </p>
        <h1 className="mt-3 max-w-2xl font-display text-4xl font-semibold leading-tight sm:text-5xl">
          Every job, every hour, every invoice — in one tidy place.
        </h1>
        <p className="mt-4 max-w-xl text-lg text-muted-foreground">
          JW Garden Services CRM keeps your schedule, client rates and billing together, so an
          afternoon's work becomes a sent invoice before you've unloaded the van.
        </p>
        <div className="mt-7 flex flex-wrap gap-3">
          <Button asChild size="lg">
            <Link to="/auth">Get started</Link>
          </Button>
          <Button asChild size="lg" variant="outline">
            <Link to="/dashboard">Open dashboard</Link>
          </Button>
        </div>

        <div className="mt-14 grid gap-4 sm:grid-cols-2">
          {FEATURES.map((f) => (
            <div key={f.title} className="rounded-2xl border border-border bg-card p-5">
              <span className="flex size-10 items-center justify-center rounded-xl bg-primary/12 text-primary">
                <f.icon className="size-5" />
              </span>
              <h2 className="mt-3 font-display text-lg font-semibold">{f.title}</h2>
              <p className="mt-1 text-sm text-muted-foreground">{f.body}</p>
            </div>
          ))}
        </div>
      </section>
    </main>
  );
}
