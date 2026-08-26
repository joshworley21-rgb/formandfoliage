import { Loader2, MapPin, Search, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { resolveAddress, searchAddresses, type AddressSuggestion } from "@/lib/invoicing";

/**
 * Finds an address so it does not have to be typed on a phone.
 *
 * Deliberately sits above the address field rather than replacing it: the field
 * stays a plain textarea underneath, so a gate code, a "round the back", or a
 * garden the lookup has never heard of can still be written by hand. Losing
 * signal in a garden must not mean losing the ability to add a client.
 */
export function AddressSearch({ onPick }: { onPick: (address: string) => void }) {
  const [query, setQuery] = useState("");
  const [suggestions, setSuggestions] = useState<AddressSuggestion[]>([]);
  const [searching, setSearching] = useState(false);
  const [picking, setPicking] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [searched, setSearched] = useState(false);

  // Every keystroke is a billable lookup on the paid provider and a request on
  // the free one, so the typing is allowed to settle first.
  const latest = useRef(0);
  useEffect(() => {
    const term = query.trim();
    if (term.length < 3) {
      setSuggestions([]);
      setSearched(false);
      setMessage(null);
      return;
    }

    const run = ++latest.current;
    const timer = setTimeout(async () => {
      setSearching(true);
      setMessage(null);
      try {
        const result = await searchAddresses(term);
        // A slow reply from an earlier keystroke must not overwrite a newer one.
        if (run !== latest.current) return;
        setSuggestions(result.suggestions);
        setSearched(true);
      } catch (error) {
        if (run !== latest.current) return;
        setSuggestions([]);
        setSearched(false);
        setMessage(error instanceof Error ? error.message : "The lookup did not respond.");
      } finally {
        if (run === latest.current) setSearching(false);
      }
    }, 450);

    return () => clearTimeout(timer);
  }, [query]);

  async function pick(suggestion: AddressSuggestion) {
    // Photon hands back the whole address in the search hit. getAddress.io
    // charges for the detail separately, so it is only fetched on a tap.
    if (suggestion.address) {
      onPick(suggestion.address);
      clear();
      return;
    }
    if (!suggestion.id) return;

    setPicking(suggestion.id);
    try {
      const result = await resolveAddress(suggestion.id, postcodeIn(query));
      onPick(result.address);
      clear();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not fetch that address.");
    } finally {
      setPicking(null);
    }
  }

  function clear() {
    latest.current++;
    setQuery("");
    setSuggestions([]);
    setSearched(false);
    setMessage(null);
  }

  return (
    <div className="space-y-2">
      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search a postcode or street"
          autoComplete="off"
          className="pl-9 pr-9"
          // A form submit here would save the client, not run a search.
          onKeyDown={(e) => {
            if (e.key === "Enter") e.preventDefault();
          }}
        />
        {searching ? (
          <Loader2 className="absolute right-3 top-1/2 size-4 -translate-y-1/2 animate-spin text-muted-foreground" />
        ) : query ? (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="Clear the address search"
            className="absolute right-1 top-1/2 size-8 -translate-y-1/2"
            onClick={clear}
          >
            <X className="size-4" />
          </Button>
        ) : null}
      </div>

      {message ? <p className="text-xs text-warning">{message}</p> : null}

      {suggestions.length > 0 ? (
        <ul className="max-h-56 overflow-y-auto rounded-xl border border-border bg-card">
          {suggestions.map((suggestion, index) => (
            <li key={suggestion.id ?? `${index}-${suggestion.label}`}>
              <button
                type="button"
                disabled={picking !== null}
                onClick={() => pick(suggestion)}
                className="flex w-full items-start gap-2 border-b border-border px-3 py-2.5 text-left text-sm last:border-b-0 hover:bg-secondary disabled:opacity-60"
              >
                {picking === suggestion.id ? (
                  <Loader2 className="mt-0.5 size-4 shrink-0 animate-spin text-muted-foreground" />
                ) : (
                  <MapPin className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                )}
                <span className="min-w-0 break-words">{suggestion.label}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : searched && !searching ? (
        <p className="text-xs text-muted-foreground">
          Nothing found for that. Type the address in below instead.
        </p>
      ) : null}
    </div>
  );
}

/** The postcode out of what was typed, for filling a gap in a resolved record. */
function postcodeIn(query: string): string | undefined {
  const match = query.toUpperCase().match(/\b[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2}\b/);
  return match ? match[0].replace(/\s+/g, " ") : undefined;
}
