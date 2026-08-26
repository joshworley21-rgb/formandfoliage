import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { supabase } from "@/integrations/supabase/client";
import type { Database } from "@/integrations/supabase/types";
import { currentUser } from "@/lib/session";

export type TeamMember = Database["public"]["Tables"]["team_members"]["Row"];
export type WorkspaceRole = "owner" | "staff";
export type Workspace = { ownerId: string; role: WorkspaceRole };

/**
 * The query itself, shared so anything needing the workspace goes through one
 * cache entry rather than asking the server again. Five minutes: your own role
 * does not change while you are holding the phone.
 */
export function workspaceQueryOptions() {
  return {
    queryKey: ["workspace"] as const,
    staleTime: 5 * 60 * 1000,
    queryFn: async (): Promise<Workspace> => {
      await supabase.rpc("claim_staff_invite");
      const { data, error } = await supabase.rpc("my_workspace");
      if (error) throw error;
      const row = Array.isArray(data) ? data[0] : data;
      const authUser = await currentUser();
      return {
        ownerId: (row?.owner_id as string) ?? authUser?.id ?? "",
        role: ((row?.role as WorkspaceRole) ?? "owner") satisfies WorkspaceRole,
      };
    },
  };
}

/** Who am I working for, and as what? Also links a pending staff invite on first sign-in. */
export function useWorkspace() {
  return useQuery(workspaceQueryOptions());
}

export function useIsStaff(): boolean {
  const { data } = useWorkspace();
  return data?.role === "staff";
}

/* ---------------- team management (owner only) ---------------- */

export function useTeam() {
  return useQuery({
    queryKey: ["team_members"],
    queryFn: async (): Promise<TeamMember[]> => {
      const { data, error } = await supabase.from("team_members").select("*").order("created_at");
      if (error) throw error;
      return data ?? [];
    },
  });
}

export function useInviteMember() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ email, name }: { email: string; name?: string }) => {
      const authUser = await currentUser();
      if (!authUser) throw new Error("Not signed in");
      const { error } = await supabase.from("team_members").insert({
        owner_id: authUser.id,
        email: email.trim().toLowerCase(),
        name: name?.trim() || null,
      });
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["team_members"] }),
  });
}

export function useRemoveMember() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const { error } = await supabase.from("team_members").delete().eq("id", id);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["team_members"] }),
  });
}
