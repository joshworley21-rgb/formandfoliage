import { useEffect, useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useSettings } from "@/hooks/use-crm";
import { useQuoteItems, useSaveQuote, type SaveQuoteInput } from "@/hooks/use-quotes";
import { money } from "@/lib/crm";
import { lineAmount, quoteTotals, type QuoteItemDraft } from "@/lib/quotes";

const BLANK_LINE: QuoteItemDraft = { description: "", quantity: 1, unit_price: 0 };

export function QuoteDialog({
  open,
  draft,
  onClose,
  onSaved,
}: {
  open: boolean;
  draft: SaveQuoteInput | null;
  onClose: () => void;
  onSaved: (quoteId: string) => void;
}) {
  const save = useSaveQuote();
  const { data: settings } = useSettings();
  // An existing quote arrives without its lines; they are fetched here so the
  // list can be edited as one block.
  const { data: savedItems } = useQuoteItems(draft?.id);

  const [form, setForm] = useState<SaveQuoteInput>(draft ?? { contactName: "", items: [] });
  const [items, setItems] = useState<QuoteItemDraft[]>([BLANK_LINE]);

  useEffect(() => {
    if (!draft) return;
    setForm(draft);
    setItems(draft.items.length > 0 ? draft.items : [BLANK_LINE]);
  }, [draft]);

  useEffect(() => {
    if (!draft?.id || !savedItems) return;
    setItems(
      savedItems.length > 0
        ? savedItems.map((item) => ({
            id: item.id,
            description: item.description,
            quantity: Number(item.quantity),
            unit_price: Number(item.unit_price),
          }))
        : [BLANK_LINE],
    );
  }, [draft?.id, savedItems]);

  const totals = quoteTotals(items, {
    vatRegistered: settings?.vat_registered,
    vatRate: settings?.vat_rate,
  });

  function updateLine(index: number, patch: Partial<QuoteItemDraft>) {
    setItems((current) => current.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  }

  async function submit() {
    if (!form.contactName.trim()) {
      toast.error("Who is the quote for?");
      return;
    }
    const priced = items.filter((item) => item.description.trim() || lineAmount(item) !== 0);
    if (priced.length === 0) {
      toast.error("Add at least one line of work");
      return;
    }
    try {
      const id = await save.mutateAsync({ ...form, items: priced });
      toast.success(form.id ? "Quote updated" : "Quote created");
      onSaved(id);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not save the quote");
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? null : onClose())}>
      <DialogContent className="max-h-[92svh] w-[calc(100vw-1.5rem)] max-w-2xl overflow-y-auto p-4 sm:p-6">
        <DialogHeader>
          <DialogTitle>{form.id ? "Edit quote" : "New quote"}</DialogTitle>
          <DialogDescription>
            Price up the work. The customer gets a link to accept or decline it.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="quote-name">Name</Label>
              <Input
                id="quote-name"
                value={form.contactName}
                onChange={(e) => setForm({ ...form, contactName: e.target.value })}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="quote-email">Email</Label>
              <Input
                id="quote-email"
                type="email"
                value={form.contactEmail ?? ""}
                onChange={(e) => setForm({ ...form, contactEmail: e.target.value })}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="quote-phone">Phone</Label>
              <Input
                id="quote-phone"
                value={form.contactPhone ?? ""}
                onChange={(e) => setForm({ ...form, contactPhone: e.target.value })}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="quote-valid">Valid until</Label>
              <Input
                id="quote-valid"
                type="date"
                value={form.validUntil ?? ""}
                onChange={(e) => setForm({ ...form, validUntil: e.target.value })}
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="quote-address">Address</Label>
            <Input
              id="quote-address"
              value={form.contactAddress ?? ""}
              onChange={(e) => setForm({ ...form, contactAddress: e.target.value })}
            />
          </div>

          <div className="space-y-2">
            <Label>Work</Label>
            {items.map((item, index) => (
              <div key={item.id ?? index} className="grid grid-cols-12 items-end gap-2">
                <div className="col-span-12 sm:col-span-6">
                  <Input
                    aria-label="Description"
                    placeholder="Cut back hedge and clear waste"
                    value={item.description}
                    onChange={(e) => updateLine(index, { description: e.target.value })}
                  />
                </div>
                <div className="col-span-3 sm:col-span-2">
                  <Input
                    aria-label="Quantity"
                    type="number"
                    inputMode="decimal"
                    step="0.25"
                    value={item.quantity}
                    onChange={(e) => updateLine(index, { quantity: Number(e.target.value) })}
                  />
                </div>
                <div className="col-span-4 sm:col-span-2">
                  <Input
                    aria-label="Unit price"
                    type="number"
                    inputMode="decimal"
                    step="0.01"
                    value={item.unit_price}
                    onChange={(e) => updateLine(index, { unit_price: Number(e.target.value) })}
                  />
                </div>
                <div className="col-span-4 flex items-center justify-end gap-1 sm:col-span-2">
                  <span className="text-sm tabular-nums">{money(lineAmount(item))}</span>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label="Remove line"
                    disabled={items.length === 1}
                    onClick={() => setItems(items.filter((_, i) => i !== index))}
                  >
                    <Trash2 className="size-4" />
                  </Button>
                </div>
              </div>
            ))}
            <Button
              variant="outline"
              size="sm"
              onClick={() => setItems([...items, { ...BLANK_LINE }])}
            >
              <Plus className="mr-2 size-4" />
              Add line
            </Button>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="quote-notes">Notes for the customer</Label>
            <Textarea
              id="quote-notes"
              rows={3}
              value={form.notes ?? ""}
              onChange={(e) => setForm({ ...form, notes: e.target.value })}
            />
          </div>

          <div className="rounded-xl border border-border bg-muted/40 px-4 py-3 text-sm">
            <div className="flex justify-between">
              <span>Subtotal</span>
              <span className="tabular-nums">{money(totals.subtotal)}</span>
            </div>
            {settings?.vat_registered ? (
              <div className="flex justify-between">
                <span>VAT ({Number(settings.vat_rate)}%)</span>
                <span className="tabular-nums">{money(totals.vatAmount)}</span>
              </div>
            ) : null}
            <div className="mt-1 flex justify-between border-t border-border pt-1 font-semibold">
              <span>Total</span>
              <span className="tabular-nums">{money(totals.total)}</span>
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={save.isPending}>
            {save.isPending ? "Saving…" : form.id ? "Save changes" : "Create quote"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
