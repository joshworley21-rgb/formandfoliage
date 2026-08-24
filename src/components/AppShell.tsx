import { Link, useNavigate } from "@tanstack/react-router";
import { PullToRefresh } from "@/components/PullToRefresh";
import {
  CalendarDays,
  ClipboardList,
  FileSignature,
  FileText,
  Inbox,
  LayoutDashboard,
  LogOut,
  MoreHorizontal,
  Repeat,
  Settings as SettingsIcon,
  Users,
} from "lucide-react";
import { useState, type ReactNode } from "react";
import { InstallAppBanner } from "@/components/InstallAppBanner";
import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { useIsStaff } from "@/hooks/use-workspace";
import { supabase } from "@/integrations/supabase/client";
import { cn } from "@/lib/utils";

const NAV = [
  { to: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { to: "/schedule", label: "Schedule", icon: CalendarDays },
  { to: "/clients", label: "Clients", icon: Users },
  { to: "/enquiries", label: "Enquiries", icon: Inbox },
  { to: "/quotes", label: "Quotes", icon: FileSignature },
  { to: "/visits", label: "Visits", icon: ClipboardList },
  { to: "/invoices", label: "Invoices", icon: FileText },
  { to: "/recurring", label: "Recurring", icon: Repeat },
  { to: "/settings", label: "Settings", icon: SettingsIcon },
] as const;

const STAFF_NAV_PATHS = ["/schedule", "/clients"];

// The phone has room for five things along the bottom, and there are nine.
// These four are the ones wanted mid-job; everything else lives behind More,
// which is what stops the rest — Settings and Invoices among them — from being
// silently unreachable on a phone.
const PHONE_BAR_PATHS: readonly string[] = [
  "/dashboard",
  "/schedule",
  "/clients",
  "/invoices",
];

export function AppShell({
  title,
  description,
  actions,
  onRefresh,
  children,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
  /**
   * Supply this and the page can be dragged down to reload. Opt-in per page:
   * a pull that refreshes nothing is worse than no pull at all.
   */
  onRefresh?: () => Promise<unknown>;
  children: ReactNode;
}) {
  const navigate = useNavigate();
  const isStaff = useIsStaff();
  const [moreOpen, setMoreOpen] = useState(false);
  const nav = isStaff ? NAV.filter((item) => STAFF_NAV_PATHS.includes(item.to)) : NAV;

  const barItems = nav.filter((item) => PHONE_BAR_PATHS.includes(item.to));
  const moreItems = nav.filter((item) => !PHONE_BAR_PATHS.includes(item.to));
  const barSlots = barItems.length + (moreItems.length > 0 ? 1 : 0);

  async function signOut() {
    await supabase.auth.signOut();
    navigate({ to: "/auth" });
  }

  return (
    <div className="min-h-screen bg-background lg:flex">
      <aside className="hidden w-64 shrink-0 flex-col justify-between bg-sidebar px-4 py-6 text-sidebar-foreground lg:flex">
        <div>
          <Logo className="px-2 text-sidebar-foreground" invert />
          <nav className="mt-8 space-y-1">
            {nav.map((item) => (
              <Link
                key={item.to}
                to={item.to}
                className="group flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-sidebar-foreground/70 transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
                activeProps={{
                  className:
                    "group flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold bg-sidebar-primary text-sidebar-primary-foreground hover:bg-sidebar-primary",
                }}
              >
                <span className="flex size-7 items-center justify-center rounded-lg bg-sidebar-accent/50 transition-colors group-hover:bg-sidebar-accent group-[.active]:bg-sidebar-primary-foreground/20">
                  <item.icon className="size-4" />
                </span>
                {item.label}
              </Link>
            ))}
          </nav>
        </div>
        <Button
          variant="ghost"
          onClick={signOut}
          className="justify-start text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
        >
          <LogOut className="size-4" /> Sign out
        </Button>
      </aside>

      <div className="relative flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 border-b border-border bg-background/90 backdrop-blur lg:hidden">
          <div className="flex items-center justify-between px-4 py-3">
            <Logo />
            <div className="flex items-center gap-1">
              {isStaff ? null : (
                <Button variant="ghost" size="icon" asChild aria-label="Settings">
                  <Link to="/settings">
                    <SettingsIcon className="size-4" />
                  </Link>
                </Button>
              )}
              <Button variant="ghost" size="icon" onClick={signOut} aria-label="Sign out">
                <LogOut className="size-4" />
              </Button>
            </div>
          </div>
        </header>

        <main className="flex-1 px-4 pb-28 pt-6 lg:px-8 lg:pb-12">
          <MaybePullToRefresh onRefresh={onRefresh}>
          <div className="mx-auto w-full max-w-5xl">
            <InstallAppBanner />
            <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
              <div>
                <h1 className="font-display text-2xl font-bold tracking-tight text-foreground lg:text-3xl">
                  {title}
                </h1>
                {description ? (
                  <p className="mt-1 text-sm text-muted-foreground">{description}</p>
                ) : null}
              </div>
              {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
            </div>
            {children}
          </div>
          </MaybePullToRefresh>
        </main>

        <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-border bg-card/95 backdrop-blur lg:hidden">
          <div
            className={cn(
              "grid",
              barSlots <= 2
                ? "grid-cols-2"
                : barSlots === 3
                  ? "grid-cols-3"
                  : barSlots === 4
                    ? "grid-cols-4"
                    : "grid-cols-5",
            )}
          >
            {barItems.map((item) => (
              <Link
                key={item.to}
                to={item.to}
                className={cn(
                  "flex flex-col items-center gap-1 py-2.5 text-[0.65rem] font-medium text-muted-foreground",
                )}
                activeProps={{
                  className:
                    "flex flex-col items-center gap-1 py-2.5 text-[0.65rem] font-semibold text-primary",
                }}
              >
                <item.icon className="size-5" />
                {item.label}
              </Link>
            ))}
            {moreItems.length > 0 ? (
              <button
                type="button"
                onClick={() => setMoreOpen(true)}
                className="flex flex-col items-center gap-1 py-2.5 text-[0.65rem] font-medium text-muted-foreground"
              >
                <MoreHorizontal className="size-5" />
                More
              </button>
            ) : null}
          </div>
        </nav>

        <Sheet open={moreOpen} onOpenChange={setMoreOpen}>
          <SheetContent side="bottom" className="lg:hidden">
            <SheetHeader className="text-left">
              <SheetTitle className="font-display">Everything else</SheetTitle>
            </SheetHeader>
            <div className="mt-4 grid grid-cols-3 gap-2">
              {moreItems.map((item) => (
                <SheetClose asChild key={item.to}>
                  <Link
                    to={item.to}
                    className="flex flex-col items-center gap-2 rounded-xl border border-border bg-card px-2 py-4 text-xs font-medium text-foreground"
                    activeProps={{
                      className:
                        "flex flex-col items-center gap-2 rounded-xl border border-primary bg-primary/10 px-2 py-4 text-xs font-semibold text-primary",
                    }}
                  >
                    <item.icon className="size-5" />
                    {item.label}
                  </Link>
                </SheetClose>
              ))}
            </div>
            <Button
              variant="ghost"
              onClick={() => {
                setMoreOpen(false);
                void signOut();
              }}
              className="mt-4 w-full justify-start text-muted-foreground"
            >
              <LogOut className="size-4" /> Sign out
            </Button>
          </SheetContent>
        </Sheet>
      </div>
    </div>
  );
}


/** Keeps the markup identical on pages that have not opted in. */
function MaybePullToRefresh({
  onRefresh,
  children,
}: {
  onRefresh?: (() => Promise<unknown>) | undefined;
  children: ReactNode;
}) {
  if (!onRefresh) return <>{children}</>;
  return <PullToRefresh onRefresh={onRefresh}>{children}</PullToRefresh>;
}
