// The state is the only thing standing between the public callback and someone
// pointing it at a user of their choosing, so the tests are about what it
// refuses rather than what it accepts.
import { expect, test } from "bun:test";
import { createState, readState } from "./oauth-state.ts";

const SECRET = "test-signing-secret";
const USER = "3f6d9a2c-1c4e-4a7b-9d21-6f0b8e5a1c33";

test("a state made here reads back as the user who started the flow", async () => {
  const state = await createState(USER, SECRET);
  expect(await readState(state, SECRET)).toEqual({ ok: true, userId: USER });
});

test("a state signed with another secret is refused", async () => {
  const state = await createState(USER, "someone-elses-secret");
  expect(await readState(state, SECRET)).toEqual({ ok: false, reason: "Bad signature" });
});

test("swapping the user id in a valid state breaks the signature", async () => {
  const state = await createState(USER, SECRET);
  const [, expiry, signature] = state.split(".") as [string, string, string];
  const forged = `${btoa("attacker-user-id").replace(/=+$/, "")}.${expiry}.${signature}`;
  expect(await readState(forged, SECRET)).toEqual({ ok: false, reason: "Bad signature" });
});

test("extending the expiry breaks the signature too", async () => {
  const state = await createState(USER, SECRET);
  const [user, , signature] = state.split(".") as [string, string, string];
  const forged = `${user}.${Date.now() + 86_400_000}.${signature}`;
  expect(await readState(forged, SECRET)).toEqual({ ok: false, reason: "Bad signature" });
});

test("a genuine state stops working once it is old", async () => {
  const state = await createState(USER, SECRET);
  const anHourLater = Date.now() + 60 * 60 * 1000;
  const result = await readState(state, SECRET, anHourLater);
  expect(result.ok).toBe(false);
});

test("nonsense in the state parameter is refused rather than parsed", async () => {
  for (const rubbish of ["", "a.b", "a.b.c.d", "....."]) {
    expect((await readState(rubbish, SECRET)).ok).toBe(false);
  }
});

test("no configured secret refuses everything, rather than signing with nothing", async () => {
  const state = await createState(USER, SECRET);
  expect(await readState(state, "")).toEqual({
    ok: false,
    reason: "No signing secret is configured",
  });
});
