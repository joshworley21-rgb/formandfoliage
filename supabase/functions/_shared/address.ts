// Address lookup, with two interchangeable providers behind one shape.
//
// Which one runs is decided by whether GETADDRESS_API_KEY is set as a function
// secret:
//
//   set    -> getAddress.io, which is Royal Mail's Postcode Address File. Every
//             letterbox in the UK, including the ones down a lane that OSM has
//             never heard of. Costs money beyond its free tier.
//   unset  -> Photon, a free search over OpenStreetMap. No key, no account, no
//             bill — but house numbers are volunteered rather than surveyed, so
//             rural addresses are hit and miss.
//
// The app never learns which is in use. A suggestion either carries its full
// address already (Photon has everything in the search hit) or carries an id to
// resolve in a second call (getAddress.io charges for the detail separately).

export type Suggestion = {
  /** Opaque to the app: pass it back to resolve() when address is null. */
  id: string | null;
  /** What the person reads in the list. */
  label: string;
  /** The address to drop into the field, newline separated, or null. */
  address: string | null;
};

export type LookupProvider = "getaddress" | "photon";

export function activeProvider(): LookupProvider {
  return Deno.env.get("GETADDRESS_API_KEY") ? "getaddress" : "photon";
}

/**
 * Joins address parts the way an envelope is written: one thing per line, no
 * blanks, nothing repeated. The invoice PDF splits this back apart on newlines.
 */
export function formatAddress(parts: (string | null | undefined)[]): string {
  const lines: string[] = [];
  for (const part of parts) {
    const line = part?.trim();
    if (!line) continue;
    if (lines.some((existing) => existing.toLowerCase() === line.toLowerCase())) continue;
    lines.push(line);
  }
  return lines.join("\n");
}

// ---------------------------------------------------------------- Photon ---

type PhotonProperties = {
  name?: string;
  housenumber?: string;
  street?: string;
  district?: string;
  city?: string;
  county?: string;
  state?: string;
  postcode?: string;
  countrycode?: string;
};

/**
 * Photon returns a GeoJSON feature collection. Anything outside the UK is
 * dropped, and so is anything with neither a street nor a name, which is how
 * whole-town and whole-county hits get filtered out of a list meant to fill in
 * a doorstep.
 */
export function photonToSuggestions(payload: unknown): Suggestion[] {
  const features = (payload as { features?: unknown[] })?.features;
  if (!Array.isArray(features)) return [];

  const out: Suggestion[] = [];
  for (const feature of features) {
    const p = (feature as { properties?: PhotonProperties })?.properties;
    if (!p) continue;
    if (p.countrycode && p.countrycode !== "GB") continue;

    const street = p.housenumber && p.street ? `${p.housenumber} ${p.street}` : p.street;
    const first = street || p.name;
    if (!first) continue;

    const address = formatAddress([first, p.district, p.city, p.county, p.postcode]);
    if (!address) continue;

    out.push({ id: null, label: address.split("\n").join(", "), address });
  }
  return dedupe(out);
}

// ----------------------------------------------------------- getAddress ---

/**
 * The autocomplete endpoint gives a one-line label and an id. The full address
 * is a second, separately billed call, so it is only made once something is
 * actually chosen.
 */
export function getAddressToSuggestions(payload: unknown): Suggestion[] {
  const suggestions = (payload as { suggestions?: unknown[] })?.suggestions;
  if (!Array.isArray(suggestions)) return [];

  const out: Suggestion[] = [];
  for (const entry of suggestions) {
    const s = entry as { address?: string; id?: string; url?: string };
    const label = s?.address?.trim();
    if (!label) continue;
    // Older responses carry only a url like "/get/abc123".
    const id = s.id?.trim() || s.url?.split("/").filter(Boolean).pop() || null;
    out.push({ id, label, address: null });
  }
  return dedupe(out);
}

type GetAddressDetail = {
  formatted_address?: unknown;
  line_1?: string;
  line_2?: string;
  line_3?: string;
  line_4?: string;
  locality?: string;
  town_or_city?: string;
  county?: string;
  postcode?: string;
};

/** Turns one resolved getAddress.io record into the address for the field. */
export function getAddressDetailToAddress(payload: unknown, postcode?: string): string {
  const d = (payload ?? {}) as GetAddressDetail;

  // formatted_address is already envelope order, but is padded with empty
  // strings to a fixed length, and never includes the postcode.
  const formatted = Array.isArray(d.formatted_address)
    ? d.formatted_address.filter((line): line is string => typeof line === "string")
    : [];

  const parts = formatted.length
    ? formatted
    : [d.line_1, d.line_2, d.line_3, d.line_4, d.locality, d.town_or_city, d.county];

  return formatAddress([...parts, d.postcode || postcode]);
}

// ------------------------------------------------------------------------

/** Same doorstep listed twice is worse than useless in a list this short. */
function dedupe(suggestions: Suggestion[]): Suggestion[] {
  const seen = new Set<string>();
  return suggestions.filter((s) => {
    const key = s.label.toLowerCase().replace(/\s+/g, " ");
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
