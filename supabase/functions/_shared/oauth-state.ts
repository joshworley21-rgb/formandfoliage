// The `state` parameter carried through the Monzo OAuth round trip.
//
// Two jobs. It stops someone feeding the callback an authorisation code of
// their own choosing — the classic OAuth cross-site trick — and it is how the
// callback knows which user is coming back, since the browser arrives at a
// public endpoint with no session on it.
//
// The obvious way to do that is a table of pending states. This signs the state
// instead: the user id and an expiry, with an HMAC over both. Nothing to store,
// nothing to clean up, and a state that was not minted here cannot be forged
// without the signing secret.

const ENCODER = new TextEncoder();

/** Long enough to sign in to Monzo and approve on a phone, short enough to matter. */
const LIFETIME_MS = 15 * 60 * 1000;

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function fromBase64url(value: string): Uint8Array {
  const padded = value
    .replace(/-/g, "+")
    .replace(/_/g, "/")
    .padEnd(Math.ceil(value.length / 4) * 4, "=");
  return Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
}

/** Compares without leaking where two values first differ. */
function constantTimeEquals(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let differences = 0;
  for (let index = 0; index < a.length; index++) {
    differences |= a.charCodeAt(index) ^ b.charCodeAt(index);
  }
  return differences === 0;
}

async function sign(payload: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    ENCODER.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, ENCODER.encode(payload));
  return base64url(new Uint8Array(signature));
}

/** `<userId>.<expiryMs>.<signature>`, URL-safe throughout. */
export async function createState(
  userId: string,
  secret: string,
  now: number = Date.now(),
): Promise<string> {
  const payload = `${base64url(ENCODER.encode(userId))}.${now + LIFETIME_MS}`;
  return `${payload}.${await sign(payload, secret)}`;
}

export type StateResult = { ok: true; userId: string } | { ok: false; reason: string };

export async function readState(
  state: string,
  secret: string,
  now: number = Date.now(),
): Promise<StateResult> {
  if (!secret) return { ok: false, reason: "No signing secret is configured" };
  const parts = state.split(".");
  if (parts.length !== 3) return { ok: false, reason: "Malformed state" };

  const [encodedUser, expiry, signature] = parts as [string, string, string];
  const expected = await sign(`${encodedUser}.${expiry}`, secret);
  // Checked before the expiry, so an unsigned state never gets a different
  // answer from a signed one that happens to be stale.
  if (!constantTimeEquals(signature, expected)) return { ok: false, reason: "Bad signature" };

  const expiresAt = Number(expiry);
  if (!Number.isFinite(expiresAt)) return { ok: false, reason: "Malformed state" };
  if (expiresAt < now)
    return { ok: false, reason: "This link has expired — start again in Settings" };

  try {
    return { ok: true, userId: new TextDecoder().decode(fromBase64url(encodedUser)) };
  } catch {
    return { ok: false, reason: "Malformed state" };
  }
}
