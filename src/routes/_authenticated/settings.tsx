import { createFileRoute } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
import { OwnerOnly } from "@/components/OwnerOnly";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useSaveSettings, useSettings } from "@/hooks/use-crm";
import { getResendStatus, sendTestEmail } from "@/lib/invoicing";
import { EmailTemplatePreview } from "@/components/EmailTemplatePreview";
import { useInviteMember, useRemoveMember, useTeam } from "@/hooks/use-workspace";

export const Route = createFileRoute("/_authenticated/settings")({
  head: () => ({
    meta: [
      { title: "Business settings — JW Garden Services CRM" },
      {
        name: "description",
        content: "Business details, VAT, invoice numbering and payment terms.",
      },
      { property: "og:title", content: "Business settings — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Business details, VAT, invoice numbering and payment terms.",
      },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <SettingsPage />
    </OwnerOnly>
  ),
});

function SettingsPage() {
  const { data: settings } = useSettings();
  const saveSettings = useSaveSettings();
  const senderFn = getResendStatus;
  const testEmailFn = sendTestEmail;
  const [testTo, setTestTo] = useState("");
  const [sendingTest, setSendingTest] = useState(false);
  const { data: sender } = useQuery({
    queryKey: ["resend-status"],
    queryFn: () => senderFn(),
  });

  const [form, setForm] = useState({
    business_name: "JW Garden Services",
    address: "24 Portland Close\nBierton\nHP22 7DG",
    phone: "",
    email: "invoices@jw-gardenservices.co.uk",
    reply_to_email: "info@jw-gardenservices.co.uk",
    public_site_url: "",
    vat_registered: false,
    vat_number: "",
    vat_rate: "20",
    invoice_prefix: "JW-",
    payment_terms: "Payment due within 14 days.",
    bank_details: "",
    email_subject_template: "Invoice {{invoice_number}} from {{business_name}}",
    email_body_template:
      "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}",
  });

  useEffect(() => {
    if (!settings) return;
    setForm({
      business_name: settings.business_name ?? "",
      address: settings.address ?? "",
      phone: settings.phone ?? "",
      email: settings.email ?? "",
      reply_to_email: settings.reply_to_email ?? "",
      public_site_url: settings.public_site_url ?? "",
      vat_registered: settings.vat_registered,
      vat_number: settings.vat_number ?? "",
      vat_rate: String(settings.vat_rate ?? 20),
      invoice_prefix: settings.invoice_prefix ?? "JW-",
      payment_terms: settings.payment_terms ?? "",
      bank_details: settings.bank_details ?? "",
      email_subject_template:
        settings.email_subject_template ?? "Invoice {{invoice_number}} from {{business_name}}",
      email_body_template: settings.email_body_template ?? "",
    });
  }, [settings]);

  const set = (key: keyof typeof form, value: string | boolean) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  return (
    <AppShell title="Settings" description="These details appear on every invoice you send.">
      <form
        className="grid gap-4 lg:grid-cols-2"
        onSubmit={async (e) => {
          e.preventDefault();
          await saveSettings.mutateAsync({
            business_name: form.business_name,
            address: form.address || null,
            phone: form.phone || null,
            email: form.email || null,
            reply_to_email: form.reply_to_email || null,
            public_site_url: form.public_site_url.trim().replace(/\/+$/, "") || null,
            vat_registered: form.vat_registered,
            vat_number: form.vat_number || null,
            vat_rate: Number(form.vat_rate) || 0,
            invoice_prefix: form.invoice_prefix || "JW-",
            payment_terms: form.payment_terms || null,
            bank_details: form.bank_details || null,
            email_subject_template:
              form.email_subject_template || "Invoice {{invoice_number}} from {{business_name}}",
            email_body_template: form.email_body_template,
          });
          toast.success("Settings saved");
        }}
      >
        <Card>
          <CardHeader>
            <CardTitle>Business details</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="bn">Business name</Label>
              <Input
                id="bn"
                value={form.business_name}
                onChange={(e) => set("business_name", e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="addr">Address</Label>
              <Textarea
                id="addr"
                rows={2}
                value={form.address}
                onChange={(e) => set("address", e.target.value)}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="phone">Phone</Label>
                <Input
                  id="phone"
                  value={form.phone}
                  onChange={(e) => set("phone", e.target.value)}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="email">Emails are sent from</Label>
                <Input
                  id="email"
                  type="email"
                  value={form.email}
                  onChange={(e) => set("email", e.target.value)}
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="replyTo">Replies go to</Label>
              <Input
                id="replyTo"
                type="email"
                value={form.reply_to_email}
                onChange={(e) => set("reply_to_email", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                The inbox you actually read. Customer replies, website enquiries and quote answers
                all land here, and it is the address printed on your invoices. The sending address
                above only has to be on a domain verified with Resend — it does not need a mailbox.
              </p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="site">Website address</Label>
              <Input
                id="site"
                type="url"
                inputMode="url"
                placeholder="https://jwgardenservices.co.uk"
                value={form.public_site_url}
                onChange={(e) => set("public_site_url", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                Where the enquiry form and quote links live. Needed for links you send to customers
                — the app on your phone has no address of its own to use.
              </p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Invoicing</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="prefix">Invoice number prefix</Label>
              <Input
                id="prefix"
                value={form.invoice_prefix}
                onChange={(e) => set("invoice_prefix", e.target.value)}
              />
            </div>
            <label className="flex items-center gap-2 text-sm font-medium">
              <Checkbox
                checked={form.vat_registered}
                onCheckedChange={(v) => set("vat_registered", Boolean(v))}
              />
              VAT registered
            </label>
            {form.vat_registered ? (
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="vatno">VAT number</Label>
                  <Input
                    id="vatno"
                    value={form.vat_number}
                    onChange={(e) => set("vat_number", e.target.value)}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="vatrate">VAT rate (%)</Label>
                  <Input
                    id="vatrate"
                    type="number"
                    step="0.5"
                    value={form.vat_rate}
                    onChange={(e) => set("vat_rate", e.target.value)}
                  />
                </div>
              </div>
            ) : null}
            <div className="space-y-1.5">
              <Label htmlFor="terms">Payment terms</Label>
              <Textarea
                id="terms"
                rows={2}
                value={form.payment_terms}
                onChange={(e) => set("payment_terms", e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="bank">Bank details</Label>
              <Textarea
                id="bank"
                rows={3}
                value={form.bank_details}
                onChange={(e) => set("bank_details", e.target.value)}
                placeholder={"Sort code 00-00-00\nAccount 12345678"}
              />
            </div>
          </CardContent>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Email sending</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {sender?.connected ? (
              <p className="text-sm">
                Resend is connected. Invoices will be sent as emails with the PDF attached.
              </p>
            ) : (
              <p className="text-sm text-muted-foreground">
                Resend is not connected. Connect your Resend account (and verify a sending domain)
                so you can email invoices directly from the CRM.
              </p>
            )}
            <div className="space-y-1.5">
              <Label htmlFor="testTo">Send a test email to</Label>
              <div className="flex flex-col gap-2 sm:flex-row">
                <Input
                  id="testTo"
                  type="email"
                  placeholder="you@example.com"
                  value={testTo}
                  onChange={(e) => setTestTo(e.target.value)}
                  className="sm:max-w-xs"
                />
                <Button
                  type="button"
                  variant="secondary"
                  disabled={sendingTest || !testTo.trim()}
                  onClick={async () => {
                    setSendingTest(true);
                    try {
                      const result = await testEmailFn({
                        data: {
                          to: testTo.trim(),
                          subjectTemplate: form.email_subject_template,
                          bodyTemplate: form.email_body_template,
                        },
                      });
                      if (result.sent) toast.success(`Test email sent to ${result.to}`);
                      else toast.error(result.reason ?? "Test email could not be sent");
                    } catch (err) {
                      toast.error(err instanceof Error ? err.message : "Test email failed");
                    } finally {
                      setSendingTest(false);
                    }
                  }}
                >
                  {sendingTest ? "Sending…" : "Send test email"}
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">
                Uses the template below with sample invoice details, so you can check how
                placeholders look before sending a real invoice.
              </p>
            </div>
          </CardContent>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Invoice email template</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="subj">Subject</Label>
              <Input
                id="subj"
                value={form.email_subject_template}
                onChange={(e) => set("email_subject_template", e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="body">Message</Label>
              <Textarea
                id="body"
                rows={8}
                value={form.email_body_template}
                onChange={(e) => set("email_body_template", e.target.value)}
              />
            </div>
            <p className="text-xs text-muted-foreground">
              Placeholders you can use: {"{{client_name}}"}, {"{{business_name}}"},{" "}
              {"{{invoice_number}}"}, {"{{total}}"}, {"{{due_date}}"}, {"{{due_clause}}"},{" "}
              {"{{issue_date}}"}, {"{{payment_terms}}"}, {"{{bank_details}}"}
            </p>
          </CardContent>
        </Card>

        <EmailTemplatePreview
          subjectTemplate={form.email_subject_template}
          bodyTemplate={form.email_body_template}
          businessName={form.business_name}
          fromEmail={form.email}
          replyToEmail={form.reply_to_email}
          paymentTerms={form.payment_terms}
          bankDetails={form.bank_details}
          invoicePrefix={form.invoice_prefix}
        />

        <div className="lg:col-span-2">
          <Button type="submit" disabled={saveSettings.isPending}>
            Save settings
          </Button>
        </div>
      </form>

      <div className="mt-6">
        <TeamCard />
      </div>
    </AppShell>
  );
}

function TeamCard() {
  const { data: team = [] } = useTeam();
  const invite = useInviteMember();
  const remove = useRemoveMember();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");

  return (
    <Card>
      <CardHeader>
        <CardTitle>Team access</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">
          Invite an employee by email. When they sign up with that email they can see the schedule,
          job sheets, photos and client addresses, and mark jobs complete. They can never see rates,
          visits, invoices, recurring billing or these settings.
        </p>
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!email.trim()) return;
            try {
              await invite.mutateAsync({ email, name });
              setEmail("");
              setName("");
              toast.success("Employee invited");
            } catch (error) {
              toast.error(error instanceof Error ? error.message : "Could not invite");
            }
          }}
        >
          <div className="min-w-40 flex-1 space-y-1.5">
            <Label htmlFor="staff-email">Work email</Label>
            <Input
              id="staff-email"
              type="email"
              value={email}
              placeholder="name@example.com"
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className="min-w-32 flex-1 space-y-1.5">
            <Label htmlFor="staff-name">Name (optional)</Label>
            <Input id="staff-name" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <Button type="submit" disabled={invite.isPending}>
            Invite
          </Button>
        </form>

        {team.length === 0 ? (
          <p className="text-sm text-muted-foreground">No employees yet.</p>
        ) : (
          <ul className="divide-y divide-border rounded-xl border border-border">
            {team.map((member) => (
              <li key={member.id} className="flex items-center justify-between gap-3 px-3 py-2.5">
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">{member.name || member.email}</p>
                  <p className="truncate text-xs text-muted-foreground">
                    {member.email} · {member.user_id ? "Active" : "Invite pending"}
                  </p>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={async () => {
                    if (!confirm(`Remove access for ${member.email}?`)) return;
                    await remove.mutateAsync(member.id);
                    toast.success("Access removed");
                  }}
                >
                  Remove
                </Button>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
