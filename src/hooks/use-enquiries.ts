import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { supabase } from "@/integrations/supabase/client";
import type { Database } from "@/integrations/supabase/types";
import { currentUser } from "@/lib/session";
import { runOrQueue, type QueuedOp } from "@/lib/offline-queue";

export type Enquiry = Database["public"]["Tables"]["enquiries"]["Row"];
export type EnquiryStatus = "new" | "contacted" | "converted" | "closed";

export const ENQUIRY_STATUS_LABELS: Record<EnquiryStatus, string> = {
  new: "New",
  contacted: "Contacted",
  converted: "Converted",
  closed: "Closed",
};

export function useEnquiries() {
  return useQuery({
    queryKey: ["enquiries"],
    queryFn: async (): Promise<Enquiry[]> => {
      const { data, error } = await supabase
        .from("enquiries")
        .select("*")
        .order("created_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useUpdateEnquiry() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...patch }: Partial<Enquiry> & { id: string }) => {
      const { error } = await supabase.from("enquiries").update(patch).eq("id", id);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["enquiries"] }),
  });
}

export function useDeleteEnquiry() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await supabase.from("enquiries").delete().eq("id", id);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["enquiries"] }),
  });
}

/** Create a client from an enquiry and mark it converted. */
export function useConvertEnquiry() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (enquiry: Enquiry) => {
      const authUser = await currentUser();
      const userId = authUser?.id;
      if (!userId) throw new Error("Not signed in");

      const clientId = crypto.randomUUID();
      const ops: QueuedOp[] = [
        {
          kind: "insert",
          table: "clients",
          payload: {
            id: clientId,
            user_id: userId,
            name: enquiry.name,
            email: enquiry.email,
            phone: enquiry.phone,
            address: enquiry.address,
            notes: enquiry.message,
          },
        },
        {
          kind: "update",
          table: "enquiries",
          payload: { status: "converted", client_id: clientId },
          match: { id: enquiry.id },
        },
      ];
      await runOrQueue("Convert enquiry", ops);
      return clientId;
    },
    onSuccess: () => qc.invalidateQueries(),
  });
}

export function useEnquiryLink() {
  return useQuery({
    queryKey: ["enquiry-key"],
    queryFn: async (): Promise<string | null> => {
      const { data, error } = await supabase
        .from("settings")
        .select("enquiry_key")
        .limit(1)
        .maybeSingle();
      if (error) throw error;
      return data?.enquiry_key ?? null;
    },
  });
}
