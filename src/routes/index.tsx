import { createFileRoute, Link } from "@tanstack/react-router";
import { LOGO_URL } from "@/components/Logo";
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

export function Landing() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-10 bg-background px-6 py-12">
      <div className="flex flex-col items-center gap-6">
        <Logo className="flex-col gap-4" showWordmark={false} />
        <h1 className="text-center font-display text-2xl font-semibold tracking-tight text-foreground">
          JW Garden Services
        </h1>
      </div>

      <Button
        asChild
        size="lg"
        className="rounded-full bg-primary px-10 text-base font-semibold text-primary-foreground shadow-lg shadow-primary/20 transition-all hover:-translate-y-0.5 hover:bg-primary/90"
      >
        <Link to="/auth">Login</Link>
      </Button>
    </main>
  );
}
