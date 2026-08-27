// The website enquiry link and key.
//
// This used to sit at the top of the Enquiries screen, above the enquiries
// themselves — a setup panel in the way of the thing you opened the page for.
// It is set once when the website is built and then never touched again, which
// makes it settings, so it lives in Settings.
//
// It sits directly under the website address it depends on: without one there
// is no link to give out, and having the cause and the effect on the same
// screen is most of why this move is worth making.
import { Copy, ExternalLink, Share2 } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useEnquiryLink } from "@/hooks/use-enquiries";
import { useSettings } from "@/hooks/use-crm";
import { copyText } from "@/lib/clipboard";
import { isNativeApp, openOrShare } from "@/lib/native";
import { publicSiteUrl } from "@/lib/site";

export function EnquiryLinkCard() {
  const { data: key } = useEnquiryLink();
  const { data: settings } = useSettings();
  const siteUrl = publicSiteUrl(settings?.public_site_url);
  const formUrl = key && siteUrl ? `${siteUrl}/enquire/${key}` : null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Your website enquiry link</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-sm text-muted-foreground">
          Link (or embed) this page from your website's "Get in touch" button. Every submission
          appears on the Enquiries screen instead of just landing in your inbox.
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
                type="button"
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
                type="button"
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
                    toast.error(error instanceof Error ? error.message : "Could not open the link");
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
            The link needs a website to live on. On the Enquiries screen this
            pointed at Settings; here the field it is asking for is a few inches
            up the same page, so it says so.
          */
          <div className="rounded-xl border border-dashed border-border p-4 text-center">
            <p className="text-sm text-muted-foreground">
              A ready-made enquiry page needs a website address to live on. Fill in{" "}
              <strong>Website address</strong> above, save, and the link will appear here.
            </p>
          </div>
        )}

        {key ? (
          <div className="space-y-2 border-t border-border pt-3">
            <p className="text-sm text-muted-foreground">
              Already have a contact form on your website? It can post straight here instead. This
              is your enquiry key — it says which business an enquiry belongs to.
            </p>
            <code className="block select-all break-all rounded-lg border border-border bg-muted px-3 py-2 text-xs">
              {key}
            </code>
            <Button
              type="button"
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
  );
}
