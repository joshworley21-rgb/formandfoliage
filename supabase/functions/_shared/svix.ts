// Verifies the signature Resend puts on its webhooks (Svix format).
//
// The endpoint has no JWT — anyone can post to it — so the signature is the
// only thing separating a real delivery report from someone writing whatever
// they like into the invoice log. Implemented here rather than pulled from a
// registry: it is thirty lines of Web Crypto and one less dependency to trust.

const PREFIX = "whsec_";
/** Reject anything older than this, so a captured request cannot be replayed. */
const TOLERANCE_SECONDS = 5 * 60;

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

/** Compares without leaking where two values first differ. */
function constantTimeEquals(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let differences = 0;
  for (let i = 0; i < a.length; i++) differences |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return differences === 0;
}

export type SvixHeaders = {
  id: string | null;
  timestamp: string | null;
  signature: string | null;
};

export type VerifyResult = { ok: true } | { ok: false; reason: string };

export async function verifySvixSignature(
  body: string,
  headers: SvixHeaders,
  secret: string,
  now: number = Date.now(),
): Promise<VerifyResult> {
  if (!secret) return { ok: false, reason: "No webhook secret is configured" };
  const { id, timestamp, signature } = headers;
  if (!id || !timestamp || !signature) return { ok: false, reason: "Missing signature headers" };

  const sentAt = Number(timestamp);
  if (!Number.isFinite(sentAt)) return { ok: false, reason: "Signature timestamp is not a number" };
  if (Math.abs(now / 1000 - sentAt) > TOLERANCE_SECONDS) {
    return { ok: false, reason: "Signature timestamp is outside the accepted window" };
  }

  const raw = secret.startsWith(PREFIX) ? secret.slice(PREFIX.length) : secret;
  let keyBytes: Uint8Array;
  try {
    keyBytes = decodeBase64(raw);
  } catch {
    return { ok: false, reason: "Webhook secret is not valid base64" };
  }

  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes as unknown as ArrayBuffer,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signed = new TextEncoder().encode(`${id}.${timestamp}.${body}`);
  const mac = await crypto.subtle.sign("HMAC", key, signed as unknown as ArrayBuffer);
  const expected = btoa(String.fromCharCode(...new Uint8Array(mac)));

  // The header carries a space-separated list so keys can be rotated; any one
  // matching is enough.
  for (const entry of signature.split(" ")) {
    const [version, value] = entry.split(",");
    if (version !== "v1" || !value) continue;
    if (constantTimeEquals(value, expected)) return { ok: true };
  }
  return { ok: false, reason: "Signature did not match" };
}
