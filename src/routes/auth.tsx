import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Browser } from "@capacitor/browser";
import { supabase } from "@/integrations/supabase/client";
import { isNativeApp, NATIVE_AUTH_REDIRECT, passwordResetRedirect } from "@/lib/native";

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
  component: AuthPage,
});

function AuthPage() {
  const navigate = useNavigate();
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

  async function googleSignIn() {
    setBusy(true);

    // In the Android app there is no page to redirect back to, so sign-in opens
    // the system browser and returns through the custom scheme, which
    // NativeAuthListener picks up.
    if (isNativeApp()) {
      const { data, error } = await supabase.auth.signInWithOAuth({
        provider: "google",
        options: { redirectTo: NATIVE_AUTH_REDIRECT, skipBrowserRedirect: true },
      });
      if (error || !data?.url) {
        setBusy(false);
        toast.error(error?.message || "Google sign-in failed");
        return;
      }
      await Browser.open({ url: data.url });
      setBusy(false);
      return;
    }

    // On the web the browser is redirected to Google and comes back to
    // /dashboard, where supabase-js completes the sign-in itself.
    const { error } = await supabase.auth.signInWithOAuth({
      provider: "google",
      options: { redirectTo: `${window.location.origin}/dashboard` },
    });
    if (error) {
      setBusy(false);
      toast.error(error.message || "Google sign-in failed");
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
            {mode !== "forgot" ? (
              <>
                <Button
                  type="button"
                  variant="outline"
                  className="w-full"
                  disabled={busy}
                  onClick={googleSignIn}
                >
                  Continue with Google
                </Button>
                <div className="flex items-center gap-3 text-xs uppercase tracking-wide text-muted-foreground">
                  <span className="h-px flex-1 bg-border" /> or{" "}
                  <span className="h-px flex-1 bg-border" />
                </div>
              </>
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
