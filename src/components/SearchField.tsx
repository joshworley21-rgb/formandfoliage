// The search box above a list.
//
// Kept as its own component so Clients and Invoices look and behave the same:
// one of them being a plain input and the other having a clear button is the
// sort of small inconsistency that makes an app feel homemade.
import { Search, X } from "lucide-react";
import { Input } from "@/components/ui/input";

export function SearchField({
  value,
  onChange,
  placeholder,
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  /** For screen readers — the box has an icon rather than a visible label. */
  label: string;
}) {
  return (
    <div className="relative">
      <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
      <Input
        type="search"
        inputMode="search"
        aria-label={label}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        // Room for the icon on the left, and for the clear button on the right
        // once there is something to clear.
        className={value ? "h-11 pl-9 pr-10" : "h-11 pl-9"}
      />
      {value ? (
        <button
          type="button"
          aria-label="Clear search"
          onClick={() => onChange("")}
          // Full height and a wide tap target: clearing a search one-handed in
          // a garden should not need a careful thumb.
          className="absolute right-0 top-0 flex h-11 w-10 items-center justify-center text-muted-foreground"
        >
          <X className="size-4" />
        </button>
      ) : null}
    </div>
  );
}
