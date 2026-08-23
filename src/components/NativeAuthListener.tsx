import { useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { toast } from "sonner";
import { App } from "@capacitor/app";
import { Browser } from "@capacitor/browser";
import { supabase } from "@/integrations/supabase/client";
import { isNativeApp } from "@/lib/native";

/**
 * Completes Google sign-in inside the Android app.
 *
 * On the web, Supabase redirects back to the site and supabase-js picks the code
 * out of the URL by itself. The app has no URL to return to, so sign-in opens
 * the system browser and Android reopens the app on the custom scheme instead.
 * This listens for that and finishes the exchange by hand.
 *
 * Renders nothing, and does nothing at all on the web.
 */
export function NativeAuthListener() {
  const navigate = useNavigate();

  useEffect(() => {
    if (!isNativeApp()) return;

    let cancelled = false;
    const handle = App.addListener("appUrlOpen", async ({ url }) => {
      if (!url.includes("auth-callback")) return;

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
        navigate({ to: "/dashboard" });
      } catch (error) {
        if (cancelled) return;
        toast.error(error instanceof Error ? error.message : "Google sign-in failed");
      } finally {
        // Close the browser tab whether or not the exchange worked, so the user
        // is never left staring at a blank page.
        await Browser.close().catch(() => {});
      }
    });

    return () => {
      cancelled = true;
      handle.then((listener) => listener.remove()).catch(() => {});
    };
  }, [navigate]);

  return null;
}
