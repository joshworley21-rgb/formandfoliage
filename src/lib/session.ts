import { getAuthStorageKey, supabase } from "@/integrations/supabase/client";

type StoredUser = { id: string; email?: string | null };

/**
 * The signed-in user according to what is written on this device, without
 * asking the network anything at all.
 *
 * This is the one that decides which screen the app opens on, and it has to be
 * this and not auth.getSession(), which is not the offline-safe read it looks
 * like. A Supabase access token lasts an hour. Every time the app is opened
 * after that, getSession() must renew it first — and when that renewal cannot
 * reach the server it hands back "no session", even though the refresh token
 * is sitting in storage perfectly intact. (It says so itself: it only deletes
 * the stored session for a real rejection, never for a failed connection.)
 *
 * So an app opened in a garden with no signal was told nobody was signed in,
 * and offered a sign-in form that cannot work without signal either. The way
 * out of that screen needed the very thing that was missing.
 *
 * Reading the stored session instead means a device that has been signed in
 * stays signed in until it is signed out. Nothing is trusted on the strength
 * of it: every query is checked against row-level security on the server, so a
 * token that really has expired is refused there, where it can be — and where
 * supabase-js will quietly renew it the moment there is signal again.
 */
export function storedUser(): StoredUser | null {
  if (typeof window === "undefined") return null;

  try {
    const raw = window.localStorage.getItem(getAuthStorageKey());
    if (!raw) return null;

    const session = JSON.parse(raw) as { user?: StoredUser } | null;
    const user = session?.user;
    return user?.id ? user : null;
  } catch {
    // Unreadable or not JSON: treat it as nobody, and let the sign-in screen
    // write a good one over the top.
    return null;
  }
}

/**
 * Who is signed in on this device.
 *
 * Deliberately not auth.getUser(), which asks the server every time. In a
 * garden with two bars that call fails, and the app took that to mean signed
 * out: it bounced you to the sign-in screen on the way into any page, and
 * refused to record a visit with "Not signed in" — while a perfectly good
 * session sat in storage the whole time.
 */
export async function currentUser(): Promise<StoredUser | null> {
  const stored = storedUser();
  if (stored) return stored;

  // Nothing on disk. Ask supabase-js anyway: a sign-in finishing in another
  // tab, or a session still being written, would not be on disk yet.
  const { data } = await supabase.auth.getSession();
  return data.session?.user ?? null;
}

/** The signed-in user's id, or a thrown error if there is no session at all. */
export async function requireUserId(): Promise<string> {
  const user = await currentUser();
  if (!user) throw new Error("Not signed in");
  return user.id;
}

/**
 * Sign out, and mean it.
 *
 * supabase-js starts its own signOut by loading the session, which renews an
 * expired token first — so with no signal that renewal fails and it returns
 * early, leaving the session on disk. Harmless when the app decided who was
 * signed in by asking the network; not harmless now that the stored session is
 * what opens the app.
 *
 * So the local key is cleared whatever happened. Telling the server is the
 * best-effort half: a refresh token that outlives this is one the next sign-in
 * replaces anyway.
 */
export async function signOutHere(): Promise<void> {
  try {
    await supabase.auth.signOut();
  } catch {
    // Offline, or the server refused. The device still signs out.
  }

  try {
    window.localStorage.removeItem(getAuthStorageKey());
  } catch {
    // Storage unavailable; nothing more that can be done here.
  }
}
