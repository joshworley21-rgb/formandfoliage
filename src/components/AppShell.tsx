import { Link, useNavigate } from "@tanstack/react-router";
import {
  CalendarDays,
  ClipboardList,
  FileText,
  LayoutDashboard,
  LogOut,
  Repeat,
  Settings as SettingsIcon,
  Users,
} from "lucide-react";
import type { ReactNode } from "react";
import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { useIsStaff } from "@/hooks/use-workspace";
import { supabase } from "@/integrations/supabase/client";
import { cn } from "@/lib/utils";

const NAV = [
  { to: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { to: "/schedule", label: "Schedule", icon: CalendarDays },
  { to: "/clients", label: "Clients", icon: Users },
  { to: "/visits", label: "Visits", icon: ClipboardList },
  { to: "/invoices", label: "Invoices", icon: FileText },
  { to: "/recurring", label: "Recurring", icon: Repeat },
  { to: "/settings", label: "Settings", icon: SettingsIcon },
] as const;

const STAFF_NAV_PATHS = ["/schedule", "/clients"];

export function AppShell({
  title,
  description,
  actions,
  children,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const navigate = useNavigate();
  const isStaff = useIsStaff();
  const nav = isStaff ? NAV.filter((item) => STAFF_NAV_PATHS.includes(item.to)) : NAV;

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
            <Button variant="ghost" size="icon" onClick={signOut} aria-label="Sign out">
              <LogOut className="size-4" />
            </Button>
          </div>
        </header>

        <main className="flex-1 px-4 pb-28 pt-6 lg:px-8 lg:pb-12">
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
        </main>

        <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-border bg-card/95 backdrop-blur lg:hidden">
          <div className={cn("grid", nav.length <= 2 ? "grid-cols-2" : nav.length === 3 ? "grid-cols-3" : nav.length === 4 ? "grid-cols-4" : "grid-cols-5")}>
            {nav.slice(0, 5).map((item) => (
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
          </div>
        </nav>
      </div>
    </div>
  );
}
