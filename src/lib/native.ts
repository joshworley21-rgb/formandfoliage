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
 * Where Supabase should send the browser back to after Google sign-in.
 *
 * On the web that is just the app's own origin. In the Android app there is no
 * origin to come back to, so we use the custom scheme registered in
 * AndroidManifest.xml, which reopens the app.
 */
export const NATIVE_AUTH_REDIRECT = "uk.co.jwgardenservices.crm://auth-callback";

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
