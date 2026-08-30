// Changing what the email says, on the way out.
//
// Sending was one tap and one tap only: the saved template, filled in, gone.
// Which is right for the ordinary invoice and wrong for the one that needs a
// line on it — "thanks for squeezing in the hedge", "no rush on this one",
// "sorry it is late". Writing that meant editing the template in Settings,
// sending, and putting the template back.
//
// So the message is editable on the way out instead. The box opens with the
// real email in it — the actual subject, the actual wording, tokens already
// filled in — because an editor that opens on {{client_name}} is a template
// editor, and there is already one of those in Settings.
//
// What is typed here applies to this send and nothing else. The saved template
// is untouched, which is the whole point of editing it here rather than there.
import { Mail, RotateCcw } from "lucide-react";
import { useState } from "react";
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
import type { Client, Invoice, Settings } from "@/lib/crm";
import { draftFromTemplates, type InvoiceEmailDraft } from "@/lib/email-template";

export type SendInvoiceDraft = InvoiceEmailDraft;

const draftFor = (invoice: Invoice, client: Client, settings: Settings | null) =>
  draftFromTemplates({ invoice, clientName: client.name, settings });

export function SendInvoiceDialog({
  invoice,
  client,
  settings,
  busy,
  onSend,
  onClose,
}: {
  invoice: Invoice | null;
  client: Client | null;
  settings: Settings | null;
  busy: boolean;
  onSend: (draft: SendInvoiceDraft) => void;
  onClose: () => void;
}) {
  // Filled once, when this dialog is mounted for an invoice. The caller keys it
  // on the invoice id, so opening a different invoice mounts a fresh one and
  // yesterday's wording is never sitting in the box for today's customer.
  //
  // Deliberately not a useEffect over the props: settings and client come from
  // react-query, which hands back new objects on every background refetch. An
  // effect watching them would re-fill the box — deleting a note mid-sentence
  // because the app happened to refresh while it was being written.
  const initial = invoice && client ? draftFor(invoice, client, settings) : null;
  const [subject, setSubject] = useState(initial?.subject ?? "");
  const [message, setMessage] = useState(initial?.message ?? "");

  function reset() {
    if (!invoice || !client) return;
    const draft = draftFor(invoice, client, settings);
    setSubject(draft.subject);
    setMessage(draft.message);
  }

  const original = invoice && client ? draftFor(invoice, client, settings) : null;
  const edited = Boolean(
    original && (subject !== original.subject || message !== original.message),
  );
  // An empty subject or body is a mistake rather than a choice: the template
  // that would have filled them is exactly what this box replaced.
  const sendable = Boolean(client?.email) && subject.trim() !== "" && message.trim() !== "";

  return (
    <Dialog open={Boolean(invoice && client)} onOpenChange={(open) => (open ? null : onClose())}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Send {invoice?.number}</DialogTitle>
          <DialogDescription>
            {client?.email
              ? `To ${client.name} at ${client.email}`
              : `${client?.name ?? "This client"} has no email address saved`}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="esubject">Subject</Label>
            <Input id="esubject" value={subject} onChange={(e) => setSubject(e.target.value)} />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="emessage">Message</Label>
            <Textarea
              id="emessage"
              rows={9}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              {invoice?.number}.pdf is attached either way. This wording is for this send only —
              your saved template in Settings does not change.
            </p>
          </div>

          {edited ? (
            <Button variant="ghost" size="sm" onClick={reset}>
              <RotateCcw className="size-4" /> Back to my usual wording
            </Button>
          ) : null}
        </div>

        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            className="h-11"
            disabled={busy || !sendable}
            onClick={() => onSend({ subject: subject.trim(), message })}
          >
            <Mail className="size-4" /> Send
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
