import { createFileRoute, Link } from "@tanstack/react-router";
import { CalendarDays, ClipboardList, Mail, Repeat } from "lucide-react";
import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

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

function OrganicShapes() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
      {/* Large moss blob — top right, breaking the grid */}
      <div
        className="absolute -right-32 -top-40 size-[28rem] rounded-full bg-primary/10 blur-3xl lg:size-[40rem]"
        style={{ borderRadius: "42% 58% 70% 30% / 45% 45% 55% 55%" }}
      />
      {/* Soft sage accent — mid left */}
      <div
        className="absolute -left-40 top-1/3 size-[24rem] rounded-full bg-accent/15 blur-3xl lg:size-[32rem]"
        style={{ borderRadius: "60% 40% 30% 70% / 60% 30% 70% 40%" }}
      />
      {/* Deep forest shape — bottom right */}
      <div
        className="absolute -bottom-48 right-1/4 size-[26rem] rounded-full bg-primary/8 blur-3xl lg:size-[36rem]"
        style={{ borderRadius: "30% 70% 70% 30% / 30% 30% 70% 70%" }}
      />
    </div>
  );
}

export function Landing() {
  return (
    <main className="relative flex min-h-screen flex-col items-center overflow-hidden bg-background px-4 py-8 sm:py-12 lg:py-20">
      <OrganicShapes />

      <header className="relative z-10 flex w-full max-w-7xl items-center justify-between">
        <Logo />
        <Button
          asChild
          size="sm"
          variant="outline"
          className="rounded-full border-foreground/20 px-5 font-medium"
        >
          <Link to="/auth">Sign in</Link>
        </Button>
      </header>

      <section className="relative z-10 mt-12 w-full max-w-7xl sm:mt-20 lg:mt-28">
        <div className="grid grid-cols-1 items-center gap-12 lg:grid-cols-12 lg:gap-20">
          {/* Hero content */}
          <div className="space-y-8 lg:col-span-5">
            <div className="inline-flex items-center gap-2 rounded-full bg-secondary px-3 py-1.5 text-xs font-semibold uppercase tracking-widest text-secondary-foreground">
              <span className="size-1.5 rounded-full bg-accent" />
              Garden services CRM
            </div>

            <h1 className="font-display text-5xl font-bold leading-[1.05] tracking-tight text-foreground sm:text-6xl lg:text-7xl">
              JW Garden <br />
              Services <span className="text-primary">CRM.</span>
            </h1>

            <p className="max-w-md text-lg leading-relaxed text-muted-foreground">
              Plan the round, log hours and visits, and email branded PDF invoices in one click —
              built for JW Garden Services.
            </p>

            <div className="flex flex-wrap gap-4 pt-2">
              <Button
                asChild
                size="lg"
                className="rounded-xl bg-primary px-8 text-base font-semibold text-primary-foreground shadow-lg shadow-primary/20 transition-all hover:-translate-y-0.5 hover:bg-primary/90"
              >
                <Link to="/auth">Get started</Link>
              </Button>
              <Button
                asChild
                size="lg"
                variant="outline"
                className="rounded-xl border-2 border-foreground/20 px-8 text-base font-semibold text-foreground transition-all hover:bg-foreground hover:text-background"
              >
                <Link to="/dashboard">Open dashboard</Link>
              </Button>
            </div>
          </div>

          {/* Feature grid */}
          <div className="lg:col-span-7">
            <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
              {FEATURES.map((f, i) => {
                const Icon = f.icon;
                const isDark = i === 1;
                const isOffset = i === 2;
                return (
                  <div
                    key={f.title}
                    className={cn(
                      "group relative rounded-[2rem] border p-7 transition-all duration-300 sm:p-8",
                      isDark
                        ? "border-primary bg-primary text-primary-foreground shadow-xl"
                        : "border-border bg-card shadow-sm hover:border-accent/50 hover:shadow-lg hover:shadow-accent/10",
                      isOffset && "sm:-translate-y-6",
                    )}
                  >
                    <span
                      className={cn(
                        "flex size-12 items-center justify-center rounded-2xl transition-colors",
                        isDark
                          ? "bg-accent text-primary-foreground"
                          : "bg-secondary text-primary group-hover:bg-primary group-hover:text-primary-foreground",
                      )}
                    >
                      <Icon className="size-6" />
                    </span>
                    <h2
                      className={cn(
                        "mt-6 font-display text-xl font-bold",
                        isDark ? "text-primary-foreground" : "text-card-foreground",
                      )}
                    >
                      {f.title}
                    </h2>
                    <p
                      className={cn(
                        "mt-2 text-sm leading-relaxed",
                        isDark ? "text-primary-foreground/80" : "text-muted-foreground",
                      )}
                    >
                      {f.body}
                    </p>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}
