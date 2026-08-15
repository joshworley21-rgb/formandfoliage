import { Navigate } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useWorkspace } from "@/hooks/use-workspace";

/**
 * Wraps owner-only pages (money, invoicing, business settings).
 * Staff accounts are sent back to the schedule.
 */
export function OwnerOnly({ children }: { children: ReactNode }) {
  const { data, isLoading } = useWorkspace();
  if (isLoading) return null;
  if (data?.role === "staff") return <Navigate to="/schedule" replace />;
  return <>{children}</>;
}
