/* Imported by the generated service worker (workbox importScripts).
   Warms the navigation cache with the pages used most, so the first
   visit after install — and every offline launch — renders instantly. */

const WARMUP_CACHE = "html-navigations";
const WARMUP_ROUTES = ["/", "/dashboard", "/schedule", "/visits", "/clients", "/invoices"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      try {
        const cache = await caches.open(WARMUP_CACHE);
        await Promise.allSettled(
          WARMUP_ROUTES.map(async (route) => {
            const response = await fetch(route, {
              credentials: "same-origin",
              headers: { Accept: "text/html" },
            });
            if (response.ok) await cache.put(route, response.clone());
          }),
        );
      } catch {
        /* warm-up is best-effort; never block installation */
      }
    })(),
  );
});
