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
