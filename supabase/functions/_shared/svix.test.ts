// The webhook endpoint is public, so this signature check is the only thing
// between Resend's delivery reports and anyone posting whatever they like into
// the invoice log. Worth testing properly.
//
//   bun test supabase/functions/_shared/svix.test.ts
import { expect, test } from "bun:test";
import { verifySvixSignature } from "./svix.ts";

const SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
const ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
const BODY = JSON.stringify({ type: "email.delivered", data: { email_id: "abc" } });

/** Signs exactly as Svix does, so the test is not just calling the code twice. */
async function sign(body: string, id: string, timestamp: number): Promise<string> {
  const raw = SECRET.slice("whsec_".length);
  const keyBytes = Uint8Array.from(atob(raw), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(`${id}.${timestamp}.${body}`),
  );
  return `v1,${btoa(String.fromCharCode(...new Uint8Array(mac)))}`;
}

const now = Date.now();
const ts = Math.floor(now / 1000);

test("accepts a correctly signed request", async () => {
  const signature = await sign(BODY, ID, ts);
  expect(
    await verifySvixSignature(BODY, { id: ID, timestamp: String(ts), signature }, SECRET, now),
  ).toEqual({ ok: true });
});

test("accepts when several signatures are offered and one matches", async () => {
  const good = await sign(BODY, ID, ts);
  const signature = `v1,c2hvdWxkbm90bWF0Y2g= ${good}`;
  expect(
    await verifySvixSignature(BODY, { id: ID, timestamp: String(ts), signature }, SECRET, now),
  ).toEqual({ ok: true });
});

test("rejects a body altered after signing", async () => {
  const signature = await sign(BODY, ID, ts);
  const tampered = JSON.stringify({ type: "email.delivered", data: { email_id: "someone-elses" } });
  const result = await verifySvixSignature(
    tampered,
    { id: ID, timestamp: String(ts), signature },
    SECRET,
    now,
  );
  expect(result.ok).toBe(false);
});

test("rejects a signature made with a different secret", async () => {
  const signature = await sign(BODY, ID, ts);
  const result = await verifySvixSignature(
    BODY,
    { id: ID, timestamp: String(ts), signature },
    "whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    now,
  );
  expect(result.ok).toBe(false);
});

test("rejects a replay from outside the time window", async () => {
  const old = ts - 60 * 60;
  const signature = await sign(BODY, ID, old);
  const result = await verifySvixSignature(
    BODY,
    { id: ID, timestamp: String(old), signature },
    SECRET,
    now,
  );
  expect(result).toEqual({
    ok: false,
    reason: "Signature timestamp is outside the accepted window",
  });
});

test("rejects a request with no signature headers", async () => {
  const result = await verifySvixSignature(
    BODY,
    { id: null, timestamp: null, signature: null },
    SECRET,
    now,
  );
  expect(result).toEqual({ ok: false, reason: "Missing signature headers" });
});

test("rejects everything when no secret is configured", async () => {
  const signature = await sign(BODY, ID, ts);
  const result = await verifySvixSignature(
    BODY,
    { id: ID, timestamp: String(ts), signature },
    "",
    now,
  );
  expect(result).toEqual({ ok: false, reason: "No webhook secret is configured" });
});

test("rejects a signature that is the right shape but wrong value", async () => {
  const result = await verifySvixSignature(
    BODY,
    { id: ID, timestamp: String(ts), signature: "v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" },
    SECRET,
    now,
  );
  expect(result).toEqual({ ok: false, reason: "Signature did not match" });
});
