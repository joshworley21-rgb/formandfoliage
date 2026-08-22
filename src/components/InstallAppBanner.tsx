import { useEffect, useState } from "react";
import { Download, Share, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { isNativeApp } from "@/lib/native";

type InstallPromptEvent = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
};

const DISMISS_KEY = "jw-install-dismissed";

export function InstallAppBanner() {
  const [promptEvent, setPromptEvent] = useState<InstallPromptEvent | null>(null);
  const [showIosHelp, setShowIosHelp] = useState(false);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (typeof window === "undefined") return;
    // Already the installed app — do not offer to install it.
    if (isNativeApp()) return;
    if (localStorage.getItem(DISMISS_KEY) === "1") return;

    const standalone =
      window.matchMedia("(display-mode: standalone)").matches ||
      (window.navigator as unknown as { standalone?: boolean }).standalone === true;
    if (standalone) return;

    const isIos = /iphone|ipad|ipod/i.test(window.navigator.userAgent);
    if (isIos) {
      setShowIosHelp(true);
      setVisible(true);
    }

    const onPrompt = (event: Event) => {
      event.preventDefault();
      setPromptEvent(event as InstallPromptEvent);
      setShowIosHelp(false);
      setVisible(true);
    };
    window.addEventListener("beforeinstallprompt", onPrompt);
    window.addEventListener("appinstalled", () => setVisible(false));
    return () => window.removeEventListener("beforeinstallprompt", onPrompt);
  }, []);

  if (!visible) return null;

  const dismiss = () => {
    localStorage.setItem(DISMISS_KEY, "1");
    setVisible(false);
  };

  const install = async () => {
    if (!promptEvent) return;
    await promptEvent.prompt();
    const choice = await promptEvent.userChoice;
    if (choice.outcome === "accepted") setVisible(false);
  };

  return (
    <div className="mb-4 flex items-start gap-3 rounded-xl border border-border bg-card px-4 py-3 shadow-sm">
      <span className="mt-0.5 rounded-lg bg-primary/10 p-2 text-primary">
        <Download className="size-4" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-foreground">Install JW Garden CRM</p>
        {showIosHelp ? (
          <p className="mt-1 flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            Tap <Share className="size-3.5" /> Share, then “Add to Home Screen” to run it full
            screen like an app.
          </p>
        ) : (
          <p className="mt-1 text-xs text-muted-foreground">
            Add it to your home screen to open it full screen, like a normal app.
          </p>
        )}
        {promptEvent ? (
          <Button size="sm" className="mt-2" onClick={install}>
            Install app
          </Button>
        ) : null}
      </div>
      <Button variant="ghost" size="icon" onClick={dismiss} aria-label="Dismiss">
        <X className="size-4" />
      </Button>
    </div>
  );
}
