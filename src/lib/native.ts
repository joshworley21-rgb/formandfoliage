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
 * Opens a tab now, to be pointed somewhere once we know where.
 *
 * Browsers only allow a new tab while a click is still being handled, and an
 * OAuth URL has to be fetched first — so the tab is claimed on the click and
 * filled in when the answer arrives. Returns null on native, and on any browser
 * that blocked it, in which case followUrl falls back to this tab.
 */
export function openBlankTab(): Window | null {
  if (isNativeApp() || typeof window === "undefined") return null;
  const tab = window.open("about:blank", "_blank");
  // "noopener" would be the usual way to sever this, but it makes window.open
  // return null and the whole point here is to keep the handle. Cutting the
  // back-reference by hand leaves the page we send it to unable to reach back
  // into this one.
  if (tab) tab.opener = null;
  return tab;
}

/** Sends the browser to a URL: the tab claimed above, the system browser, or here. */
export async function followUrl(url: string, tab: Window | null): Promise<void> {
  if (isNativeApp()) {
    const { AppLauncher } = await import("@capacitor/app-launcher");
    await AppLauncher.openUrl({ url });
    return;
  }
  if (tab && !tab.closed) tab.location.href = url;
  else window.location.href = url;
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
