import type { Client, Invoice, InvoiceItem, Settings } from "@/lib/crm";
import { parseBankDetails, prettyDate } from "@/lib/invoice-pdf";
import { LOGO_URL } from "@/components/Logo";

function gbp(n: number | string | null | undefined) {
  return `£${Number(n ?? 0).toLocaleString("en-GB", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

type Props = {
  invoice: Invoice;
  items: InvoiceItem[];
  client: Client;
  settings: Settings | null;
};

/** On-screen replica of the invoice PDF (mobile browsers can't render PDF iframes). */
export function InvoicePreviewDoc({ invoice, items, client, settings }: Props) {
  const business = settings?.business_name || "JW Garden Services";
  const bankRows = parseBankDetails(settings?.bank_details);

  return (
    <div className="rounded-lg border bg-white p-5 text-[13px] text-neutral-900 sm:p-8">
      <div className="flex items-start justify-between gap-4">
        <h2 className="text-2xl font-bold tracking-tight">Invoice</h2>
        <img src={LOGO_URL} alt="" className="h-12 w-auto object-contain" />
      </div>

      <dl className="mt-4 space-y-1">
        {[
          ["Invoice number", invoice.number],
          ["Invoice date", prettyDate(invoice.issue_date)],
          ["Due date", invoice.due_date ? prettyDate(invoice.due_date) : "On receipt"],
        ].map(([label, value]) => (
          <div key={label} className="flex gap-3">
            <dt className="w-28 shrink-0 font-semibold">{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>

      <div className="mt-6 grid gap-6 sm:grid-cols-2">
        <div>
          <p className="text-base font-bold">Billed to</p>
          <p className="mt-2">{client.name}</p>
          {client.address ? (
            <p className="mt-2 whitespace-pre-line text-neutral-700">{client.address}</p>
          ) : null}
        </div>
        <div>
          <p className="text-base font-bold">{business}</p>
          {settings?.address ? (
            <p className="mt-2 whitespace-pre-line text-neutral-700">{settings.address}</p>
          ) : null}
          {settings?.phone ? <p className="mt-2 text-neutral-700">{settings.phone}</p> : null}
          {settings?.email ? <p className="mt-2 text-neutral-700">{settings.email}</p> : null}
        </div>
      </div>

      {invoice.notes ? (
        <div className="mt-6">
          <p className="font-semibold">Invoice description</p>
          <p className="whitespace-pre-line text-neutral-700">{invoice.notes}</p>
        </div>
      ) : null}

      <div className="mt-6 overflow-x-auto">
        <table className="w-full border-collapse text-left">
          <thead>
            <tr className="bg-neutral-100">
              <th className="px-3 py-2 font-semibold">Description</th>
              <th className="px-3 py-2 text-right font-semibold">Qty</th>
              <th className="px-3 py-2 text-right font-semibold">Unit price</th>
              <th className="px-3 py-2 text-right font-semibold">Total</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.id} className="border-b border-neutral-200 align-top">
                <td className="px-3 py-3">
                  {item.item_date ? `${prettyDate(item.item_date)} — ` : ""}
                  {item.description}
                </td>
                <td className="px-3 py-3 text-right">{Number(item.quantity)}</td>
                <td className="px-3 py-3 text-right">{gbp(item.unit_price)}</td>
                <td className="px-3 py-3 text-right">{gbp(item.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="mt-6 grid gap-6 sm:grid-cols-2">
        <div>
          {bankRows.length ? (
            <>
              <p className="text-base font-bold">Payment details</p>
              <dl className="mt-2 space-y-1">
                {bankRows.map(([label, value], i) => (
                  <div key={`${label}-${i}`} className="flex gap-3">
                    <dt className="w-32 shrink-0 font-semibold">{label ? `${label}:` : ""}</dt>
                    <dd className="text-neutral-700">{value}</dd>
                  </div>
                ))}
                <div className="flex gap-3">
                  <dt className="w-32 shrink-0 font-semibold">Payment reference:</dt>
                  <dd className="text-neutral-700">{invoice.number}</dd>
                </div>
              </dl>
            </>
          ) : null}
        </div>
        <div className="space-y-2 border-t border-neutral-300 pt-3">
          {Number(invoice.vat_amount) > 0 ? (
            <>
              <div className="flex justify-between">
                <span>Subtotal</span>
                <span>{gbp(invoice.subtotal)}</span>
              </div>
              <div className="flex justify-between">
                <span>VAT ({settings?.vat_rate ?? 20}%)</span>
                <span>{gbp(invoice.vat_amount)}</span>
              </div>
            </>
          ) : null}
          <div className="flex justify-between font-bold">
            <span>Total</span>
            <span>{gbp(invoice.total)}</span>
          </div>
        </div>
      </div>

      <div className="mt-6 rounded-md bg-neutral-100 px-4 py-3 text-base font-bold">
        {gbp(invoice.total)} due
        {invoice.due_date ? ` by ${prettyDate(invoice.due_date)}` : " on receipt"}
      </div>

      {settings?.payment_terms ? (
        <p className="mt-4 whitespace-pre-line text-[11px] text-neutral-500">
          {settings.payment_terms}
        </p>
      ) : null}
    </div>
  );
}
