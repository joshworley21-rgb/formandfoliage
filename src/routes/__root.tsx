import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  Outlet,
  Link,
  createRootRouteWithContext,
  useRouter,
  HeadContent,
  Scripts,
} from "@tanstack/react-router";
import { useEffect, type ReactNode } from "react";

import appCss from "../styles.css?url";
import { reportLovableError } from "../lib/lovable-error-reporting";
import { ClientOnly } from "@tanstack/react-router";
import { OfflineSupport } from "@/components/OfflineSupport";
import { supabase } from "@/integrations/supabase/client";
import { Toaster } from "@/components/ui/sonner";

// iOS standalone launch images (Android/PWABuilder uses manifest background_color + icon)
const APPLE_SPLASH_LINKS = (
  [
    [1290, 2796, 430, 932, 3],
    [1179, 2556, 393, 852, 3],
    [1170, 2532, 390, 844, 3],
    [828, 1792, 414, 896, 2],
    [750, 1334, 375, 667, 2],
    [1536, 2048, 768, 1024, 2],
    [1640, 2360, 820, 1180, 2],
    [1668, 2388, 834, 1194, 2],
    [2048, 2732, 1024, 1366, 2],
  ] as const
).map(([w, h, cssW, cssH, dpr]) => ({
  rel: "apple-touch-startup-image",
  href: `/splash-${w}x${h}.png`,
  media: `(device-width: ${cssW}px) and (device-height: ${cssH}px) and (-webkit-device-pixel-ratio: ${dpr}) and (orientation: portrait)`,
}));


function NotFoundComponent() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <h1 className="text-7xl font-bold text-foreground">404</h1>
        <h2 className="mt-4 text-xl font-semibold text-foreground">Page not found</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          The page you're looking for doesn't exist or has been moved.
        </p>
        <div className="mt-6">
          <Link
            to="/"
            className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Go home
          </Link>
        </div>
      </div>
    </div>
  );
}

function ErrorComponent({ error, reset }: { error: Error; reset: () => void }) {
  console.error(error);
  const router = useRouter();
  useEffect(() => {
    reportLovableError(error, { boundary: "tanstack_root_error_component" });
  }, [error]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="max-w-md text-center">
        <h1 className="text-xl font-semibold tracking-tight text-foreground">
          This page didn't load
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Something went wrong on our end. You can try refreshing or head back home.
        </p>
        <div className="mt-6 flex flex-wrap justify-center gap-2">
          <button
            onClick={() => {
              router.invalidate();
              reset();
            }}
            className="inline-flex items-center justify-center rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Try again
          </button>
          <a
            href="/"
            className="inline-flex items-center justify-center rounded-md border border-input bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            Go home
          </a>
        </div>
      </div>
    </div>
  );
}

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  head: () => ({
    meta: [
      { charSet: "utf-8" },
      { name: "viewport", content: "width=device-width, initial-scale=1" },
      { title: "JW Garden Services CRM" },
      {
        name: "description",
        content:
          "Client, visit and invoicing manager for JW Garden Services — schedule jobs, log hours and send invoices.",
      },
      { name: "author", content: "JW Garden Services" },
      { name: "theme-color", content: "#3B5A2E" },
      { name: "msapplication-TileColor", content: "#3B5A2E" },
      { name: "msapplication-navbutton-color", content: "#3B5A2E" },
      { name: "mobile-web-app-capable", content: "yes" },
      { name: "apple-mobile-web-app-title", content: "JW Garden Services CRM" },
      { name: "apple-mobile-web-app-capable", content: "yes" },
      { name: "apple-mobile-web-app-status-bar-style", content: "black-translucent" },
      { property: "og:title", content: "JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Schedule jobs, log hours and send branded invoices in one click.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
    links: [
      { rel: "stylesheet", href: appCss },
      { rel: "preconnect", href: "https://fonts.googleapis.com" },
      { rel: "preconnect", href: "https://fonts.gstatic.com", crossOrigin: "anonymous" },
      {
        rel: "stylesheet",
        href: "https://fonts.googleapis.com/css2?family=Figtree:ital,wght@0,400;0,500;0,600;1,400&family=Outfit:wght@500;600;700&display=swap",
      },
      { rel: "manifest", href: "/manifest.webmanifest" },
      { rel: "icon", href: "/favicon.png", type: "image/png" },
      { rel: "apple-touch-icon", href: "/apple-touch-icon.png", sizes: "180x180" },
      ...APPLE_SPLASH_LINKS,
    ],
  }),
  shellComponent: RootShell,
  component: RootComponent,
  notFoundComponent: NotFoundComponent,
  errorComponent: ErrorComponent,
});

function RootShell({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <head>
        <HeadContent />
      </head>
      <body>
        <div id="app-splash" aria-hidden="true">
          <img src="/icon-512.png" alt="" width={112} height={112} />
          <span>JW Garden Services</span>
        </div>
        {children}
        <Scripts />
      </body>
    </html>
  );
}

function AuthSync() {
  const router = useRouter();
  const { queryClient } = Route.useRouteContext();

  useEffect(() => {
    const { data } = supabase.auth.onAuthStateChange((event) => {
      if (event !== "SIGNED_IN" && event !== "SIGNED_OUT" && event !== "USER_UPDATED") return;
      router.invalidate();
      if (event !== "SIGNED_OUT") queryClient.invalidateQueries();
    });
    return () => data.subscription.unsubscribe();
  }, [router, queryClient]);

  return null;
}

function SplashDismiss() {
  useEffect(() => {
    const splash = document.getElementById("app-splash");
    if (!splash) return;
    splash.setAttribute("data-hidden", "true");
    const timer = window.setTimeout(() => splash.remove(), 350);
    return () => window.clearTimeout(timer);
  }, []);
  return null;
}

function RootComponent() {
  const { queryClient } = Route.useRouteContext();



  return (
    <QueryClientProvider client={queryClient}>
      <AuthSync />
      <ClientOnly fallback={null}>
        <OfflineSupport queryClient={queryClient} />
      </ClientOnly>
      {/* Required: nested routes render here. Removing <Outlet /> breaks all child routes. */}
      <Outlet />
      <Toaster richColors position="top-center" />
    </QueryClientProvider>
  );
}

