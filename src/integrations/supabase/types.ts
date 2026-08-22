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
      bank_connections: {
        Row: {
          access_token: string
          account_id: string | null
          account_name: string | null
          created_at: string
          expires_at: string | null
          id: string
          last_synced_at: string | null
          provider: string
          refresh_token: string | null
          updated_at: string
          user_id: string
        }
        Insert: {
          access_token: string
          account_id?: string | null
          account_name?: string | null
          created_at?: string
          expires_at?: string | null
          id?: string
          last_synced_at?: string | null
          provider?: string
          refresh_token?: string | null
          updated_at?: string
          user_id: string
        }
        Update: {
          access_token?: string
          account_id?: string | null
          account_name?: string | null
          created_at?: string
          expires_at?: string | null
          id?: string
          last_synced_at?: string | null
          provider?: string
          refresh_token?: string | null
          updated_at?: string
          user_id?: string
        }
        Relationships: []
      }
      bank_transactions: {
        Row: {
          amount: number
          counterparty: string | null
          created_at: string
          currency: string
          description: string | null
          id: string
          ignored: boolean
          invoice_id: string | null
          matched_at: string | null
          posted_at: string
          provider_transaction_id: string
          reference: string | null
          updated_at: string
          user_id: string
        }
        Insert: {
          amount?: number
          counterparty?: string | null
          created_at?: string
          currency?: string
          description?: string | null
          id?: string
          ignored?: boolean
          invoice_id?: string | null
          matched_at?: string | null
          posted_at: string
          provider_transaction_id: string
          reference?: string | null
          updated_at?: string
          user_id: string
        }
        Update: {
          amount?: number
          counterparty?: string | null
          created_at?: string
          currency?: string
          description?: string | null
          id?: string
          ignored?: boolean
          invoice_id?: string | null
          matched_at?: string | null
          posted_at?: string
          provider_transaction_id?: string
          reference?: string | null
          updated_at?: string
          user_id?: string
        }
        Relationships: [
          {
            foreignKeyName: "bank_transactions_invoice_id_fkey"
            columns: ["invoice_id"]
            isOneToOne: false
            referencedRelation: "invoices"
            referencedColumns: ["id"]
          },
        ]
      }
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
      enquiries: {
        Row: {
          address: string | null
          client_id: string | null
          created_at: string
          email: string | null
          id: string
          message: string | null
          name: string
          phone: string | null
          source: string
          status: string
          updated_at: string
          user_id: string
        }
        Insert: {
          address?: string | null
          client_id?: string | null
          created_at?: string
          email?: string | null
          id?: string
          message?: string | null
          name: string
          phone?: string | null
          source?: string
          status?: string
          updated_at?: string
          user_id: string
        }
        Update: {
          address?: string | null
          client_id?: string | null
          created_at?: string
          email?: string | null
          id?: string
          message?: string | null
          name?: string
          phone?: string | null
          source?: string
          status?: string
          updated_at?: string
          user_id?: string
        }
        Relationships: [
          {
            foreignKeyName: "enquiries_client_id_fkey"
            columns: ["client_id"]
            isOneToOne: false
            referencedRelation: "clients"
            referencedColumns: ["id"]
          },
        ]
      }
      invoice_email_events: {
        Row: {
          created_at: string
          detail: string | null
          id: string
          invoice_id: string
          provider_message_id: string | null
          recipient: string | null
          status: Database["public"]["Enums"]["email_event_status"]
          user_id: string
        }
        Insert: {
          created_at?: string
          detail?: string | null
          id?: string
          invoice_id: string
          provider_message_id?: string | null
          recipient?: string | null
          status: Database["public"]["Enums"]["email_event_status"]
          user_id: string
        }
        Update: {
          created_at?: string
          detail?: string | null
          id?: string
          invoice_id?: string
          provider_message_id?: string | null
          recipient?: string | null
          status?: Database["public"]["Enums"]["email_event_status"]
          user_id?: string
        }
        Relationships: [
          {
            foreignKeyName: "invoice_email_events_invoice_id_fkey"
            columns: ["invoice_id"]
            isOneToOne: false
            referencedRelation: "invoices"
            referencedColumns: ["id"]
          },
        ]
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
      job_photos: {
        Row: {
          caption: string | null
          created_at: string
          id: string
          job_id: string
          storage_path: string
          task_id: string | null
          user_id: string
        }
        Insert: {
          caption?: string | null
          created_at?: string
          id?: string
          job_id: string
          storage_path: string
          task_id?: string | null
          user_id: string
        }
        Update: {
          caption?: string | null
          created_at?: string
          id?: string
          job_id?: string
          storage_path?: string
          task_id?: string | null
          user_id?: string
        }
        Relationships: [
          {
            foreignKeyName: "job_photos_job_id_fkey"
            columns: ["job_id"]
            isOneToOne: false
            referencedRelation: "scheduled_jobs"
            referencedColumns: ["id"]
          },
          {
            foreignKeyName: "job_photos_task_id_fkey"
            columns: ["task_id"]
            isOneToOne: false
            referencedRelation: "job_tasks"
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
      job_tasks: {
        Row: {
          created_at: string
          done: boolean
          id: string
          job_id: string
          position: number
          title: string
          user_id: string
        }
        Insert: {
          created_at?: string
          done?: boolean
          id?: string
          job_id: string
          position?: number
          title: string
          user_id: string
        }
        Update: {
          created_at?: string
          done?: boolean
          id?: string
          job_id?: string
          position?: number
          title?: string
          user_id?: string
        }
        Relationships: [
          {
            foreignKeyName: "job_tasks_job_id_fkey"
            columns: ["job_id"]
            isOneToOne: false
            referencedRelation: "scheduled_jobs"
            referencedColumns: ["id"]
          },
        ]
      }
      quote_items: {
        Row: {
          amount: number
          created_at: string
          description: string
          id: string
          quantity: number
          quote_id: string
          sort_order: number
          unit_price: number
          user_id: string
        }
        Insert: {
          amount?: number
          created_at?: string
          description: string
          id?: string
          quantity?: number
          quote_id: string
          sort_order?: number
          unit_price?: number
          user_id: string
        }
        Update: {
          amount?: number
          created_at?: string
          description?: string
          id?: string
          quantity?: number
          quote_id?: string
          sort_order?: number
          unit_price?: number
          user_id?: string
        }
        Relationships: [
          {
            foreignKeyName: "quote_items_quote_id_fkey"
            columns: ["quote_id"]
            isOneToOne: false
            referencedRelation: "quotes"
            referencedColumns: ["id"]
          },
        ]
      }
      quotes: {
        Row: {
          client_id: string | null
          contact_address: string | null
          contact_email: string | null
          contact_name: string
          contact_phone: string | null
          created_at: string
          decided_at: string | null
          decline_reason: string | null
          enquiry_id: string | null
          id: string
          issue_date: string
          notes: string | null
          number: string
          public_key: string
          sent_at: string | null
          status: Database["public"]["Enums"]["quote_status"]
          subtotal: number
          terms: string | null
          total: number
          updated_at: string
          user_id: string
          valid_until: string | null
          vat_amount: number
        }
        Insert: {
          client_id?: string | null
          contact_address?: string | null
          contact_email?: string | null
          contact_name: string
          contact_phone?: string | null
          created_at?: string
          decided_at?: string | null
          decline_reason?: string | null
          enquiry_id?: string | null
          id?: string
          issue_date?: string
          notes?: string | null
          number: string
          public_key?: string
          sent_at?: string | null
          status?: Database["public"]["Enums"]["quote_status"]
          subtotal?: number
          terms?: string | null
          total?: number
          updated_at?: string
          user_id: string
          valid_until?: string | null
          vat_amount?: number
        }
        Update: {
          client_id?: string | null
          contact_address?: string | null
          contact_email?: string | null
          contact_name?: string
          contact_phone?: string | null
          created_at?: string
          decided_at?: string | null
          decline_reason?: string | null
          enquiry_id?: string | null
          id?: string
          issue_date?: string
          notes?: string | null
          number?: string
          public_key?: string
          sent_at?: string | null
          status?: Database["public"]["Enums"]["quote_status"]
          subtotal?: number
          terms?: string | null
          total?: number
          updated_at?: string
          user_id?: string
          valid_until?: string | null
          vat_amount?: number
        }
        Relationships: [
          {
            foreignKeyName: "quotes_client_id_fkey"
            columns: ["client_id"]
            isOneToOne: false
            referencedRelation: "clients"
            referencedColumns: ["id"]
          },
          {
            foreignKeyName: "quotes_enquiry_id_fkey"
            columns: ["enquiry_id"]
            isOneToOne: false
            referencedRelation: "enquiries"
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
          email_body_template: string
          email_subject_template: string
          enquiry_key: string | null
          invoice_counter: number
          invoice_prefix: string
          payment_terms: string | null
          phone: string | null
          quote_counter: number
          quote_prefix: string
          quote_terms: string | null
          quote_validity_days: number
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
          email_body_template?: string
          email_subject_template?: string
          enquiry_key?: string | null
          invoice_counter?: number
          invoice_prefix?: string
          payment_terms?: string | null
          phone?: string | null
          quote_counter?: number
          quote_prefix?: string
          quote_terms?: string | null
          quote_validity_days?: number
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
          email_body_template?: string
          email_subject_template?: string
          enquiry_key?: string | null
          invoice_counter?: number
          invoice_prefix?: string
          payment_terms?: string | null
          phone?: string | null
          quote_counter?: number
          quote_prefix?: string
          quote_terms?: string | null
          quote_validity_days?: number
          updated_at?: string
          user_id?: string
          vat_number?: string | null
          vat_rate?: number
          vat_registered?: boolean
        }
        Relationships: []
      }
      team_members: {
        Row: {
          created_at: string
          email: string
          id: string
          name: string | null
          owner_id: string
          role: Database["public"]["Enums"]["app_role"]
          updated_at: string
          user_id: string | null
        }
        Insert: {
          created_at?: string
          email: string
          id?: string
          name?: string | null
          owner_id: string
          role?: Database["public"]["Enums"]["app_role"]
          updated_at?: string
          user_id?: string | null
        }
        Update: {
          created_at?: string
          email?: string
          id?: string
          name?: string | null
          owner_id?: string
          role?: Database["public"]["Enums"]["app_role"]
          updated_at?: string
          user_id?: string | null
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
      claim_staff_invite: { Args: never; Returns: string }
      is_staff: { Args: { _uid: string }; Returns: boolean }
      my_workspace: {
        Args: never
        Returns: {
          owner_id: string
          role: Database["public"]["Enums"]["app_role"]
        }[]
      }
      next_invoice_number: { Args: { _user_id: string }; Returns: string }
      next_quote_number: { Args: { _user_id: string }; Returns: string }
      owner_for: { Args: { _uid: string }; Returns: string }
      staff_clients: {
        Args: never
        Returns: {
          address: string
          id: string
          name: string
          notes: string
        }[]
      }
      staff_complete_job: {
        Args: { _description?: string; _hours: number; _job_id: string }
        Returns: string
      }
    }
    Enums: {
      app_role: "owner" | "staff"
      billing_mode: "hourly" | "fixed"
      email_event_status: "sent" | "failed" | "skipped"
      invoice_status: "draft" | "sent" | "paid"
      job_status: "scheduled" | "done" | "missed" | "cancelled"
      quote_status: "draft" | "sent" | "accepted" | "declined" | "expired"
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
      app_role: ["owner", "staff"],
      billing_mode: ["hourly", "fixed"],
      email_event_status: ["sent", "failed", "skipped"],
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
