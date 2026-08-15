import { createServerFn } from "@tanstack/react-start";
import { requireSupabaseAuth } from "@/integrations/supabase/auth-middleware";

type CreateInvoiceInput = {
  clientId: string;
  visitIds: string[];
  retainerDescription?: string | null;
  retainerAmount?: number;
  notes?: string | null;
  dueInDays?: number;
};

export const createInvoice = createServerFn({ method: "POST" })
  .middleware([requireSupabaseAuth])
  .inputValidator((input: CreateInvoiceInput) => {
    if (!input?.clientId) throw new Error("A client is required");
    return {
      clientId: input.clientId,
      visitIds: Array.isArray(input.visitIds) ? input.visitIds : [],
      retainerDescription: input.retainerDescription ?? null,
      retainerAmount: Number(input.retainerAmount ?? 0) || 0,
      notes: input.notes ?? null,
      dueInDays: Number(input.dueInDays ?? 14) || 14,
    };
  })
  .handler(async ({ data, context }) => {
    const { supabase, userId } = context;

    const { data: client, error: clientError } = await supabase
      .from("clients")
      .select("*")
      .eq("id", data.clientId)
      .single();
    if (clientError || !client) throw new Error("Client not found");

    const { data: settings } = await supabase
      .from("settings")
      .select("*")
      .eq("user_id", userId)
      .maybeSingle();

    let visits: Array<{
      id: string;
      visit_date: string;
      hours: number;
      description: string | null;
      materials_cost: number;
      amount: number;
    }> = [];

    if (data.visitIds.length > 0) {
      const { data: rows, error } = await supabase
        .from("visits")
        .select("id, visit_date, hours, description, materials_cost, amount, billed")
        .in("id", data.visitIds)
        .eq("client_id", data.clientId);
      if (error) throw error;
      visits = (rows ?? []).filter((v) => !v.billed);
    }

    if (visits.length === 0 && data.retainerAmount <= 0) {
      throw new Error("Nothing to invoice — select visits or add a retainer amount");
    }

    const visitTotal = visits.reduce((sum, v) => sum + Number(v.amount), 0);
    const subtotal = Math.round((visitTotal + data.retainerAmount) * 100) / 100;
    const vatRate = settings?.vat_registered ? Number(settings.vat_rate ?? 0) : 0;
    const vatAmount = Math.round(subtotal * (vatRate / 100) * 100) / 100;
    const total = Math.round((subtotal + vatAmount) * 100) / 100;

    const { supabaseAdmin } = await import("@/integrations/supabase/client.server");
    const { data: number, error: numberError } = await supabaseAdmin.rpc("next_invoice_number", {
      _user_id: userId,
    });
    if (numberError) throw numberError;

    const dates = visits.map((v) => v.visit_date).sort();
    const issueDate = new Date();
    const dueDate = new Date(issueDate.getTime() + data.dueInDays * 86400000);

    const { data: invoice, error: invoiceError } = await supabase
      .from("invoices")
      .insert({
        user_id: userId,
        client_id: data.clientId,
        number: number as string,
        issue_date: issueDate.toISOString().slice(0, 10),
        due_date: dueDate.toISOString().slice(0, 10),
        period_start: dates[0] ?? null,
        period_end: dates[dates.length - 1] ?? null,
        subtotal,
        vat_amount: vatAmount,
        total,
        notes: data.notes,
      })
      .select("*")
      .single();
    if (invoiceError) throw invoiceError;

    const items = visits.map((v) => ({
      user_id: userId,
      invoice_id: invoice.id,
      visit_id: v.id,
      item_date: v.visit_date,
      description:
        v.description?.trim() ||
        (client.billing_mode === "fixed" ? "Garden maintenance visit" : "Garden maintenance"),
      quantity: client.billing_mode === "fixed" ? 1 : Number(v.hours),
      unit_price:
        client.billing_mode === "fixed" ? Number(v.amount) : Number(client.hourly_rate ?? 0),
      amount: Number(v.amount),
    }));

    if (data.retainerAmount > 0) {
      items.push({
        user_id: userId,
        invoice_id: invoice.id,
        visit_id: null as unknown as string,
        item_date: issueDate.toISOString().slice(0, 10),
        description: data.retainerDescription || "Maintenance retainer",
        quantity: 1,
        unit_price: data.retainerAmount,
        amount: data.retainerAmount,
      });
    }

    if (items.length > 0) {
      const { error } = await supabase.from("invoice_items").insert(items);
      if (error) throw error;
    }

    if (visits.length > 0) {
      const { error } = await supabase
        .from("visits")
        .update({ billed: true, invoice_id: invoice.id })
        .in(
          "id",
          visits.map((v) => v.id),
        );
      if (error) throw error;
    }

    return { invoiceId: invoice.id as string, number: invoice.number as string, total };
  });

type SendInvoiceInput = {
  invoiceId: string;
  pdfBase64: string;
  message?: string | null;
};

export const sendInvoiceEmail = createServerFn({ method: "POST" })
  .middleware([requireSupabaseAuth])
  .inputValidator((input: SendInvoiceInput) => {
    if (!input?.invoiceId) throw new Error("invoiceId is required");
    if (!input?.pdfBase64) throw new Error("Invoice PDF is missing");
    return {
      invoiceId: input.invoiceId,
      pdfBase64: input.pdfBase64,
      message: input.message ?? null,
    };
  })
  .handler(async ({ data, context }) => {
    const { supabase, userId } = context;

    const { data: invoice, error } = await supabase
      .from("invoices")
      .select("*, clients(name, email)")
      .eq("id", data.invoiceId)
      .single();
    if (error || !invoice) throw new Error("Invoice not found");

    const client = invoice.clients as unknown as { name: string; email: string | null } | null;

    const logEvent = async (
      status: "sent" | "failed" | "skipped",
      detail: string | null,
      providerMessageId?: string | null,
    ) => {
      await supabase.from("invoice_email_events").insert({
        user_id: userId,
        invoice_id: data.invoiceId,
        status,
        recipient: client?.email ?? null,
        provider_message_id: providerMessageId ?? null,
        detail,
      });
    };

    if (!client?.email) {
      await logEvent("failed", "This client has no email address saved");
      throw new Error("This client has no email address saved");
    }

    const { data: settings } = await supabase
      .from("settings")
      .select("*")
      .eq("user_id", userId)
      .maybeSingle();

    const businessName = settings?.business_name || "JW Garden Services";
    const path = `${userId}/${invoice.id}.pdf`;
    const bytes = Uint8Array.from(atob(data.pdfBase64), (c) => c.charCodeAt(0));

    await supabase.storage.from("invoices").upload(path, bytes, {
      contentType: "application/pdf",
      upsert: true,
    });

    const lovableKey = process.env["LOVABLE_API_KEY"];
    const resendKey = process.env["RESEND_API_KEY"];
    if (!lovableKey || !resendKey) {
      await logEvent("skipped", "Email is not connected — Resend is not set up yet.");
      return {
        sent: false,
        reason:
          "Email is not connected yet — connect Resend in Settings to send invoices by email.",
      };
    }

    const fromEmail = settings?.email || "onboarding@resend.dev";

    const escapeHtml = (value: string) =>
      value
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");

    const tokens: Record<string, string> = {
      client_name: client.name ?? "",
      business_name: businessName,
      invoice_number: invoice.number ?? "",
      total: `£${Number(invoice.total).toFixed(2)}`,
      due_date: invoice.due_date ?? "",
      due_clause: invoice.due_date ? ` by ${invoice.due_date}` : "",
      issue_date: invoice.issue_date ?? "",
      payment_terms: settings?.payment_terms ?? "",
      bank_details: settings?.bank_details ?? "",
    };

    const fill = (template: string) =>
      template.replace(/\{\{\s*(\w+)\s*\}\}/g, (_m, key: string) => tokens[key] ?? "");

    const subjectTemplate =
      settings?.email_subject_template || "Invoice {{invoice_number}} from {{business_name}}";
    const bodyTemplate =
      data.message ||
      settings?.email_body_template ||
      "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}";

    const subject = fill(subjectTemplate);
    const htmlBody = `<div style="font-family:Arial,sans-serif;font-size:15px;color:#243">
      ${escapeHtml(fill(bodyTemplate))
        .split(/\n{2,}/)
        .map((para) => `<p>${para.replace(/\n/g, "<br/>")}</p>`)
        .join("\n")}
    </div>`;

    const response = await fetch("https://connector-gateway.lovable.dev/resend/emails", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${lovableKey}`,
        "X-Connection-Api-Key": resendKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: `${businessName} <${fromEmail}>`,
        to: client.email,
        subject,
        html: htmlBody,
        attachments: [
          {
            filename: `${invoice.number}.pdf`,
            content: data.pdfBase64,
            contentType: "application/pdf",
          },
        ],
      }),
    });

    if (!response.ok) {
      const detail = await response.text();
      console.error("Resend send error", response.status, detail);
      await logEvent("failed", `${response.status}: ${detail}`.slice(0, 500));
      throw new Error(`Resend could not send the email. ${response.status}: ${detail}`);
    }

    let providerMessageId: string | null = null;
    try {
      const payload = (await response.json()) as { id?: string };
      providerMessageId = payload?.id ?? null;
    } catch {
      providerMessageId = null;
    }

    await supabase
      .from("invoices")
      .update({ status: "sent", sent_at: new Date().toISOString(), pdf_path: path })
      .eq("id", invoice.id);

    await logEvent("sent", `Delivered to ${client.email} via Resend`, providerMessageId);

    return { sent: true, to: client.email };
  });

export const getResendStatus = createServerFn({ method: "GET" })
  .middleware([requireSupabaseAuth])
  .handler(async () => {
    const lovableKey = process.env["LOVABLE_API_KEY"];
    const resendKey = process.env["RESEND_API_KEY"];
    if (!lovableKey || !resendKey) return { connected: false as const };

    const response = await fetch("https://connector-gateway.lovable.dev/api/v1/verify_credentials", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${lovableKey}`,
        "X-Connection-Api-Key": resendKey,
      },
    });
    if (!response.ok) {
      const detail = await response.text();
      console.error("Resend verify error", response.status, detail);
      return { connected: false as const };
    }

    const result = (await response.json()) as { outcome?: string };
    if (result.outcome === "failed") {
      return { connected: false as const };
    }
    return { connected: true as const };
  });

type SendTestEmailInput = {
  to: string;
  subjectTemplate?: string | null;
  bodyTemplate?: string | null;
};

export const sendTestEmail = createServerFn({ method: "POST" })
  .middleware([requireSupabaseAuth])
  .inputValidator((input: SendTestEmailInput) => {
    const to = (input?.to ?? "").trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(to)) throw new Error("Enter a valid email address");
    return {
      to,
      subjectTemplate: input.subjectTemplate?.trim() || null,
      bodyTemplate: input.bodyTemplate?.trim() || null,
    };
  })
  .handler(async ({ data, context }) => {
    const { supabase, userId } = context;

    const lovableKey = process.env["LOVABLE_API_KEY"];
    const resendKey = process.env["RESEND_API_KEY"];
    if (!lovableKey || !resendKey) {
      return { sent: false as const, reason: "Resend is not connected yet." };
    }

    const { data: settings } = await supabase
      .from("settings")
      .select("*")
      .eq("user_id", userId)
      .maybeSingle();

    const businessName = settings?.business_name || "JW Garden Services";
    const fromEmail = settings?.email || "onboarding@resend.dev";
    const today = new Date();
    const due = new Date(today.getTime() + 14 * 86400000).toISOString().slice(0, 10);

    const tokens: Record<string, string> = {
      client_name: "Sample Client",
      business_name: businessName,
      invoice_number: `${settings?.invoice_prefix ?? "INV-"}0001`,
      total: "£120.00",
      due_date: due,
      due_clause: ` by ${due}`,
      issue_date: today.toISOString().slice(0, 10),
      payment_terms: settings?.payment_terms ?? "",
      bank_details: settings?.bank_details ?? "",
    };

    const fill = (template: string) =>
      template.replace(/\{\{\s*(\w+)\s*\}\}/g, (_m, key: string) => tokens[key] ?? "");

    const escapeHtml = (value: string) =>
      value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

    const subject = `[Test] ${fill(
      data.subjectTemplate ||
        settings?.email_subject_template ||
        "Invoice {{invoice_number}} from {{business_name}}",
    )}`;
    const body = fill(
      data.bodyTemplate ||
        settings?.email_body_template ||
        "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}",
    );

    const html = `<div style="font-family:Arial,sans-serif;font-size:15px;color:#243">
      ${escapeHtml(body)
        .split(/\n{2,}/)
        .map((para) => `<p>${para.replace(/\n/g, "<br/>")}</p>`)
        .join("\n")}
      <hr style="margin-top:24px;border:none;border-top:1px solid #ddd"/>
      <p style="font-size:12px;color:#777">This is a test email sent from your CRM using sample invoice details. No PDF is attached.</p>
    </div>`;

    const response = await fetch("https://connector-gateway.lovable.dev/resend/emails", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${lovableKey}`,
        "X-Connection-Api-Key": resendKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        from: `${businessName} <${fromEmail}>`,
        to: data.to,
        subject,
        html,
      }),
    });

    if (!response.ok) {
      const detail = await response.text();
      console.error("Resend test send error", response.status, detail);
      return {
        sent: false as const,
        reason: `Resend rejected the test email (${response.status}): ${detail}`.slice(0, 500),
      };
    }

    return { sent: true as const, to: data.to, subject };
  });
