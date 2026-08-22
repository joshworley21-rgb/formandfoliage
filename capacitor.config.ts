import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "uk.co.jwgardenservices.crm",
  appName: "JW Garden Services",
  // Built by `bun run build:app`, which turns the SPA output into a folder the
  // WebView can load directly. Not .output/public — that has no index.html.
  webDir: "dist-app",
  android: {
    // Keep the app on https://localhost so Supabase auth (and its cookies)
    // behave the same as they do on the web.
    androidScheme: "https",
  },
  plugins: {
    App: {
      // Deep link used to bring Google sign-in back into the app.
      // Must match the redirect URL allowed in the Supabase dashboard.
      launchUrl: "uk.co.jwgardenservices.crm://auth-callback",
    },
  },
};

export default config;
