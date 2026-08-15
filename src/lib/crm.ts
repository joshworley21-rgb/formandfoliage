import type { Database } from "@/integrations/supabase/types";

export type Client = Database["public"]["Tables"]["clients"]["Row"];
export type Visit = Database["public"]["Tables"]["visits"]["Row"];
export type ScheduledJob = Database["public"]["Tables"]["scheduled_jobs"]["Row"];
export type JobSeries = Database["public"]["Tables"]["job_series"]["Row"];
export type Invoice = Database["public"]["Tables"]["invoices"]["Row"];
export type InvoiceEmailEvent = Database["public"]["Tables"]["invoice_email_events"]["Row"];
export type InvoiceItem = Database["public"]["Tables"]["invoice_items"]["Row"];
export type RecurringSchedule = Database["public"]["Tables"]["recurring_schedules"]["Row"];
export type Settings = Database["public"]["Tables"]["settings"]["Row"];

export type Frequency = Database["public"]["Enums"]["repeat_frequency"];
export type JobStatus = Database["public"]["Enums"]["job_status"];
export type BillingMode = Database["public"]["Enums"]["billing_mode"];

export const FREQUENCY_LABELS: Record<Frequency, string> = {
  weekly: "Weekly",
  fortnightly: "Fortnightly",
  four_weekly: "Every 4 weeks",
  monthly: "Monthly",
  quarterly: "Quarterly",
};

export const JOB_STATUS_LABELS: Record<JobStatus, string> = {
  scheduled: "Scheduled",
  done: "Done",
  missed: "Missed",
  cancelled: "Cancelled",
};

export function money(value: number | string | null | undefined): string {
  const n = Number(value ?? 0);
  return new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" }).format(n);
}

export function hoursLabel(value: number | string | null | undefined): string {
  const n = Number(value ?? 0);
  return `${n % 1 === 0 ? n.toFixed(0) : n.toFixed(2)} h`;
}

/** Price a visit from the client's billing setup. */
export function priceVisit(
  client: Pick<Client, "billing_mode" | "hourly_rate" | "fixed_price">,
  hours: number,
  materialsCost: number,
): number {
  const labour =
    client.billing_mode === "fixed"
      ? Number(client.fixed_price ?? 0)
      : Number(client.hourly_rate ?? 0) * (Number.isFinite(hours) ? hours : 0);
  return Math.round((labour + (Number(materialsCost) || 0)) * 100) / 100;
}
