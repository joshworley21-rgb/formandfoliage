import { useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { toast } from "sonner";
import { App } from "@capacitor/app";
import { supabase } from "@/integrations/supabase/client";
import { isNativeApp } from "@/lib/native";

/**
 * Completes a password reset inside the Android app.
 *
 * On the web, Supabase redirects back to the site and supabase-js picks the
 * code out of the URL by itself. The app has no URL to return to, so the reset
 * link comes back on a custom scheme and Android reopens the app on it. This
 * listens for that, finishes the exchange by hand, and sends the app to the
 * page for choosing a new password.
 *
 * Renders nothing, and does nothing at all on the web.
 */
export function NativeAuthListener() {
  const navigate = useNavigate();

  useEffect(() => {
    if (!isNativeApp()) return;

    let cancelled = false;
    const handle = App.addListener("appUrlOpen", async ({ url }) => {
      if (!url.includes("password-reset")) return;

      try {
        // The scheme is not a real origin, so URL() needs a stand-in host
        // before the query and fragment can be read off it.
        const parsed = new URL(url.replace(/^[^:]+:\/\//, "https://placeholder/"));
        const code = parsed.searchParams.get("code");
        const errorDescription = parsed.searchParams.get("error_description");

        if (errorDescription) throw new Error(errorDescription);

        if (code) {
          const { error } = await supabase.auth.exchangeCodeForSession(code);
          if (error) throw error;
        } else {
          // Implicit-flow fallback: tokens arrive in the fragment instead.
          const fragment = new URLSearchParams(parsed.hash.replace(/^#/, ""));
          const access_token = fragment.get("access_token");
          const refresh_token = fragment.get("refresh_token");
          if (!access_token || !refresh_token) throw new Error("No sign-in details returned");
          const { error } = await supabase.auth.setSession({ access_token, refresh_token });
          if (error) throw error;
        }

        if (cancelled) return;
        navigate({ to: "/reset-password" });
      } catch (error) {
        if (cancelled) return;
        toast.error(error instanceof Error ? error.message : "That reset link did not work");
      }
    });

    return () => {
      cancelled = true;
      handle.then((listener) => listener.remove()).catch(() => {});
    };
  }, [navigate]);

  return null;
}
