import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { supabase } from "@/integrations/supabase/client";
import { requireUserId } from "@/lib/session";
import { runOrQueue } from "@/lib/offline-queue";
import type {
  Client,
  Invoice,
  InvoiceEmailEvent,
  InvoiceItem,
  JobPhoto,
  JobSeries,
  JobTask,
  RecurringSchedule,
  ScheduledJob,
  Settings,
  Visit,
} from "@/lib/crm";

async function currentUserId(): Promise<string> {
  return requireUserId();
}

/** Owner of the workspace the signed-in user belongs to (themselves, or their employer). */
async function workspaceOwnerId(): Promise<string> {
  const { data } = await supabase.rpc("my_workspace");
  const row = Array.isArray(data) ? data[0] : data;
  return (row?.owner_id as string) ?? (await currentUserId());
}

/* ---------------- clients ---------------- */

export function useClients() {
  return useQuery({
    queryKey: ["clients"],
    queryFn: async (): Promise<Client[]> => {
      const { data: ws } = await supabase.rpc("my_workspace");
      const wsRow = Array.isArray(ws) ? ws[0] : ws;
      if (wsRow?.role === "staff") {
        // Staff only ever see name / address / notes — never contact details or rates.
        const { data, error } = await supabase.rpc("staff_clients");
        if (error) throw error;
        return (data ?? []).map((row) => ({
          id: row.id,
          user_id: (wsRow.owner_id as string) ?? "",
          name: row.name,
          address: row.address,
          notes: row.notes,
          phone: null,
          email: null,
          billing_mode: "hourly",
          hourly_rate: 0,
          fixed_price: 0,
          active: true,
          created_at: "",
          updated_at: "",
        })) as Client[];
      }
      const { data, error } = await supabase.from("clients").select("*").order("name");
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useSaveClient() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: Partial<Client> & { name: string }) => {
      const user_id = await currentUserId();
      const payload = { ...input, user_id };
      await runOrQueue(input.id ? `Client "${input.name}" update` : `New client "${input.name}"`, [
        input.id
          ? { kind: "update", table: "clients", payload, match: { id: input.id } }
          : { kind: "insert", table: "clients", payload },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDeleteClient() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await runOrQueue("Delete client", [{ kind: "delete", table: "clients", match: { id } }]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

/* ---------------- visits ---------------- */

export function useVisits(clientId?: string) {
  return useQuery({
    queryKey: ["visits", clientId ?? "all"],
    queryFn: async (): Promise<Visit[]> => {
      let q = supabase.from("visits").select("*").order("visit_date", { ascending: false });
      if (clientId) q = q.eq("client_id", clientId);
      const { data, error } = await q;
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useSaveVisit() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      completesJobId,
      ...input
    }: Partial<Visit> & { client_id: string; completesJobId?: string | null }) => {
      const user_id = await currentUserId();
      // Generate the id client-side so the visit can be linked (and queued) offline.
      const id = input.id ?? crypto.randomUUID();
      const payload = { ...input, id, user_id };

      // The job and the visit are the same afternoon in the garden. Both writes
      // go in one batch so that offline they queue together and replay in
      // order — a completed job pointing at a visit that never arrived would be
      // worse than neither.
      const ops: Parameters<typeof runOrQueue>[1] = [
        input.id
          ? { kind: "update", table: "visits", payload, match: { id } }
          : { kind: "insert", table: "visits", payload },
      ];
      if (completesJobId) {
        ops.push({
          kind: "update",
          table: "scheduled_jobs",
          payload: { status: "done", visit_id: id },
          match: { id: completesJobId },
        });
      }

      await runOrQueue(input.id ? "Visit update" : "New visit", ops);
      return { id, completedJob: Boolean(completesJobId) };
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDeleteVisit() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await runOrQueue("Delete visit", [{ kind: "delete", table: "visits", match: { id } }]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

/* ---------------- scheduled jobs ---------------- */

export function useJobs(range?: { from: string; to: string }) {
  return useQuery({
    queryKey: ["jobs", range?.from ?? "all", range?.to ?? "all"],
    queryFn: async (): Promise<ScheduledJob[]> => {
      let q = supabase.from("scheduled_jobs").select("*").order("starts_at");
      if (range) q = q.gte("starts_at", range.from).lte("starts_at", range.to);
      const { data, error } = await q;
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useSaveJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: Partial<ScheduledJob> & { client_id: string; starts_at: string }) => {
      const user_id = await currentUserId();
      const payload = { ...input, user_id };
      await runOrQueue(input.id ? "Job update" : "New job", [
        input.id
          ? { kind: "update", table: "scheduled_jobs", payload, match: { id: input.id } }
          : { kind: "insert", table: "scheduled_jobs", payload },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDeleteJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, wholeSeries }: { id: string; wholeSeries?: string | null }) => {
      if (wholeSeries) {
        await runOrQueue("Delete repeating job", [
          {
            kind: "delete",
            table: "scheduled_jobs",
            match: { series_id: wholeSeries, status: "scheduled" },
          },
          {
            kind: "update",
            table: "job_series",
            payload: { active: false },
            match: { id: wholeSeries },
          },
        ]);
        return;
      }
      await runOrQueue("Delete job", [{ kind: "delete", table: "scheduled_jobs", match: { id } }]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useSeries() {
  return useQuery({
    queryKey: ["job_series"],
    queryFn: async (): Promise<JobSeries[]> => {
      const { data, error } = await supabase.from("job_series").select("*").order("created_at");
      if (error) throw error;
      return data ?? [];
    },
  });
}

/* ---------------- invoices ---------------- */

export function useInvoices() {
  return useQuery({
    queryKey: ["invoices"],
    queryFn: async (): Promise<Invoice[]> => {
      const { data, error } = await supabase
        .from("invoices")
        .select("*")
        .order("issue_date", { ascending: false })
        .order("created_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useInvoiceEmailEvents(invoiceId?: string) {
  return useQuery({
    enabled: Boolean(invoiceId),
    queryKey: ["invoice_email_events", invoiceId],
    queryFn: async (): Promise<InvoiceEmailEvent[]> => {
      const { data, error } = await supabase
        .from("invoice_email_events")
        .select("*")
        .eq("invoice_id", invoiceId!)
        .order("created_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useAllInvoiceEmailEvents() {
  return useQuery({
    queryKey: ["invoice_email_events", "all"],
    queryFn: async (): Promise<InvoiceEmailEvent[]> => {
      const { data, error } = await supabase
        .from("invoice_email_events")
        .select("*")
        .order("created_at", { ascending: false })
        .limit(100);
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useInvoiceItems(invoiceId?: string) {
  return useQuery({
    enabled: Boolean(invoiceId),
    queryKey: ["invoice_items", invoiceId],
    queryFn: async (): Promise<InvoiceItem[]> => {
      const { data, error } = await supabase
        .from("invoice_items")
        .select("*")
        .eq("invoice_id", invoiceId!)
        .order("item_date");
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useUpdateInvoice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, patch }: { id: string; patch: Partial<Invoice> }) => {
      await runOrQueue("Invoice update", [
        { kind: "update", table: "invoices", payload: patch, match: { id } },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

/** Delete an invoice: unbill its visits, then remove items, email events and the invoice. */
export function useDeleteInvoice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await runOrQueue("Delete invoice", [
        {
          kind: "update",
          table: "visits",
          payload: { billed: false, invoice_id: null },
          match: { invoice_id: id },
        },
        {
          kind: "update",
          table: "bank_transactions",
          payload: { invoice_id: null, matched_at: null },
          match: { invoice_id: id },
        },
        { kind: "delete", table: "invoice_items", match: { invoice_id: id } },
        { kind: "delete", table: "invoice_email_events", match: { invoice_id: id } },
        { kind: "delete", table: "invoices", match: { id } },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

/* ---------------- recurring ---------------- */

export function useRecurring() {
  return useQuery({
    queryKey: ["recurring"],
    queryFn: async (): Promise<RecurringSchedule[]> => {
      const { data, error } = await supabase
        .from("recurring_schedules")
        .select("*")
        .order("next_run_date");
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useSaveRecurring() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: Partial<RecurringSchedule> & { client_id: string }) => {
      const user_id = await currentUserId();
      const payload = { ...input, user_id };
      await runOrQueue("Recurring schedule", [
        input.id
          ? { kind: "update", table: "recurring_schedules", payload, match: { id: input.id } }
          : { kind: "insert", table: "recurring_schedules", payload },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDeleteRecurring() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await runOrQueue("Delete recurring schedule", [
        { kind: "delete", table: "recurring_schedules", match: { id } },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

/* ---------------- settings ---------------- */

export function useSettings() {
  return useQuery({
    queryKey: ["settings"],
    queryFn: async (): Promise<Settings | null> => {
      const user_id = await currentUserId();
      const { data, error } = await supabase
        .from("settings")
        .select("*")
        .eq("user_id", user_id)
        .maybeSingle();
      if (error) throw error;
      if (data) return data;
      const { data: created, error: insertError } = await supabase
        .from("settings")
        .insert({ user_id })
        .select("*")
        .single();
      if (insertError) throw insertError;
      return created;
    },
  });
}

export function useSaveSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (patch: Partial<Settings>) => {
      const user_id = await currentUserId();
      await runOrQueue("Settings update", [
        {
          kind: "upsert",
          table: "settings",
          payload: { ...patch, user_id },
          onConflict: "user_id",
        },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

/* ---------------- job sheets ---------------- */

export function useJobTasks(jobId?: string) {
  return useQuery({
    enabled: Boolean(jobId),
    queryKey: ["job_tasks", jobId],
    queryFn: async (): Promise<JobTask[]> => {
      const { data, error } = await supabase
        .from("job_tasks")
        .select("*")
        .eq("job_id", jobId!)
        .order("position")
        .order("created_at");
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useSaveJobTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: Partial<JobTask> & { job_id: string; title: string }) => {
      const user_id = await workspaceOwnerId();
      const payload = { ...input, user_id };
      await runOrQueue("Job sheet task", [
        input.id
          ? { kind: "update", table: "job_tasks", payload, match: { id: input.id } }
          : { kind: "insert", table: "job_tasks", payload },
      ]);
    },
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: ["job_tasks", v.job_id] }),
  });
}

export function useDeleteJobTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id }: { id: string; jobId: string }) => {
      await runOrQueue("Delete job sheet task", [
        { kind: "delete", table: "job_tasks", match: { id } },
      ]);
    },
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: ["job_tasks", v.jobId] }),
  });
}

export function useJobPhotos(jobId?: string) {
  return useQuery({
    enabled: Boolean(jobId),
    queryKey: ["job_photos", jobId],
    queryFn: async (): Promise<(JobPhoto & { url: string | null })[]> => {
      const { data, error } = await supabase
        .from("job_photos")
        .select("*")
        .eq("job_id", jobId!)
        .order("created_at", { ascending: false });
      if (error) throw error;
      const rows = data ?? [];
      const signed = await Promise.all(
        rows.map(async (row) => {
          const { data: s } = await supabase.storage
            .from("job-photos")
            .createSignedUrl(row.storage_path, 60 * 60);
          return { ...row, url: s?.signedUrl ?? null };
        }),
      );
      return signed;
    },
  });
}

export function useUploadJobPhoto() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      jobId,
      file,
      caption,
    }: {
      jobId: string;
      file: File;
      caption?: string;
    }) => {
      const user_id = await workspaceOwnerId();
      const ext = file.name.split(".").pop()?.toLowerCase() || "jpg";
      const path = `${user_id}/${jobId}/${crypto.randomUUID()}.${ext}`;
      const { error: upErr } = await supabase.storage
        .from("job-photos")
        .upload(path, file, { contentType: file.type || "image/jpeg" });
      if (upErr) throw upErr;
      const { error } = await supabase
        .from("job_photos")
        .insert({ user_id, job_id: jobId, storage_path: path, caption: caption ?? null });
      if (error) throw error;
    },
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: ["job_photos", v.jobId] }),
  });
}

export function useDeleteJobPhoto() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, path }: { id: string; path: string; jobId: string }) => {
      await supabase.storage.from("job-photos").remove([path]);
      const { error } = await supabase.from("job_photos").delete().eq("id", id);
      if (error) throw error;
    },
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: ["job_photos", v.jobId] }),
  });
}

/* ---------------- staff job completion ---------------- */

/** Staff mark a job done; the visit is created and priced on the server. */
export function useStaffCompleteJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({
      jobId,
      hours,
      description,
    }: {
      jobId: string;
      hours: number;
      description?: string;
    }) => {
      await runOrQueue("Job completion", [
        {
          kind: "rpc",
          fn: "staff_complete_job",
          args: {
            _job_id: jobId,
            _hours: hours,
            ...(description ? { _description: description } : {}),
          },
        },
      ]);
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}
