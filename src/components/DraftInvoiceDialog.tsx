// Correcting a draft invoice before anyone sees it.
//
// An invoice used to be frozen the moment it was created, so a figure spotted
// wrong a minute later meant deleting it and starting again — which burns an
// invoice number and leaves a gap in the sequence over a typo.
//
// A draft is a document nobody has received. Two things can be put right here:
// the wording and the due date, which are typed and so can simply be retyped;
// and the lines, which are not typed at all — they are derived from the visits,
// so the way to correct them is to correct the visit and rebuild.
//
// Nothing in here is offered once an invoice has been sent. That copy has to go
// on matching the one the customer is holding.
import { RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useUpdateInvoice } from "@/hooks/use-crm";
import { money, type Invoice } from "@/lib/crm";
import { rebuildInvoice } from "@/lib/invoicing";

export function DraftInvoiceDialog({
  invoice,
  onClose,
}: {
  invoice: Invoice | null;
  onClose: () => void;
}) {
  const updateInvoice = useUpdateInvoice();
  const [dueDate, setDueDate] = useState("");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!invoice) return;
    setDueDate(invoice.due_date ?? "");
    setNotes(invoice.notes ?? "");
  }, [invoice]);

  async function saveDetails() {
    if (!invoice) return;
    setBusy(true);
    try {
      await updateInvoice.mutateAsync({
        id: invoice.id,
        patch: { due_date: dueDate || null, notes: notes.trim() || null },
      });
      toast.success("Invoice updated");
      onClose();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not update the invoice");
    } finally {
      setBusy(false);
    }
  }

  async function rebuild() {
    if (!invoice) return;
    setBusy(true);
    try {
      const result = await rebuildInvoice({ data: { invoiceId: invoice.id } });
      toast.success(
        `${result.number} rebuilt — ${result.lines} line${result.lines === 1 ? "" : "s"}, ${money(
          result.total,
        )}${result.released ? `. ${result.released} missed visit released` : ""}`,
      );
      onClose();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not rebuild the invoice");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={Boolean(invoice)} onOpenChange={(open) => (open ? null : onClose())}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Edit {invoice?.number}</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <p className="rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            This invoice has not been sent, so it can still be changed. Once it goes out it is fixed
            at whatever the customer received.
          </p>

          <div className="space-y-1.5">
            <Label htmlFor="idue">Due date</Label>
            <Input
              id="idue"
              type="date"
              value={dueDate}
              onChange={(e) => setDueDate(e.target.value)}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="inotes">Notes on the invoice</Label>
            <Textarea
              id="inotes"
              rows={3}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Thanks — payment within 14 days please."
            />
          </div>

          <Button className="h-11 w-full" disabled={busy} onClick={saveDetails}>
            Save changes
          </Button>

          <div className="space-y-2 border-t border-border pt-4">
            <Label>The lines</Label>
            {/*
              The lines are not editable here on purpose: they are worked out
              from the visits, so a figure typed over the top would be a number
              agreeing with nothing. Correct the visit; the invoice follows.
            */}
            <p className="text-xs text-muted-foreground">
              The lines come from the visits on this invoice — currently{" "}
              <strong>{money(Number(invoice?.total ?? 0))}</strong>. Change a visit's hours or
              materials on the Visits screen, then rebuild and the invoice catches up. Its number
              and date stay as they are.
            </p>
            <Button variant="outline" className="h-11 w-full" disabled={busy} onClick={rebuild}>
              <RefreshCw className="size-4" /> Rebuild from visits
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
