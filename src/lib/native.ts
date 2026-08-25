// Small wrapper around the Capacitor runtime.
//
// The same bundle runs as a website and inside the Android app, so anything
// platform-specific goes through here rather than being sprinkled about.
import { Capacitor } from "@capacitor/core";

/** True only when running inside the packaged Android app. */
export function isNativeApp(): boolean {
  try {
    return Capacitor.isNativePlatform();
  } catch {
    return false;
  }
}


/**
 * Where Supabase should send someone back to after they follow a password
 * reset link.
 *
 * A separate host from the sign-in callback rather than a query parameter on
 * it: Supabase appends its own parameters to this URL, and a scheme with its
 * own query string is one more thing that has to merge correctly. Two hosts
 * cannot be confused for one another, and the Android manifest registers both.
 */
export const NATIVE_RECOVERY_REDIRECT = "uk.co.jwgardenservices.crm://password-reset";

/** The address a password reset email should return to on this platform. */
export function passwordResetRedirect(): string {
  return isNativeApp() ? NATIVE_RECOVERY_REDIRECT : `${window.location.origin}/reset-password`;
}

/**
 * Open a link, by whatever means the platform actually has.
 *
 * On the web this is a new tab. Inside the app a target="_blank" anchor opens
 * nothing at all — which is how the Preview button came to look broken — so
 * the link goes to the share sheet instead. That is the more useful thing
 * regardless: a link you are putting on your website needs to get out of the
 * app, not be looked at inside it.
 */
export async function openOrShare(url: string, title: string): Promise<void> {
  if (!isNativeApp()) {
    const opened = window.open(url, "_blank", "noopener,noreferrer");
    if (!opened) throw new Error("Your browser blocked the new tab.");
    return;
  }

  const { Share } = await import("@capacitor/share");
  await Share.share({ title, text: url, url });
}
