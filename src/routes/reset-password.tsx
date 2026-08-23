import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { supabase } from "@/integrations/supabase/client";

export const Route = createFileRoute("/reset-password")({
  head: () => ({
    meta: [{ title: "Choose a new password — JW Garden Services CRM" }],
  }),
  component: ResetPasswordPage,
});

/**
 * Where a password reset link lands.
 *
 * Following the link signs the account in, which is what makes it possible to
 * set a new password. On the web supabase-js reads the code out of the URL
 * itself; in the Android app NativeAuthListener does the exchange and sends the
 * app here. Either way this page waits for a session to appear rather than
 * assuming one is already there.
 */
function ResetPasswordPage() {
  const navigate = useNavigate();
  const [state, setState] = useState<"checking" | "ready" | "expired">("checking");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let settled = false;
    const ready = () => {
      if (settled) return;
      settled = true;
      setState("ready");
    };

    supabase.auth.getSession().then(({ data }) => {
      if (data.session) ready();
    });

    // The web path finishes the exchange a moment after the page mounts, so
    // the session can arrive after that first check.
    const { data: sub } = supabase.auth.onAuthStateChange((_event, session) => {
      if (session) ready();
    });

    // Nothing arrived: the link was used already, has expired, or was opened
    // on a different device from the one that asked for it.
    const giveUp = setTimeout(() => {
      if (!settled) {
        settled = true;
        setState("expired");
      }
    }, 6000);

    return () => {
      clearTimeout(giveUp);
      sub.subscription.unsubscribe();
    };
  }, []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (password !== confirmation) {
      toast.error("The two passwords do not match");
      return;
    }
    setBusy(true);
    try {
      const { error } = await supabase.auth.updateUser({ password });
      if (error) throw error;
      toast.success("Password changed. You are signed in.");
      navigate({ to: "/dashboard" });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not change the password");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-10">
      <div className="w-full max-w-md">
        <Logo className="mb-8 justify-center" />
        <Card className="border-border/80 bg-card/95 shadow-sm">
          <CardHeader className="space-y-1">
            <CardTitle className="font-display text-2xl">Choose a new password</CardTitle>
            <CardDescription>
              {state === "expired"
                ? "This link cannot be used."
                : "Pick something you will remember. You will be signed in straight away."}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {state === "checking" ? (
              <p className="text-sm text-muted-foreground">Checking your link…</p>
            ) : state === "expired" ? (
              <>
                <p className="text-sm text-muted-foreground">
                  Reset links only work once, expire after an hour, and have to be opened on the
                  same phone or computer you asked for the reset from.
                </p>
                <Button className="w-full" onClick={() => navigate({ to: "/auth" })}>
                  Ask for a new link
                </Button>
              </>
            ) : (
              <form className="space-y-3" onSubmit={handleSubmit}>
                <div className="space-y-1.5">
                  <Label htmlFor="password">New password</Label>
                  <Input
                    id="password"
                    type="password"
                    autoComplete="new-password"
                    required
                    minLength={6}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="confirmation">New password again</Label>
                  <Input
                    id="confirmation"
                    type="password"
                    autoComplete="new-password"
                    required
                    minLength={6}
                    value={confirmation}
                    onChange={(e) => setConfirmation(e.target.value)}
                  />
                </div>
                <Button type="submit" className="w-full" disabled={busy}>
                  {busy ? "Saving…" : "Save new password"}
                </Button>
              </form>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
