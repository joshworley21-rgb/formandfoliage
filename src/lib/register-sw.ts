import { isNativeApp } from "@/lib/native";

const SW_URL = "/sw.js";

function isRefusedContext(): boolean {
  if (typeof window === "undefined") return true;
  if (!import.meta.env.PROD) return true;
  if (window.self !== window.top) return true;

  // Inside the Android app every asset is already on the device; a service
  // worker would only add a second, staler cache.
  if (isNativeApp()) return true;
  if (new URLSearchParams(window.location.search).get("sw") === "off") return true;

  return false;
}

async function unregisterAppWorkers() {
  if (!("serviceWorker" in navigator)) return;
  const registrations = await navigator.serviceWorker.getRegistrations();
  await Promise.allSettled(
    registrations
      .filter((registration) => {
        const url =
          registration.active?.scriptURL ??
          registration.waiting?.scriptURL ??
          registration.installing?.scriptURL ??
          "";
        return url.endsWith(SW_URL);
      })
      .map((registration) => registration.unregister()),
  );
}

export function registerOfflineSupport() {
  if (typeof window === "undefined" || !("serviceWorker" in navigator)) return;

  if (isRefusedContext()) {
    void unregisterAppWorkers();
    return;
  }

  const register = () => {
    void navigator.serviceWorker.register(SW_URL, { scope: "/" }).catch(() => {
      /* offline support is best-effort */
    });
  };

  // Register as soon as possible so scanners (PWABuilder, Lighthouse) detect it.
  if (document.readyState === "complete") register();
  else window.addEventListener("load", register, { once: true });
}
