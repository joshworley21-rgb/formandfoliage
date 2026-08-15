import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { supabase } from "@/integrations/supabase/client";
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
  const { data, error } = await supabase.auth.getUser();
  if (error || !data.user) throw new Error("Not signed in");
  return data.user.id;
}

/* ---------------- clients ---------------- */

export function useClients() {
  return useQuery({
    queryKey: ["clients"],
    queryFn: async (): Promise<Client[]> => {
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
      const { error } = input.id
        ? await supabase.from("clients").update(payload).eq("id", input.id)
        : await supabase.from("clients").insert(payload);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDeleteClient() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await supabase.from("clients").delete().eq("id", id);
      if (error) throw error;
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
    mutationFn: async (input: Partial<Visit> & { client_id: string }) => {
      const user_id = await currentUserId();
      const payload = { ...input, user_id };
      const { data, error } = input.id
        ? await supabase.from("visits").update(payload).eq("id", input.id).select("id").single()
        : await supabase.from("visits").insert(payload).select("id").single();
      if (error) throw error;
      return data;
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDeleteVisit() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await supabase.from("visits").delete().eq("id", id);
      if (error) throw error;
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
      const { error } = input.id
        ? await supabase.from("scheduled_jobs").update(payload).eq("id", input.id)
        : await supabase.from("scheduled_jobs").insert(payload);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDeleteJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, wholeSeries }: { id: string; wholeSeries?: string | null }) => {
      if (wholeSeries) {
        const { error } = await supabase
          .from("scheduled_jobs")
          .delete()
          .eq("series_id", wholeSeries)
          .eq("status", "scheduled");
        if (error) throw error;
        await supabase.from("job_series").update({ active: false }).eq("id", wholeSeries);
        return;
      }
      const { error } = await supabase.from("scheduled_jobs").delete().eq("id", id);
      if (error) throw error;
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
      const { error } = await supabase.from("invoices").update(patch).eq("id", id);
      if (error) throw error;
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
      const { error } = input.id
        ? await supabase.from("recurring_schedules").update(payload).eq("id", input.id)
        : await supabase.from("recurring_schedules").insert(payload);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useDeleteRecurring() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await supabase.from("recurring_schedules").delete().eq("id", id);
      if (error) throw error;
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
      const { error } = await supabase
        .from("settings")
        .upsert({ ...patch, user_id }, { onConflict: "user_id" });
      if (error) throw error;
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
      const user_id = await currentUserId();
      const payload = { ...input, user_id };
      const { error } = input.id
        ? await supabase.from("job_tasks").update(payload).eq("id", input.id)
        : await supabase.from("job_tasks").insert(payload);
      if (error) throw error;
    },
    onSuccess: (_d, v) => qc.invalidateQueries({ queryKey: ["job_tasks", v.job_id] }),
  });
}

export function useDeleteJobTask() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id }: { id: string; jobId: string }) => {
      const { error } = await supabase.from("job_tasks").delete().eq("id", id);
      if (error) throw error;
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
    mutationFn: async ({ jobId, file, caption }: { jobId: string; file: File; caption?: string }) => {
      const user_id = await currentUserId();
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
