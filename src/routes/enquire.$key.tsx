import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { LOGO_URL } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { submitEnquiry } from "@/lib/invoicing";

export const Route = createFileRoute("/enquire/$key")({
  head: () => ({
    meta: [
      { title: "Garden enquiry — JW Garden Services" },
      {
        name: "description",
        content:
          "Tell JW Garden Services about the work you need — lawns, hedges, borders and garden maintenance in Aylesbury and Bierton.",
      },
      { property: "og:title", content: "Garden enquiry — JW Garden Services" },
      {
        property: "og:description",
        content: "Send your details and a description of the work and we'll be in touch.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: EnquiryFormPage,
});

const EMPTY = { name: "", email: "", phone: "", address: "", message: "", website: "" };

function EnquiryFormPage() {
  const { key } = Route.useParams();
  const [form, setForm] = useState(EMPTY);
  const [state, setState] = useState<"idle" | "sending" | "sent">("idle");
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setState("sending");
    try {
      await submitEnquiry({ ...form, key, source: "website form" });
      setState("sent");
      setForm(EMPTY);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not send your enquiry");
      setState("idle");
    }
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-xl flex-col gap-6 px-5 py-10">
      <div className="flex flex-col items-center gap-3 text-center">
        <img
          src={LOGO_URL}
          alt="JW Garden Services logo"
          className="h-24 w-auto object-contain"
          width={96}
          height={96}
        />
        <h1 className="font-display text-2xl font-semibold tracking-tight text-foreground">
          Garden enquiry
        </h1>
        <p className="text-sm text-muted-foreground">
          Tell us where you are and what needs doing — we'll come back to you shortly.
        </p>
      </div>

      <Card>
        <CardContent className="py-6">
          {state === "sent" ? (
            <div className="space-y-4 text-center">
              <p className="text-base font-medium text-foreground">Thanks — enquiry received.</p>
              <p className="text-sm text-muted-foreground">
                We'll be in touch using the details you gave us.
              </p>
              <Button variant="outline" onClick={() => setState("idle")}>
                Send another
              </Button>
            </div>
          ) : (
            <form className="space-y-3" onSubmit={submit}>
              <div className="space-y-1.5">
                <Label htmlFor="name">Name</Label>
                <Input
                  id="name"
                  required
                  maxLength={120}
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="address">Address</Label>
                <Textarea
                  id="address"
                  rows={2}
                  maxLength={400}
                  value={form.address}
                  onChange={(e) => setForm({ ...form, address: e.target.value })}
                />
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label htmlFor="email">Email</Label>
                  <Input
                    id="email"
                    type="email"
                    maxLength={255}
                    value={form.email}
                    onChange={(e) => setForm({ ...form, email: e.target.value })}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="phone">Phone number</Label>
                  <Input
                    id="phone"
                    maxLength={40}
                    value={form.phone}
                    onChange={(e) => setForm({ ...form, phone: e.target.value })}
                  />
                </div>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="message">Description of job(s) required</Label>
                <Textarea
                  id="message"
                  rows={5}
                  maxLength={4000}
                  placeholder="Lawn cutting fortnightly, hedge trimmed, borders tidied…"
                  value={form.message}
                  onChange={(e) => setForm({ ...form, message: e.target.value })}
                />
              </div>
              <input
                type="text"
                tabIndex={-1}
                autoComplete="off"
                aria-hidden="true"
                className="hidden"
                value={form.website}
                onChange={(e) => setForm({ ...form, website: e.target.value })}
              />
              {error ? <p className="text-sm text-destructive">{error}</p> : null}
              <Button type="submit" className="w-full" disabled={state === "sending"}>
                {state === "sending" ? "Sending…" : "Send enquiry"}
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
    </main>
  );
}
