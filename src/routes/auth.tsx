import { createFileRoute, redirect, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { supabase } from "@/integrations/supabase/client";
import { passwordResetRedirect } from "@/lib/native";
import { isOffline } from "@/lib/offline-queue";
import { currentUser } from "@/lib/session";

/** Tracks the connection so the sign-in page can say why it cannot help. */
function useOffline(): boolean {
  const [offline, setOffline] = useState(() => isOffline());

  useEffect(() => {
    const update = () => setOffline(isOffline());
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);

  return offline;
}

export const Route = createFileRoute("/auth")({
  head: () => ({
    meta: [
      { title: "Sign in — JW Garden Services CRM" },
      {
        name: "description",
        content: "Sign in to the JW Garden Services CRM to manage clients, visits and invoices.",
      },
      { property: "og:title", content: "Sign in — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Secure access to your gardening client, visit and invoicing records.",
      },
    ],
  }),
  /**
   * Already signed in? Then this page has nothing to ask. Without this, tapping
   * Login showed the form again even though the session was perfectly good.
   *
   * Client only: the sign-in page is prerendered for the website, and who is
   * signed in is not a question a build machine can answer.
   */
  beforeLoad: async () => {
    if (typeof window === "undefined") return;
    if (await currentUser()) throw redirect({ to: "/dashboard" });
  },
  component: AuthPage,
});

function AuthPage() {
  const navigate = useNavigate();
  const offline = useOffline();
  const [mode, setMode] = useState<"signin" | "signup" | "forgot">("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      if (data.session) navigate({ to: "/dashboard" });
    });
  }, [navigate]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (mode === "forgot") {
      await sendResetLink();
      return;
    }
    setBusy(true);
    try {
      if (mode === "signup") {
        const { error } = await supabase.auth.signUp({
          email,
          password,
          options: { emailRedirectTo: `${window.location.origin}/dashboard` },
        });
        if (error) throw error;
        toast.success("Account created. Check your email if confirmation is required.");
      } else {
        const { error } = await supabase.auth.signInWithPassword({ email, password });
        if (error) throw error;
      }
      const { data } = await supabase.auth.getSession();
      if (data.session) navigate({ to: "/dashboard" });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Sign in failed");
    } finally {
      setBusy(false);
    }
  }

  async function sendResetLink() {
    setBusy(true);
    try {
      const { error } = await supabase.auth.resetPasswordForEmail(email, {
        redirectTo: passwordResetRedirect(),
      });
      if (error) throw error;
      // Deliberately the same message whether or not the address has an
      // account, so this cannot be used to find out who has one.
      toast.success("If that address has an account, a reset link is on its way.");
      setMode("signin");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not send the reset link");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background px-4 py-10">
      {/* Organic background shapes */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -left-[10%] -top-[15%] h-[50vh] w-[50vh] rounded-full bg-primary/10 blur-3xl" />
        <div className="absolute bottom-[5%] right-[5%] h-[40vh] w-[40vh] rounded-[40%_60%_70%_30%/40%_50%_60%_50%] bg-leaf/15 blur-3xl" />
        <div className="absolute left-[30%] top-[40%] h-[25vh] w-[25vh] rounded-[60%_40%_30%_70%/60%_30%_70%_40%] bg-brown/10 blur-2xl" />
      </div>

      <div className="relative z-10 w-full max-w-md">
        <Logo className="mb-8 justify-center" />
        <Card className="border-border/80 bg-card/95 shadow-sm backdrop-blur">
          <CardHeader className="space-y-1">
            <CardTitle className="font-display text-2xl">
              {mode === "signin"
                ? "Sign in"
                : mode === "signup"
                  ? "Create your account"
                  : "Reset your password"}
            </CardTitle>
            <CardDescription>
              {mode === "forgot"
                ? "We will email you a link to set a new one."
                : "Your private garden business workspace."}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {/*
              Signing in is the one thing in the app that genuinely cannot be
              done without signal — the password has to be checked by the
              server. Say so, rather than letting a tap fail with a network
              error that reads like a wrong password.
            */}
            {offline ? (
              <p className="rounded-xl border border-warning/40 bg-warning/10 px-3 py-2.5 text-sm text-warning-foreground">
                You are offline. Signing in needs signal — once you are back in
                range this will work, and after that the app opens without
                asking again.
              </p>
            ) : null}
            <form className="space-y-3" onSubmit={handleSubmit}>
              <div className="space-y-1.5">
                <Label htmlFor="email">Email</Label>
                <Input
                  id="email"
                  type="email"
                  autoComplete="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </div>
              {mode !== "forgot" ? (
                <div className="space-y-1.5">
                  <Label htmlFor="password">Password</Label>
                  <Input
                    id="password"
                    type="password"
                    autoComplete={mode === "signin" ? "current-password" : "new-password"}
                    required
                    minLength={6}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                </div>
              ) : null}
              <Button type="submit" className="w-full" disabled={busy}>
                {mode === "signin"
                  ? "Sign in"
                  : mode === "signup"
                    ? "Create account"
                    : busy
                      ? "Sending…"
                      : "Email me a reset link"}
              </Button>
            </form>
            {mode === "signin" ? (
              <button
                type="button"
                className="w-full text-center text-sm text-muted-foreground underline-offset-4 hover:underline"
                onClick={() => setMode("forgot")}
              >
                Forgotten your password?
              </button>
            ) : null}
            <button
              type="button"
              className="w-full text-center text-sm text-muted-foreground underline-offset-4 hover:underline"
              onClick={() => setMode(mode === "signup" ? "signin" : mode === "forgot" ? "signin" : "signup")}
            >
              {mode === "signin"
                ? "Need an account? Create one"
                : mode === "signup"
                  ? "Already have an account? Sign in"
                  : "Back to sign in"}
            </button>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
