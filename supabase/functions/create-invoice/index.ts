// Builds an invoice from selected visits and/or a retainer amount.
//
// Ported from the createInvoice server function. Runs server-side because
// next_invoice_number() is granted to service_role only — the client must not be
// able to burn or skip invoice numbers. Everything else is done through the
// caller's own client so RLS still applies.
import { requireUser, adminClient } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { groupLines, linesForVisit } from "../_shared/invoice-lines.ts";

type CreateInvoiceInput = {
  clientId?: string;
  visitIds?: string[];
  retainerDescription?: string | null;
  retainerAmount?: number;
  notes?: string | null;
  dueInDays?: number;
  dueDate?: string | null;
};

type VisitRow = {
  id: string;
  visit_date: string;
  hours: number;
  description: string | null;
  materials_cost: number;
  materials_note: string | null;
  amount: number;
  billed: boolean;
  missed: boolean;
};

function validate(input: CreateInvoiceInput) {
  if (!input?.clientId) throw new Error("A client is required");
  const dueDate = input.dueDate?.trim() ? input.dueDate.trim() : null;
  if (dueDate && !/^\d{4}-\d{2}-\d{2}$/.test(dueDate)) throw new Error("Due date is invalid");
  return {
    clientId: input.clientId,
    visitIds: Array.isArray(input.visitIds) ? input.visitIds : [],
    retainerDescription: input.retainerDescription ?? null,
    retainerAmount: Number(input.retainerAmount ?? 0) || 0,
    notes: input.notes ?? null,
    dueInDays: Number(input.dueInDays ?? 14) || 14,
    dueDate,
  };
}

Deno.serve(
  serveJson(async (request) => {
    const { userId, supabase } = await requireUser(request);
    const data = validate((await request.json()) as CreateInvoiceInput);

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

    let visits: VisitRow[] = [];
    if (data.visitIds.length > 0) {
      const { data: rows, error } = await supabase
        .from("visits")
        .select(
          "id, visit_date, hours, description, materials_cost, materials_note, amount, billed, missed",
        )
        .in("id", data.visitIds)
        .eq("client_id", data.clientId);
      if (error) throw error;
      // Missed visits are refused here too, not just hidden in the picker:
      // the app is not the only thing that can call this.
      visits = ((rows ?? []) as VisitRow[]).filter((v) => !v.billed && !v.missed);
    }

    if (visits.length === 0 && data.retainerAmount <= 0) {
      throw new Error("Nothing to invoice — select visits or add a retainer amount");
    }

    const visitTotal = visits.reduce((sum, v) => sum + Number(v.amount), 0);
    const subtotal = Math.round((visitTotal + data.retainerAmount) * 100) / 100;
    const vatRate = settings?.vat_registered ? Number(settings.vat_rate ?? 0) : 0;
    const vatAmount = Math.round(subtotal * (vatRate / 100) * 100) / 100;
    const total = Math.round((subtotal + vatAmount) * 100) / 100;

    const { data: number, error: numberError } = await adminClient().rpc("next_invoice_number", {
      _user_id: userId,
    });
    if (numberError) throw numberError;

    const dates = visits.map((v) => v.visit_date).sort();
    const issueDate = new Date();
    const dueDate =
      data.dueDate ??
      new Date(issueDate.getTime() + data.dueInDays * 86400000).toISOString().slice(0, 10);

    const { data: invoice, error: invoiceError } = await supabase
      .from("invoices")
      .insert({
        user_id: userId,
        client_id: data.clientId,
        number: number as string,
        issue_date: issueDate.toISOString().slice(0, 10),
        due_date: dueDate,
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

    // Materials get a line of their own rather than being folded into the
    // labour one, so both lines multiply out and the materials can be named.
    // The two still sum to the visit, so subtotal, VAT and total are untouched.
    // The arithmetic lives in _shared/invoice-lines, where it is tested.
    const items = groupLines(
      visits.flatMap((v) =>
        linesForVisit(v, {
          fixedPrice: client.billing_mode === "fixed",
          hourlyRate: Number(client.hourly_rate ?? 0),
        }),
      ),
    ).map((line) => ({ user_id: userId, invoice_id: invoice.id, ...line }));

    if (data.retainerAmount > 0) {
      items.push({
        user_id: userId,
        invoice_id: invoice.id,
        visit_id: null,
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

    return json({ invoiceId: invoice.id as string, number: invoice.number as string, total });
  }),
);
