import { createFileRoute, Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import {
  Copy,
  ExternalLink,
  FileSignature,
  Mail,
  Phone,
  Settings as SettingsIcon,
  Share2,
  Trash2,
  UserPlus,
} from "lucide-react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
import { copyText } from "@/lib/clipboard";
import { isNativeApp, openOrShare } from "@/lib/native";
import { QuoteDialog } from "@/components/QuoteDialog";
import { OwnerOnly } from "@/components/OwnerOnly";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  ENQUIRY_STATUS_LABELS,
  useConvertEnquiry,
  useDeleteEnquiry,
  useEnquiries,
  useEnquiryLink,
  useUpdateEnquiry,
  type Enquiry,
  type EnquiryStatus,
} from "@/hooks/use-enquiries";
import { quoteDraftFromEnquiry, type SaveQuoteInput } from "@/hooks/use-quotes";
import { useSettings } from "@/hooks/use-crm";
import { publicSiteUrl } from "@/lib/site";

export const Route = createFileRoute("/_authenticated/enquiries")({
  head: () => ({
    meta: [
      { title: "Enquiries — JW Garden Services CRM" },
      {
        name: "description",
        content: "Website enquiries inbox: new leads, contact details and job descriptions.",
      },
      { property: "og:title", content: "Enquiries — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Website enquiries inbox: new leads, contact details and job descriptions.",
      },
    ],
  }),
  component: () => (
    <OwnerOnly>
      <EnquiriesPage />
    </OwnerOnly>
  ),
});

const STATUS_VARIANT: Record<EnquiryStatus, "default" | "secondary" | "outline"> = {
  new: "default",
  contacted: "secondary",
  converted: "outline",
  closed: "outline",
};

function EnquiriesPage() {
  const { data: enquiries = [], isLoading } = useEnquiries();
  const { data: key } = useEnquiryLink();
  const update = useUpdateEnquiry();
  const remove = useDeleteEnquiry();
  const convert = useConvertEnquiry();
  const navigate = useNavigate();
  const [quoting, setQuoting] = useState<SaveQuoteInput | null>(null);

  const { data: settings } = useSettings();
  const siteUrl = publicSiteUrl(settings?.public_site_url);
  const formUrl = key && siteUrl ? `${siteUrl}/enquire/${key}` : null;

  return (
    <AppShell
      title="Enquiries"
      description="Everything sent from your website enquiry form lands here."
    >
      <Card className="mb-4">
        <CardHeader>
          <CardTitle>Your website enquiry link</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-muted-foreground">
            Link (or embed) this page from your website's "Get in touch" button. Every submission
            appears below instead of just landing in your inbox.
          </p>
          {formUrl ? (
            <div className="space-y-2">
              {/*
                select-all so a long press grabs the whole thing, and break-all
                so it wraps instead of being cut off at the edge of the phone.
              */}
              <code className="block select-all break-all rounded-lg border border-border bg-muted px-3 py-2 text-xs">
                {formUrl}
              </code>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="h-10 flex-1"
                  onClick={async () => {
                    // Says which of the two happened. This used to await a
                    // promise that rejects inside the app's WebView, with
                    // nothing catching it, so the button appeared dead.
                    if (await copyText(formUrl)) toast.success("Link copied");
                    else toast.error("Could not copy — press and hold the link to select it");
                  }}
                >
                  <Copy className="size-4" /> Copy link
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-10 flex-1"
                  onClick={async () => {
                    // target="_blank" opens nothing inside the app, and Share
                    // is the more useful thing anyway: it puts the link into
                    // whatever you are building the website in.
                    try {
                      await openOrShare(formUrl, "Your enquiry form");
                    } catch (error) {
                      toast.error(
                        error instanceof Error ? error.message : "Could not open the link",
                      );
                    }
                  }}
                >
                  {isNativeApp() ? (
                    <>
                      <Share2 className="size-4" /> Share
                    </>
                  ) : (
                    <>
                      <ExternalLink className="size-4" /> Preview
                    </>
                  )}
                </Button>
              </div>
            </div>
          ) : (
            /*
              The link needs a website to live on. Until one is saved there is
              nothing to copy, and this used to be a disabled button beside a
              line of grey text — which reads exactly like a broken button.
            */
            <div className="rounded-xl border border-dashed border-border p-4 text-center">
              <p className="text-sm text-muted-foreground">
                A ready-made enquiry page needs a website address to live on. Add yours in
                Settings and the link will appear here.
              </p>
              <Button asChild variant="outline" size="sm" className="mt-3 h-10">
                <Link to="/settings">
                  <SettingsIcon className="size-4" /> Open Settings
                </Link>
              </Button>
            </div>
          )}

          {key ? (
            <div className="space-y-2 border-t border-border pt-3">
              <p className="text-sm text-muted-foreground">
                Already have a contact form on your website? It can post straight here instead.
                This is your enquiry key — it says which business an enquiry belongs to.
              </p>
              <code className="block select-all break-all rounded-lg border border-border bg-muted px-3 py-2 text-xs">
                {key}
              </code>
              <Button
                variant="outline"
                size="sm"
                className="h-10 w-full"
                onClick={async () => {
                  if (await copyText(key)) toast.success("Key copied");
                  else toast.error("Could not copy — press and hold the key to select it");
                }}
              >
                <Copy className="size-4" /> Copy key
              </Button>
            </div>
          ) : null}
        </CardContent>
      </Card>

      {isLoading ? (
        <p className="text-sm text-muted-foreground">Loading enquiries…</p>
      ) : enquiries.length === 0 ? (
        <Card>
          <CardContent className="py-10 text-center text-sm text-muted-foreground">
            No enquiries yet. Point your website form at the link above and they'll appear here.
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3">
          {enquiries.map((enquiry) => (
            <EnquiryCard
              key={enquiry.id}
              enquiry={enquiry}
              onStatus={async (status) => {
                await update.mutateAsync({ id: enquiry.id, status });
              }}
              onConvert={async () => {
                try {
                  await convert.mutateAsync(enquiry);
                  toast.success(`${enquiry.name} added to clients`);
                  navigate({ to: "/clients" });
                } catch (error) {
                  toast.error(error instanceof Error ? error.message : "Could not convert");
                }
              }}
              onQuote={() => setQuoting(quoteDraftFromEnquiry(enquiry))}
              onDelete={async () => {
                if (!confirm(`Delete the enquiry from ${enquiry.name}?`)) return;
                await remove.mutateAsync(enquiry.id);
                toast.success("Enquiry deleted");
              }}
            />
          ))}
        </div>
      )}

      <QuoteDialog
        open={quoting !== null}
        draft={quoting}
        onClose={() => setQuoting(null)}
        onSaved={() => {
          setQuoting(null);
          navigate({ to: "/quotes" });
        }}
      />
    </AppShell>
  );
}

function EnquiryCard({
  enquiry,
  onStatus,
  onConvert,
  onQuote,
  onDelete,
}: {
  enquiry: Enquiry;
  onStatus: (status: EnquiryStatus) => void | Promise<void>;
  onConvert: () => void | Promise<void>;
  onQuote: () => void;
  onDelete: () => void | Promise<void>;
}) {
  const status = (enquiry.status as EnquiryStatus) ?? "new";
  return (
    <Card>
      <CardContent className="space-y-3 py-5">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="truncate font-semibold text-foreground">{enquiry.name}</p>
            <p className="text-xs text-muted-foreground">
              {new Date(enquiry.created_at).toLocaleString("en-GB", {
                dateStyle: "medium",
                timeStyle: "short",
              })}
              {enquiry.source ? ` · ${enquiry.source}` : ""}
            </p>
          </div>
          <Badge variant={STATUS_VARIANT[status]}>{ENQUIRY_STATUS_LABELS[status]}</Badge>
        </div>

        {enquiry.address ? (
          <p className="whitespace-pre-line text-sm text-muted-foreground">{enquiry.address}</p>
        ) : null}
        {enquiry.message ? (
          <p className="whitespace-pre-line text-sm text-foreground/90">{enquiry.message}</p>
        ) : null}

        <div className="flex flex-wrap gap-2">
          {enquiry.phone ? (
            <Button variant="outline" size="sm" asChild>
              <a href={`tel:${enquiry.phone}`}>
                <Phone className="size-4" /> {enquiry.phone}
              </a>
            </Button>
          ) : null}
          {enquiry.email ? (
            <Button variant="outline" size="sm" asChild>
              <a href={`mailto:${enquiry.email}`}>
                <Mail className="size-4" /> Email
              </a>
            </Button>
          ) : null}
        </div>

        <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
          <Select value={status} onValueChange={(value) => onStatus(value as EnquiryStatus)}>
            <SelectTrigger className="w-40">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {(Object.keys(ENQUIRY_STATUS_LABELS) as EnquiryStatus[]).map((value) => (
                <SelectItem key={value} value={value}>
                  {ENQUIRY_STATUS_LABELS[value]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button size="sm" onClick={onQuote}>
            <FileSignature className="size-4" />
            Quote this
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={onConvert}
            disabled={Boolean(enquiry.client_id)}
          >
            <UserPlus className="size-4" />
            {enquiry.client_id ? "Client created" : "Add as client"}
          </Button>
          <Button variant="ghost" size="sm" onClick={onDelete} aria-label="Delete enquiry">
            <Trash2 className="size-4 text-destructive" />
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
