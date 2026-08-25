import { createFileRoute, Link, redirect } from "@tanstack/react-router";
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
  /**
   * Opening the app landed here every time, on a page whose only purpose is to
   * offer a Login button — so signing in was a tap you made every morning even
   * though the session had never expired.
   *
   * Runs on the client only. The page is prerendered for the website, and a
   * redirect decided on the server would either bake itself into that HTML or
   * ask a build machine who is signed in.
   */
  beforeLoad: async () => {
    if (typeof window === "undefined") return;

    // Imported here rather than at the top of the file: this page is
    // prerendered, and the Capacitor runtime is not something a build machine
    // can load — pulling it into the server graph broke the prerender outright.
    const [{ currentUser }, { isNativeApp }] = await Promise.all([
      import("@/lib/session"),
      import("@/lib/native"),
    ]);

    if (await currentUser()) throw redirect({ to: "/dashboard" });

    // In the app there is no marketing page worth showing: nobody installs the
    // APK to be told what it does.
    if (isNativeApp()) throw redirect({ to: "/auth" });
  },
  component: Landing,
});

export function Landing() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-10 bg-background px-6 py-12">
      <div className="flex flex-col items-center gap-6">
        <img
          src={LOGO_URL}
          alt="JW Garden Services logo"
          className="h-40 w-auto object-contain"
          width={160}
          height={160}
        />
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
