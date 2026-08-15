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
    <main className="flex min-h-screen flex-col items-center bg-background px-4 py-10 sm:py-16">
      <header className="flex w-full max-w-5xl items-center justify-between">
        <Logo />
        <Button asChild size="sm" className="rounded-full px-5">
          <Link to="/auth">Sign in</Link>
        </Button>
      </header>

      <section className="mt-16 max-w-3xl text-center sm:mt-24">
        <h1 className="font-display text-5xl leading-[1.05] text-foreground sm:text-6xl md:text-7xl">
          Every job, every hour, every invoice —{" "}
          <span className="italic text-primary">in one tidy place.</span>
        </h1>
        <p className="mx-auto mt-6 max-w-xl text-lg leading-relaxed text-muted-foreground">
          JW Garden Services CRM keeps your schedule, client rates and billing together, so an
          afternoon's work becomes a sent invoice before you've unloaded the van.
        </p>
        <div className="mt-8 flex flex-col justify-center gap-4 sm:flex-row">
          <Button asChild size="lg" className="rounded-xl px-8 shadow-lg shadow-primary/20">
            <Link to="/auth">Get started</Link>
          </Button>
          <Button asChild size="lg" variant="outline" className="rounded-xl px-8">
            <Link to="/dashboard">Open dashboard</Link>
          </Button>
        </div>
      </section>

      <section className="mt-16 grid w-full max-w-5xl gap-5 sm:mt-24 md:grid-cols-2">
        {FEATURES.map((f) => (
          <div
            key={f.title}
            className="rounded-3xl border border-border bg-card p-6 shadow-sm transition-all hover:border-primary/30 hover:shadow-md sm:p-8"
          >
            <span className="flex size-12 items-center justify-center rounded-2xl bg-muted text-primary">
              <f.icon className="size-6" />
            </span>
            <h2 className="mt-5 text-xl font-semibold text-card-foreground">{f.title}</h2>
            <p className="mt-2 leading-relaxed text-muted-foreground">{f.body}</p>
          </div>
        ))}
      </section>
    </main>
  );
}
