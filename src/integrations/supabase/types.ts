export type Json =
  | string
  | number
  | boolean
  | null
  | { [key: string]: Json | undefined }
  | Json[]

export type Database = {
  // Allows to automatically instantiate createClient with right options
  // instead of createClient<Database, { PostgrestVersion: 'XX' }>(URL, KEY)
  __InternalSupabase: {
    PostgrestVersion: "14.15"
  }
  public: {
    Tables: {
      clients: {
        Row: {
          active: boolean
          address: string | null
          billing_mode: Database["public"]["Enums"]["billing_mode"]
          created_at: string
          email: string | null
          fixed_price: number
          hourly_rate: number
          id: string
          name: string
          notes: string | null
          phone: string | null
          updated_at: string
          user_id: string
        }
        Insert: {
          active?: boolean
          address?: string | null
          billing_mode?: Database["public"]["Enums"]["billing_mode"]
          created_at?: string
          email?: string | null
          fixed_price?: number
          hourly_rate?: number
          id?: string
          name: string
          notes?: string | null
          phone?: string | null
          updated_at?: string
          user_id: string
        }
        Update: {
          active?: boolean
          address?: string | null
          billing_mode?: Database["public"]["Enums"]["billing_mode"]
          created_at?: string
          email?: string | null
          fixed_price?: number
          hourly_rate?: number
          id?: string
          name?: string
          notes?: string | null
          phone?: string | null
          updated_at?: string
          user_id?: string
        }
        Relationships: []
      }
      invoice_items: {
        Row: {
          amount: number
          created_at: string
          description: string
          id: string
          invoice_id: string
          item_date: string | null
          quantity: number
          unit_price: number
          user_id: string
          visit_id: string | null
        }
        Insert: {
          amount?: number
          created_at?: string
          description: string
          id?: string
          invoice_id: string
          item_date?: string | null
          quantity?: number
          unit_price?: number
          user_id: string
          visit_id?: string | null
        }
        Update: {
          amount?: number
          created_at?: string
          description?: string
          id?: string
          invoice_id?: string
          item_date?: string | null
          quantity?: number
          unit_price?: number
          user_id?: string
          visit_id?: string | null
        }
        Relationships: [
          {
            foreignKeyName: "invoice_items_invoice_id_fkey"
            columns: ["invoice_id"]
            isOneToOne: false
            referencedRelation: "invoices"
            referencedColumns: ["id"]
          },
          {
            foreignKeyName: "invoice_items_visit_id_fkey"
            columns: ["visit_id"]
            isOneToOne: false
            referencedRelation: "visits"
            referencedColumns: ["id"]
          },
        ]
      }
      invoices: {
        Row: {
          client_id: string
          created_at: string
          due_date: string | null
          id: string
          issue_date: string
          notes: string | null
          number: string
          paid_at: string | null
          pdf_path: string | null
          period_end: string | null
          period_start: string | null
          sent_at: string | null
          status: Database["public"]["Enums"]["invoice_status"]
          subtotal: number
          total: number
          updated_at: string
          user_id: string
          vat_amount: number
        }
        Insert: {
          client_id: string
          created_at?: string
          due_date?: string | null
          id?: string
          issue_date?: string
          notes?: string | null
          number: string
          paid_at?: string | null
          pdf_path?: string | null
          period_end?: string | null
          period_start?: string | null
          sent_at?: string | null
          status?: Database["public"]["Enums"]["invoice_status"]
          subtotal?: number
          total?: number
          updated_at?: string
          user_id: string
          vat_amount?: number
        }
        Update: {
          client_id?: string
          created_at?: string
          due_date?: string | null
          id?: string
          issue_date?: string
          notes?: string | null
          number?: string
          paid_at?: string | null
          pdf_path?: string | null
          period_end?: string | null
          period_start?: string | null
          sent_at?: string | null
          status?: Database["public"]["Enums"]["invoice_status"]
          subtotal?: number
          total?: number
          updated_at?: string
          user_id?: string
          vat_amount?: number
        }
        Relationships: [
          {
            foreignKeyName: "invoices_client_id_fkey"
            columns: ["client_id"]
            isOneToOne: false
            referencedRelation: "clients"
            referencedColumns: ["id"]
          },
        ]
      }
      job_series: {
        Row: {
          active: boolean
          client_id: string
          created_at: string
          duration_minutes: number
          end_date: string | null
          frequency: Database["public"]["Enums"]["repeat_frequency"]
          id: string
          notes: string | null
          start_date: string
          start_time: string
          updated_at: string
          user_id: string
        }
        Insert: {
          active?: boolean
          client_id: string
          created_at?: string
          duration_minutes?: number
          end_date?: string | null
          frequency?: Database["public"]["Enums"]["repeat_frequency"]
          id?: string
          notes?: string | null
          start_date: string
          start_time?: string
          updated_at?: string
          user_id: string
        }
        Update: {
          active?: boolean
          client_id?: string
          created_at?: string
          duration_minutes?: number
          end_date?: string | null
          frequency?: Database["public"]["Enums"]["repeat_frequency"]
          id?: string
          notes?: string | null
          start_date?: string
          start_time?: string
          updated_at?: string
          user_id?: string
        }
        Relationships: [
          {
            foreignKeyName: "job_series_client_id_fkey"
            columns: ["client_id"]
            isOneToOne: false
            referencedRelation: "clients"
            referencedColumns: ["id"]
          },
        ]
      }
      recurring_schedules: {
        Row: {
          active: boolean
          auto_send: boolean
          client_id: string
          created_at: string
          end_date: string | null
          frequency: Database["public"]["Enums"]["repeat_frequency"]
          id: string
          last_run_at: string | null
          next_run_date: string
          retainer_amount: number
          start_date: string
          updated_at: string
          user_id: string
        }
        Insert: {
          active?: boolean
          auto_send?: boolean
          client_id: string
          created_at?: string
          end_date?: string | null
          frequency?: Database["public"]["Enums"]["repeat_frequency"]
          id?: string
          last_run_at?: string | null
          next_run_date?: string
          retainer_amount?: number
          start_date?: string
          updated_at?: string
          user_id: string
        }
        Update: {
          active?: boolean
          auto_send?: boolean
          client_id?: string
          created_at?: string
          end_date?: string | null
          frequency?: Database["public"]["Enums"]["repeat_frequency"]
          id?: string
          last_run_at?: string | null
          next_run_date?: string
          retainer_amount?: number
          start_date?: string
          updated_at?: string
          user_id?: string
        }
        Relationships: [
          {
            foreignKeyName: "recurring_schedules_client_id_fkey"
            columns: ["client_id"]
            isOneToOne: false
            referencedRelation: "clients"
            referencedColumns: ["id"]
          },
        ]
      }
      scheduled_jobs: {
        Row: {
          client_id: string
          created_at: string
          duration_minutes: number
          id: string
          notes: string | null
          series_id: string | null
          starts_at: string
          status: Database["public"]["Enums"]["job_status"]
          updated_at: string
          user_id: string
          visit_id: string | null
        }
        Insert: {
          client_id: string
          created_at?: string
          duration_minutes?: number
          id?: string
          notes?: string | null
          series_id?: string | null
          starts_at: string
          status?: Database["public"]["Enums"]["job_status"]
          updated_at?: string
          user_id: string
          visit_id?: string | null
        }
        Update: {
          client_id?: string
          created_at?: string
          duration_minutes?: number
          id?: string
          notes?: string | null
          series_id?: string | null
          starts_at?: string
          status?: Database["public"]["Enums"]["job_status"]
          updated_at?: string
          user_id?: string
          visit_id?: string | null
        }
        Relationships: [
          {
            foreignKeyName: "scheduled_jobs_client_id_fkey"
            columns: ["client_id"]
            isOneToOne: false
            referencedRelation: "clients"
            referencedColumns: ["id"]
          },
          {
            foreignKeyName: "scheduled_jobs_series_id_fkey"
            columns: ["series_id"]
            isOneToOne: false
            referencedRelation: "job_series"
            referencedColumns: ["id"]
          },
          {
            foreignKeyName: "scheduled_jobs_visit_id_fkey"
            columns: ["visit_id"]
            isOneToOne: false
            referencedRelation: "visits"
            referencedColumns: ["id"]
          },
        ]
      }
      settings: {
        Row: {
          address: string | null
          bank_details: string | null
          business_name: string
          created_at: string
          email: string | null
          invoice_counter: number
          invoice_prefix: string
          payment_terms: string | null
          phone: string | null
          updated_at: string
          user_id: string
          vat_number: string | null
          vat_rate: number
          vat_registered: boolean
        }
        Insert: {
          address?: string | null
          bank_details?: string | null
          business_name?: string
          created_at?: string
          email?: string | null
          invoice_counter?: number
          invoice_prefix?: string
          payment_terms?: string | null
          phone?: string | null
          updated_at?: string
          user_id: string
          vat_number?: string | null
          vat_rate?: number
          vat_registered?: boolean
        }
        Update: {
          address?: string | null
          bank_details?: string | null
          business_name?: string
          created_at?: string
          email?: string | null
          invoice_counter?: number
          invoice_prefix?: string
          payment_terms?: string | null
          phone?: string | null
          updated_at?: string
          user_id?: string
          vat_number?: string | null
          vat_rate?: number
          vat_registered?: boolean
        }
        Relationships: []
      }
      visits: {
        Row: {
          amount: number
          billed: boolean
          client_id: string
          created_at: string
          description: string | null
          hours: number
          id: string
          invoice_id: string | null
          materials_cost: number
          updated_at: string
          user_id: string
          visit_date: string
        }
        Insert: {
          amount?: number
          billed?: boolean
          client_id: string
          created_at?: string
          description?: string | null
          hours?: number
          id?: string
          invoice_id?: string | null
          materials_cost?: number
          updated_at?: string
          user_id: string
          visit_date?: string
        }
        Update: {
          amount?: number
          billed?: boolean
          client_id?: string
          created_at?: string
          description?: string | null
          hours?: number
          id?: string
          invoice_id?: string | null
          materials_cost?: number
          updated_at?: string
          user_id?: string
          visit_date?: string
        }
        Relationships: [
          {
            foreignKeyName: "visits_client_id_fkey"
            columns: ["client_id"]
            isOneToOne: false
            referencedRelation: "clients"
            referencedColumns: ["id"]
          },
          {
            foreignKeyName: "visits_invoice_fk"
            columns: ["invoice_id"]
            isOneToOne: false
            referencedRelation: "invoices"
            referencedColumns: ["id"]
          },
        ]
      }
    }
    Views: {
      [_ in never]: never
    }
    Functions: {
      next_invoice_number: { Args: { _user_id: string }; Returns: string }
    }
    Enums: {
      billing_mode: "hourly" | "fixed"
      invoice_status: "draft" | "sent" | "paid"
      job_status: "scheduled" | "done" | "missed" | "cancelled"
      repeat_frequency:
        | "weekly"
        | "fortnightly"
        | "four_weekly"
        | "monthly"
        | "quarterly"
    }
    CompositeTypes: {
      [_ in never]: never
    }
  }
}

type DatabaseWithoutInternals = Omit<Database, "__InternalSupabase">

type DefaultSchema = DatabaseWithoutInternals[Extract<keyof Database, "public">]

export type Tables<
  DefaultSchemaTableNameOrOptions extends
    | keyof (DefaultSchema["Tables"] & DefaultSchema["Views"])
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
        DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? (DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"] &
      DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Views"])[TableName] extends {
      Row: infer R
    }
    ? R
    : never
  : DefaultSchemaTableNameOrOptions extends keyof (DefaultSchema["Tables"] &
        DefaultSchema["Views"])
    ? (DefaultSchema["Tables"] &
        DefaultSchema["Views"])[DefaultSchemaTableNameOrOptions] extends {
        Row: infer R
      }
      ? R
      : never
    : never

export type TablesInsert<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Insert: infer I
    }
    ? I
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
    ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
        Insert: infer I
      }
      ? I
      : never
    : never

export type TablesUpdate<
  DefaultSchemaTableNameOrOptions extends
    | keyof DefaultSchema["Tables"]
    | { schema: keyof DatabaseWithoutInternals },
  TableName extends DefaultSchemaTableNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"]
    : never = never,
> = DefaultSchemaTableNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaTableNameOrOptions["schema"]]["Tables"][TableName] extends {
      Update: infer U
    }
    ? U
    : never
  : DefaultSchemaTableNameOrOptions extends keyof DefaultSchema["Tables"]
    ? DefaultSchema["Tables"][DefaultSchemaTableNameOrOptions] extends {
        Update: infer U
      }
      ? U
      : never
    : never

export type Enums<
  DefaultSchemaEnumNameOrOptions extends
    | keyof DefaultSchema["Enums"]
    | { schema: keyof DatabaseWithoutInternals },
  EnumName extends DefaultSchemaEnumNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"]
    : never = never,
> = DefaultSchemaEnumNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[DefaultSchemaEnumNameOrOptions["schema"]]["Enums"][EnumName]
  : DefaultSchemaEnumNameOrOptions extends keyof DefaultSchema["Enums"]
    ? DefaultSchema["Enums"][DefaultSchemaEnumNameOrOptions]
    : never

export type CompositeTypes<
  PublicCompositeTypeNameOrOptions extends
    | keyof DefaultSchema["CompositeTypes"]
    | { schema: keyof DatabaseWithoutInternals },
  CompositeTypeName extends PublicCompositeTypeNameOrOptions extends {
    schema: keyof DatabaseWithoutInternals
  }
    ? keyof DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"]
    : never = never,
> = PublicCompositeTypeNameOrOptions extends {
  schema: keyof DatabaseWithoutInternals
}
  ? DatabaseWithoutInternals[PublicCompositeTypeNameOrOptions["schema"]]["CompositeTypes"][CompositeTypeName]
  : PublicCompositeTypeNameOrOptions extends keyof DefaultSchema["CompositeTypes"]
    ? DefaultSchema["CompositeTypes"][PublicCompositeTypeNameOrOptions]
    : never

export const Constants = {
  public: {
    Enums: {
      billing_mode: ["hourly", "fixed"],
      invoice_status: ["draft", "sent", "paid"],
      job_status: ["scheduled", "done", "missed", "cancelled"],
      repeat_frequency: [
        "weekly",
        "fortnightly",
        "four_weekly",
        "monthly",
        "quarterly",
      ],
    },
  },
} as const
