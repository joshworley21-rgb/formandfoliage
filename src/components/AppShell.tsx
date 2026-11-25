import { Link, useNavigate } from "@tanstack/react-router";
import { PullToRefresh } from "@/components/PullToRefresh";
import {
  CalendarDays,
  ClipboardList,
  FileSignature,
  FileText,
  Home,
  Inbox,
  LayoutGrid,
  LogOut,
  Settings as SettingsIcon,
  Users,
} from "lucide-react";
import { useState, type ReactNode } from "react";
import { InstallAppBanner } from "@/components/InstallAppBanner";
import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { Sheet, SheetClose, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { useIsStaff } from "@/hooks/use-workspace";
import { signOutHere } from "@/lib/session";
import { cn } from "@/lib/utils";

const NAV = [
  { to: "/dashboard", label: "Home", icon: Home },
  { to: "/clients", label: "Clients", icon: Users },
  { to: "/schedule", label: "Rota", icon: CalendarDays },
  { to: "/quotes", label: "Quote", icon: FileSignature },
  { to: "/invoices", label: "Invoice", icon: FileText },
  { to: "/enquiries", label: "Enquiries", icon: Inbox },
  { to: "/visits", label: "Visits", icon: ClipboardList },
  { to: "/settings", label: "Settings", icon: SettingsIcon },
] as const;

// Retainers — invoicing a flat amount every cycle whether or not you visited —
// is not how this business bills, so it is not in the navigation. The screen,
// the route and the table all still exist and /recurring still opens it; only
// the standing offer of it is gone. Repeating work is a repeating job on the
// schedule, which is a different feature and the one actually used.

const STAFF_NAV_PATHS = ["/schedule", "/clients"];

// The five that are wanted during a working day. Everything else lives behind
// More, which is what stops Settings and Enquiries from being silently
// unreachable on a phone.
const PHONE_BAR_PATHS: readonly string[] = [
  "/dashboard",
  "/clients",
  "/schedule",
  "/quotes",
  "/invoices",
];

export function AppShell({
  title,
  description,
  actions,
  onRefresh,
  overlap = false,
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
  /**
   * Pulls the first block up into the forest header. Used by Home, where the
   * two money cards are meant to sit half on the band and half on the paper.
   */
  overlap?: boolean;
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
    await signOutHere();
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
                className={cn(
                  "group flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-sidebar-foreground/65 transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground",
                  "data-[status=active]:bg-gold data-[status=active]:font-semibold data-[status=active]:text-gold-foreground data-[status=active]:hover:bg-gold",
                )}
              >
                <span className="flex size-7 items-center justify-center rounded-lg bg-sidebar-accent/50 transition-colors group-hover:bg-sidebar-accent group-data-[status=active]:bg-gold-foreground/15">
                  <item.icon className="size-4" strokeWidth={1.7} />
                </span>
                {item.label}
              </Link>
            ))}
          </nav>
        </div>
        <Button
          variant="ghost"
          onClick={signOut}
          className="justify-start text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
        >
          <LogOut className="size-4" /> Sign out
        </Button>
      </aside>

      <div className="relative flex min-w-0 flex-1 flex-col">
        {/*
          The forest band is the app's one constant. It carries who you are,
          which screen you are on and the action for that screen, so the paper
          underneath can stay quiet.
        */}
        <header className="bg-band px-5 pb-14 pt-[calc(env(safe-area-inset-top)+0.85rem)] text-band-foreground lg:hidden">
          <div className="flex items-center justify-between gap-3">
            <Link to="/dashboard" aria-label="Home">
              <Logo invert showWordmark={false} className="[&_img]:h-8" />
            </Link>
            <div className="flex items-center gap-1">
              {isStaff ? null : (
                <Button
                  variant="ghost"
                  size="icon"
                  asChild
                  aria-label="Settings"
                  className="text-band-foreground/80 hover:bg-band-foreground/10 hover:text-band-foreground"
                >
                  <Link to="/settings">
                    <SettingsIcon className="size-[18px]" strokeWidth={1.8} />
                  </Link>
                </Button>
              )}
              <Button
                variant="ghost"
                size="icon"
                onClick={signOut}
                aria-label="Sign out"
                className="text-band-foreground/80 hover:bg-band-foreground/10 hover:text-band-foreground"
              >
                <LogOut className="size-[18px]" strokeWidth={1.8} />
              </Button>
            </div>
          </div>

          <div className="mt-5 flex items-end justify-between gap-3">
            <div className="min-w-0">
              <h1 className="font-display text-[2rem] font-bold leading-[1.02] tracking-[-0.02em]">
                {title}
              </h1>
              {description ? (
                <p className="mt-1.5 max-w-[34ch] text-[0.8rem] leading-snug text-band-foreground/60">
                  {description}
                </p>
              ) : null}
            </div>
            {actions ? (
              <div className="app-header-actions flex shrink-0 flex-wrap justify-end gap-2">
                {actions}
              </div>
            ) : null}
          </div>
        </header>

        <main
          className={cn("flex-1 px-4 pb-28 lg:px-8 lg:pb-12", overlap ? "-mt-10 lg:mt-0" : "pt-4")}
        >
          <MaybePullToRefresh onRefresh={onRefresh}>
            <div className="mx-auto w-full max-w-5xl">
              <InstallAppBanner />
              <div className="mb-6 hidden flex-wrap items-end justify-between gap-3 lg:flex">
                <div>
                  <h1 className="font-display text-4xl font-bold leading-none tracking-tight text-foreground">
                    {title}
                  </h1>
                  {description ? (
                    <p className="mt-1.5 text-sm text-muted-foreground">{description}</p>
                  ) : null}
                </div>
                {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
              </div>
              {children}
            </div>
          </MaybePullToRefresh>
        </main>

        {/*
          A paper tab bar, flush to the bottom of the screen, the way the
          platform bars it sits between behave. The active tab is the only dark
          thing on it, with a brass dot underneath.
        */}
        <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-border bg-card/95 pb-[env(safe-area-inset-bottom)] backdrop-blur lg:hidden">
          <div
            className={cn(
              "mx-auto grid max-w-lg",
              barSlots <= 2
                ? "grid-cols-2"
                : barSlots === 3
                  ? "grid-cols-3"
                  : barSlots === 4
                    ? "grid-cols-4"
                    : barSlots === 5
                      ? "grid-cols-5"
                      : "grid-cols-6",
            )}
          >
            {barItems.map((item) => (
              <Link
                key={item.to}
                to={item.to}
                className={cn(
                  "group relative flex flex-col items-center gap-1 py-2.5 text-[0.62rem] font-medium tracking-[0.01em] text-muted-foreground transition-colors",
                  // The router sets data-status itself. Colouring off it beats
                  // activeProps, whose class merely joins the base one and
                  // leaves CSS source order to decide which colour wins.
                  "data-[status=active]:text-primary",
                )}
              >
                <item.icon className="size-[22px]" strokeWidth={1.7} />
                {item.label}
                <span
                  aria-hidden
                  className="absolute bottom-1 size-1 rounded-full bg-primary opacity-0 transition-opacity group-data-[status=active]:opacity-100"
                />
              </Link>
            ))}
            {moreItems.length > 0 ? (
              <button
                type="button"
                onClick={() => setMoreOpen(true)}
                className="flex flex-col items-center gap-1 py-2.5 text-[0.62rem] font-medium text-muted-foreground transition-colors hover:text-primary"
              >
                <LayoutGrid className="size-[22px]" strokeWidth={1.7} />
                More
              </button>
            ) : null}
          </div>
        </nav>

        <Sheet open={moreOpen} onOpenChange={setMoreOpen}>
          <SheetContent side="bottom" className="rounded-t-3xl lg:hidden">
            <SheetHeader className="text-left">
              <SheetTitle className="font-display">Everything else</SheetTitle>
            </SheetHeader>
            <div className="mt-4 grid grid-cols-3 gap-2">
              {moreItems.map((item) => (
                <SheetClose asChild key={item.to}>
                  <Link
                    to={item.to}
                    className={cn(
                      "flex flex-col items-center gap-2 rounded-2xl border border-border bg-background px-2 py-4 text-xs font-medium text-foreground",
                      "data-[status=active]:border-primary data-[status=active]:bg-primary data-[status=active]:font-semibold data-[status=active]:text-primary-foreground",
                    )}
                  >
                    <item.icon className="size-5" strokeWidth={1.7} />
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
              className="mt-4 h-11 w-full justify-start text-muted-foreground"
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
