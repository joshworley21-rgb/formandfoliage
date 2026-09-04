import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { Check, X } from "lucide-react";
import { LOGO_URL } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { decidePublicQuote, fetchPublicQuote, type PublicQuoteView } from "@/lib/invoicing";

export const Route = createFileRoute("/quote/$key")({
  head: () => ({
    meta: [
      { title: "Your quote — JW Garden Services" },
      { name: "description", content: "View your garden work quote and accept or decline it." },
      { name: "robots", content: "noindex" },
    ],
  }),
  component: PublicQuotePage,
});

function gbp(value: number): string {
  return new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" }).format(value);
}

function PublicQuotePage() {
  const { key } = Route.useParams();
  const [quote, setQuote] = useState<PublicQuoteView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "sending" | "done">("loading");
  const [outcome, setOutcome] = useState<"accepted" | "declined" | null>(null);
  const [declining, setDeclining] = useState(false);
  const [reason, setReason] = useState("");

  useEffect(() => {
    let cancelled = false;
    fetchPublicQuote(key)
      .then((result) => {
        if (cancelled) return;
        setQuote(result.quote);
        // Already answered before this page was opened — show the outcome
        // rather than buttons that would only fail.
        if (result.quote.status === "accepted" || result.quote.status === "declined") {
          setOutcome(result.quote.status);
          setState("done");
        } else {
          setState("ready");
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "This quote link is not valid");
        setState("ready");
      });
    return () => {
      cancelled = true;
    };
  }, [key]);

  async function decide(decision: "accepted" | "declined") {
    setState("sending");
    try {
      await decidePublicQuote({ key, decision, reason: decision === "declined" ? reason : null });
      setOutcome(decision);
      setState("done");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not send your answer");
      setState("ready");
    }
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-2xl flex-col gap-6 px-5 py-10">
      <img
        src={LOGO_URL}
        alt="JW Garden Services logo"
        className="h-12 w-auto self-start"
        width={48}
        height={48}
      />

      {state === "loading" ? (
        <p className="text-sm text-muted-foreground">Loading your quote…</p>
      ) : error && !quote ? (
        <Card>
          <CardContent className="py-10 text-center">
            <p className="text-base font-medium text-foreground">{error}</p>
            <p className="mt-2 text-sm text-muted-foreground">
              If you think this is a mistake, please get back in touch and we'll send a new link.
            </p>
          </CardContent>
        </Card>
      ) : quote ? (
        <>
          <header>
            <h1 className="font-display text-2xl text-foreground">
              Quote {quote.number} for {quote.contactName}
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              From {quote.business.name}
              {quote.contactAddress ? ` · work at ${quote.contactAddress}` : ""}
            </p>
          </header>

          <Card>
            <CardContent className="p-0">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left">
                    <th className="px-4 py-3 font-medium">Work</th>
                    <th className="px-4 py-3 text-right font-medium">Rate</th>
                    <th className="px-4 py-3 text-right font-medium">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {quote.items.map((item, index) => (
                    <tr key={index} className="border-b border-border/60">
                      <td className="px-4 py-3">{item.description}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-right text-muted-foreground">
                        {item.quantity} × {gbp(item.unitPrice)}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-right tabular-nums">
                        {gbp(item.amount)}
                      </td>
                    </tr>
                  ))}
                  <tr>
                    <td colSpan={2} className="px-4 pt-3 text-right text-muted-foreground">
                      Subtotal
                    </td>
                    <td className="px-4 pt-3 text-right tabular-nums">{gbp(quote.subtotal)}</td>
                  </tr>
                  {quote.vatRegistered ? (
                    <tr>
                      <td colSpan={2} className="px-4 text-right text-muted-foreground">
                        VAT
                      </td>
                      <td className="px-4 text-right tabular-nums">{gbp(quote.vatAmount)}</td>
                    </tr>
                  ) : null}
                  <tr className="text-base font-semibold">
                    <td colSpan={2} className="px-4 pb-4 pt-2 text-right">
                      Total
                    </td>
                    <td className="px-4 pb-4 pt-2 text-right tabular-nums">{gbp(quote.total)}</td>
                  </tr>
                </tbody>
              </table>
            </CardContent>
          </Card>

          {quote.notes ? (
            <p className="whitespace-pre-line text-sm text-muted-foreground">{quote.notes}</p>
          ) : null}

          {quote.validUntil ? (
            <p className="text-sm text-muted-foreground">
              This quote is valid until {quote.validUntil}.
            </p>
          ) : null}

          {state === "done" && outcome ? (
            <Card>
              <CardContent className="py-8 text-center">
                <p className="text-base font-medium text-foreground">
                  {outcome === "accepted"
                    ? "Thanks — quote accepted."
                    : "Thanks for letting us know."}
                </p>
                <p className="mt-2 text-sm text-muted-foreground">
                  {outcome === "accepted"
                    ? `${quote.business.name} will be in touch to arrange a date.`
                    : "No problem at all — get in touch any time if things change."}
                </p>
              </CardContent>
            </Card>
          ) : quote.expired ? (
            <Card>
              <CardContent className="py-8 text-center text-sm text-muted-foreground">
                This quote has expired. Please get in touch and we'll happily send a fresh one.
              </CardContent>
            </Card>
          ) : (
            <div className="space-y-3">
              {error ? <p className="text-sm text-destructive">{error}</p> : null}

              {declining ? (
                <div className="space-y-2">
                  <Textarea
                    rows={3}
                    placeholder="Anything you'd like us to know? (optional)"
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      disabled={state === "sending"}
                      onClick={() => decide("declined")}
                    >
                      Confirm decline
                    </Button>
                    <Button variant="ghost" onClick={() => setDeclining(false)}>
                      Back
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-wrap gap-2">
                  <Button
                    size="lg"
                    disabled={state === "sending"}
                    onClick={() => decide("accepted")}
                  >
                    <Check className="mr-2 size-4" />
                    {state === "sending" ? "Sending…" : "Accept this quote"}
                  </Button>
                  <Button
                    variant="ghost"
                    size="lg"
                    disabled={state === "sending"}
                    onClick={() => setDeclining(true)}
                  >
                    <X className="mr-2 size-4" />
                    No thanks
                  </Button>
                </div>
              )}
            </div>
          )}

          <footer className="mt-2 text-sm text-muted-foreground">
            <p>
              {quote.business.name}
              {quote.business.phone ? ` · ${quote.business.phone}` : ""}
              {quote.business.email ? ` · ${quote.business.email}` : ""}
            </p>
            {quote.terms ? <p className="mt-2 whitespace-pre-line text-xs">{quote.terms}</p> : null}
          </footer>
        </>
      ) : null}
    </main>
  );
}
