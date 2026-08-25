import { isNativeApp } from "@/lib/native";

/**
 * Opening a reply to an enquiry.
 *
 * A plain mailto: link already escapes the app's WebView and reaches Android,
 * which hands it to whichever mail app is set as default — Gmail on most
 * phones, whether or not that is where the business mail lives. This prefers
 * Outlook when it is actually installed, and falls back to mailto: otherwise,
 * so a phone without Outlook still opens something.
 */

/** The address, subject and body of a reply, before either scheme is built. */
export type Compose = {
  to: string;
  subject?: string;
  body?: string;
};

/** Standard, understood by every mail app there has ever been. */
export function mailtoUrl({ to, subject, body }: Compose): string {
  const params = new URLSearchParams();
  if (subject) params.set("subject", subject);
  if (body) params.set("body", body);

  const query = params.toString();
  // encodeURIComponent, not URLSearchParams, for the address itself: the latter
  // encodes a space as "+", which is a legal character in an email address and
  // would be delivered as one.
  return `mailto:${encodeURIComponent(to)}${query ? `?${query}` : ""}`;
}

/**
 * Outlook for Android's own compose deep link.
 *
 * Not a documented Microsoft API — it is the scheme Outlook registers and the
 * one every "open Outlook from my app" answer uses. Which is exactly why
 * nothing depends on it working: it is tried only when the phone says Outlook
 * is there to answer, and mailto: catches everything else.
 */
export function outlookUrl({ to, subject, body }: Compose): string {
  const params = new URLSearchParams({ to });
  if (subject) params.set("subject", subject);
  if (body) params.set("body", body);
  return `ms-outlook://compose?${params.toString()}`;
}

/** A first line worth sending, rather than an empty box. */
export function replyToEnquiry(name: string, businessName?: string | null | undefined): string {
  const firstName = name.trim().split(/\s+/)[0] || "there";
  const signOff = businessName?.trim() ? `\n\nMany thanks,\n${businessName.trim()}` : "";
  return `Hi ${firstName},\n\nThank you for getting in touch about your garden.\n${signOff}`;
}

type Launcher = {
  canOpenUrl: (options: { url: string }) => Promise<{ value: boolean }>;
  openUrl: (options: { url: string }) => Promise<{ completed: boolean }>;
};

/**
 * Opens a reply, preferring Outlook. Returns which one was used, so the caller
 * can say something true rather than assume.
 *
 * The launcher is a parameter so the decision can be tested without a phone.
 */
export async function openReply(
  compose: Compose,
  loadLauncher: () => Promise<Launcher> = defaultLauncher,
  navigate: (url: string) => void = (url) => {
    window.location.href = url;
  },
): Promise<"outlook" | "mailto"> {
  if (isNativeApp()) {
    try {
      const launcher = await loadLauncher();
      const { value } = await launcher.canOpenUrl({ url: "ms-outlook://" });
      if (value) {
        const { completed } = await launcher.openUrl({ url: outlookUrl(compose) });
        if (completed) return "outlook";
      }
    } catch {
      // Outlook absent, or the plugin unavailable. mailto: below still works.
    }
  }

  navigate(mailtoUrl(compose));
  return "mailto";
}

async function defaultLauncher(): Promise<Launcher> {
  const { AppLauncher } = await import("@capacitor/app-launcher");
  return AppLauncher as unknown as Launcher;
}
