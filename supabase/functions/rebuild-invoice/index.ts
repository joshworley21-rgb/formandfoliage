// Rebuilds a draft invoice's lines from the visits it was made from.
//
// An invoice was frozen the moment it was created: no edit of any kind, so
// spotting a wrong figure a minute later meant deleting the invoice and making
// it again — which burns an invoice number and leaves a gap in the sequence
// for no reason other than a typo.
//
// A draft is a document nobody has seen. Correcting one is not rewriting
// history, it is finishing the job. So a draft can be rebuilt: fix the visit,
// rebuild, and the invoice keeps its number and its identity while its lines
// catch up.
//
// A sent invoice is a different thing entirely and is refused here. Someone
// has that document; this copy must go on matching it. The check is on the
// server rather than only in the app, because the app is not the only thing
// that can call this.
import { requireUser } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import {
  groupLines,
  invoiceTotals,
  linesForVisit,
  type VisitForInvoice,
} from "../_shared/invoice-lines.ts";

type VisitRow = VisitForInvoice & { billed: boolean; missed: boolean };

Deno.serve(
  serveJson(async (request) => {
    const { userId, supabase } = await requireUser(request);
    const { invoiceId } = (await request.json()) as { invoiceId?: string };
    if (!invoiceId) throw new Error("An invoice is required");

    const { data: invoice, error: invoiceError } = await supabase
      .from("invoices")
      .select("*")
      .eq("id", invoiceId)
      .single();
    if (invoiceError || !invoice) throw new Error("Invoice not found");
    if (invoice.status !== "draft") {
      throw new Error(
        `${invoice.number} has already been sent, so its lines cannot be changed. ` +
          "Anything extra belongs on a new invoice.",
      );
    }

    const { data: client, error: clientError } = await supabase
      .from("clients")
      .select("*")
      .eq("id", invoice.client_id)
      .single();
    if (clientError || !client) throw new Error("Client not found");

    const { data: settings } = await supabase
      .from("settings")
      .select("*")
      .eq("user_id", userId)
      .maybeSingle();

    const { data: visitRows, error: visitsError } = await supabase
      .from("visits")
      .select(
        "id, visit_date, hours, description, materials_cost, materials_note, amount, billed, missed",
      )
      .eq("invoice_id", invoiceId);
    if (visitsError) throw visitsError;

    const all = (visitRows ?? []) as VisitRow[];
    // A visit marked missed since the invoice was drafted is not work to bill.
    // It is let go of rather than left attached with no line to its name, which
    // would be a visit that is neither billable nor billed.
    const billable = all.filter((v) => !v.missed);
    const released = all.filter((v) => v.missed);

    // Anything not derived from a visit — a retainer line — was typed rather
    // than computed, so rebuilding must not throw it away.
    const { data: extraRows, error: extrasError } = await supabase
      .from("invoice_items")
      .select("amount")
      .eq("invoice_id", invoiceId)
      .is("visit_id", null);
    if (extrasError) throw extrasError;
    const extras = extraRows ?? [];

    const lines = groupLines(
      billable.flatMap((visit) =>
        linesForVisit(visit, {
          fixedPrice: client.billing_mode === "fixed",
          hourlyRate: Number(client.hourly_rate ?? 0),
        }),
      ),
    );

    if (lines.length === 0 && extras.length === 0) {
      throw new Error("Rebuilding would leave this invoice with nothing on it. Delete it instead.");
    }

    const vatRate = settings?.vat_registered ? Number(settings.vat_rate ?? 0) : 0;
    const { subtotal, vatAmount, total } = invoiceTotals(
      [...lines.map((l) => l.amount), ...extras.map((e) => Number(e.amount))],
      vatRate,
    );

    // Only the visit-derived lines are replaced; the extras above are left be.
    const { error: deleteError } = await supabase
      .from("invoice_items")
      .delete()
      .eq("invoice_id", invoiceId)
      .not("visit_id", "is", null);
    if (deleteError) throw deleteError;

    if (lines.length > 0) {
      const { error } = await supabase
        .from("invoice_items")
        .insert(lines.map((line) => ({ user_id: userId, invoice_id: invoiceId, ...line })));
      if (error) throw error;
    }

    if (released.length > 0) {
      const { error } = await supabase
        .from("visits")
        .update({ billed: false, invoice_id: null })
        .in(
          "id",
          released.map((v) => v.id),
        );
      if (error) throw error;
    }

    const dates = billable.map((v) => v.visit_date).sort();
    const { error: updateError } = await supabase
      .from("invoices")
      .update({
        subtotal,
        vat_amount: vatAmount,
        total,
        period_start: dates[0] ?? null,
        period_end: dates[dates.length - 1] ?? null,
      })
      .eq("id", invoiceId);
    if (updateError) throw updateError;

    return json({
      number: invoice.number as string,
      lines: lines.length + extras.length,
      released: released.length,
      total,
    });
  }),
);
