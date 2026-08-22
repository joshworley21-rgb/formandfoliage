import { isNativeApp } from "@/lib/native";

/**
 * The address customers reach the business at, for links we put in front of
 * them — the enquiry form and quote accept links.
 *
 * This cannot come from window.location.origin alone: inside the Android app
 * that is the WebView's own https://localhost, so a quote emailed from the
 * phone would arrive with a dead link. The business address is stored in
 * settings; the browser's origin is only a fallback for the website itself.
 *
 * Returns null when there is nothing usable, so callers can say so rather than
 * sending a broken link.
 */
export function publicSiteUrl(configured: string | null | undefined): string | null {
  const trimmed = configured?.trim();
  if (trimmed) return trimmed.replace(/\/+$/, "");

  if (typeof window === "undefined") return null;
  if (isNativeApp()) return null;

  const origin = window.location.origin;
  // A local dev origin is fine for clicking about, but never for a customer.
  if (/^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:|$)/.test(origin)) return null;
  return origin;
}

export const SITE_URL_MISSING =
  "Add your website address in Settings first — without it the link sent to your customer won't work.";
