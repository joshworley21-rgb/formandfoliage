// Address lookup for the client form.
//
// This runs server-side rather than in the app for two reasons: an API key for
// the paid provider must never be inside an APK anyone can unzip, and swapping
// providers must not need a new build on the phone.
//
// Signed in only. It is a proxy to a metered third party, so it is not left
// open to the internet.
import { requireUser } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import {
  activeProvider,
  getAddressDetailToAddress,
  getAddressToSuggestions,
  photonToSuggestions,
  type Suggestion,
} from "../_shared/address.ts";

type LookupInput = {
  /** What has been typed so far. */
  query?: string;
  /** A suggestion id from a previous search, to turn into a full address. */
  resolve?: string;
  /** The postcode that was searched, used if the resolved record omits it. */
  postcode?: string;
};

const GETADDRESS_BASE = "https://api.getaddress.io";
const PHOTON_URL = "https://photon.komoot.io/api/";

/** Long enough to be worth a lookup; shorter than this matches half the county. */
const MIN_QUERY = 3;

Deno.serve(
  serveJson(async (request) => {
    await requireUser(request);
    const input = (await request.json()) as LookupInput;
    const provider = activeProvider();

    if (input.resolve) {
      const address = await resolveAddress(input.resolve, input.postcode);
      return json({ provider, address });
    }

    const query = input.query?.trim() ?? "";
    if (query.length < MIN_QUERY) return json({ provider, suggestions: [] });

    const suggestions =
      provider === "getaddress" ? await searchGetAddress(query) : await searchPhoton(query);

    return json({ provider, suggestions });
  }),
);

async function searchPhoton(query: string): Promise<Suggestion[]> {
  const url = new URL(PHOTON_URL);
  url.searchParams.set("q", query);
  url.searchParams.set("limit", "10");
  url.searchParams.set("lang", "en");
  // A rough box around Great Britain and Northern Ireland. Photon treats this
  // as a preference rather than a filter, so photonToSuggestions still drops
  // anything that comes back with a non-GB country code.
  url.searchParams.set("bbox", "-8.65,49.8,1.77,60.9");

  const response = await fetch(url, {
    headers: {
      // Photon asks callers to identify themselves. A browser cannot set this
      // header, which is another reason the lookup lives here.
      "User-Agent": "JW-Garden-Services-CRM/1.0 (client address lookup)",
      Accept: "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(await describeFailure("The address service", response));
  }
  return photonToSuggestions(await response.json());
}

async function searchGetAddress(query: string): Promise<Suggestion[]> {
  const key = requireKey();
  const response = await fetch(
    `${GETADDRESS_BASE}/autocomplete/${encodeURIComponent(query)}?api-key=${encodeURIComponent(key)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ top: 10, all: true }),
    },
  );

  if (!response.ok) {
    throw new Error(await describeFailure("Royal Mail address lookup", response));
  }
  return getAddressToSuggestions(await response.json());
}

async function resolveAddress(id: string, postcode?: string): Promise<string> {
  const key = requireKey();
  const response = await fetch(
    `${GETADDRESS_BASE}/get/${encodeURIComponent(id)}?api-key=${encodeURIComponent(key)}`,
  );

  if (!response.ok) {
    throw new Error(await describeFailure("Royal Mail address lookup", response));
  }
  return getAddressDetailToAddress(await response.json(), postcode);
}

function requireKey(): string {
  const key = Deno.env.get("GETADDRESS_API_KEY");
  // Only reachable if the key was removed between a search and a tap.
  if (!key) throw new Error("The paid address lookup is no longer connected.");
  return key;
}

/**
 * Says what the service actually did, in words worth reading. A lookup that
 * fails silently is a search box that just never finds anything, and there is
 * no way to tell that apart from an address that is genuinely not listed.
 */
async function describeFailure(who: string, response: Response): Promise<string> {
  const body = await response.text().catch(() => "");
  console.error(`${who} failed`, response.status, body.slice(0, 300));

  if (response.status === 401 || response.status === 403) {
    return `${who} rejected the API key. Check it in Supabase under Edge Function secrets.`;
  }
  if (response.status === 404) {
    return "No addresses matched.";
  }
  if (response.status === 429) {
    return `${who} is rate limiting the lookups. Wait a moment, or type the address in by hand.`;
  }
  return `${who} is not responding (${response.status}). You can still type the address in.`;
}
