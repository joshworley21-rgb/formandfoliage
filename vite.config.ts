// Standalone Vite config. This project previously built through
// @lovable.dev/vite-tanstack-config, which bundled the plugins below behind one
// import. They are now wired up explicitly so the app builds anywhere.
import { tanstackStart } from "@tanstack/react-start/plugin/vite";
import tailwindcss from "@tailwindcss/vite";
import viteReact from "@vitejs/plugin-react";
import { nitro } from "nitro/vite";
import { defineConfig, loadEnv } from "vite";
import tsConfigPaths from "vite-tsconfig-paths";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig(({ mode }) => {
  // Vite exposes VITE_* to the client automatically, but server code reads the
  // unprefixed names (SUPABASE_URL, RESEND_API_KEY, ...) off process.env. Load
  // .env into this process so SSR and server functions work in local dev; these
  // never reach the client bundle.
  Object.assign(process.env, loadEnv(mode, process.cwd(), ""));

  return {
    server: { host: true, port: 8080 },
    resolve: {
      // Keep a single copy of React and the TanStack runtime in the graph;
      // duplicates break hooks and router context.
      dedupe: [
        "react",
        "react-dom",
        "@tanstack/react-router",
        "@tanstack/react-start",
        "@tanstack/react-query",
      ],
    },
    plugins: [
      tsConfigPaths(),
      tailwindcss(),
      tanstackStart({
        // Client-rendered: the Android app ships the built client inside the
        // APK and has no server to render on, so the web build is a SPA too and
        // both run the exact same code.
        spa: { enabled: true },
        // Redirect TanStack Start's bundled server entry to src/server.ts (our
        // SSR error wrapper). nitro builds from this.
        server: { entry: "server" },
      }),
      viteReact(),
      nitro(),
      VitePWA({
        // The client build lands in .output/public (nitro), not dist/ — without
        // this the worker is written somewhere that never ships and precaches
        // nothing.
        outDir: ".output/public",
        strategies: "generateSW",
        registerType: "autoUpdate",
        injectRegister: null,
        filename: "sw.js",
        devOptions: { enabled: false },
        manifest: false,
        workbox: {
          globPatterns: ["**/*.{js,css,woff2}"],
          navigateFallback: "/",
          navigateFallbackDenylist: [/^\/~oauth/, /^\/api\//],
          cleanupOutdatedCaches: true,
          clientsClaim: true,
          skipWaiting: true,
          importScripts: ["/sw-warmup.js"],
          runtimeCaching: [
            {
              urlPattern: ({ request, url }) =>
                request.mode === "navigate" &&
                !url.pathname.startsWith("/~oauth") &&
                !url.pathname.startsWith("/api/"),
              handler: "NetworkFirst",
              options: {
                cacheName: "html-navigations",
                networkTimeoutSeconds: 4,
                expiration: { maxEntries: 40, maxAgeSeconds: 60 * 60 * 24 * 7 },
              },
            },
            {
              // Backend GET reads: serve the last good response instantly, refresh behind it.
              urlPattern: ({ request, url }) =>
                request.method === "GET" &&
                /\/rest\/v1\//.test(url.pathname) &&
                /supabase\.co$/.test(url.hostname),
              handler: "NetworkFirst",
              options: {
                cacheName: "api-shells",
                networkTimeoutSeconds: 3,
                expiration: { maxEntries: 120, maxAgeSeconds: 60 * 60 * 24 },
                cacheableResponse: { statuses: [200] },
              },
            },
            {
              urlPattern: ({ url, sameOrigin }) =>
                sameOrigin && /\.(?:js|css|png|svg|webmanifest|woff2)$/.test(url.pathname),
              handler: "CacheFirst",
              options: {
                cacheName: "static-assets",
                expiration: { maxEntries: 200, maxAgeSeconds: 60 * 60 * 24 * 30 },
              },
            },
          ],
        },
      }),
    ],
  };
});
