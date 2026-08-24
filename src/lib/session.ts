import { supabase } from "@/integrations/supabase/client";

/**
 * Who is signed in, according to the session stored on this device.
 *
 * Deliberately not auth.getUser(), which asks the server every time. In a
 * garden with two bars that call fails, and the app took that to mean signed
 * out: it bounced you to the sign-in screen on the way into any page, and
 * refused to record a visit with "Not signed in" — while a perfectly good
 * session sat in storage the whole time.
 *
 * The session is the right answer here. supabase-js renews the token in the
 * background when there is signal, and nothing is trusted on the strength of
 * this: every query is checked against row-level security on the server, so a
 * token that has genuinely expired is refused there rather than here.
 */
export async function currentUser() {
  const { data } = await supabase.auth.getSession();
  return data.session?.user ?? null;
}

/** The signed-in user's id, or a thrown error if there is no session at all. */
export async function requireUserId(): Promise<string> {
  const user = await currentUser();
  if (!user) throw new Error("Not signed in");
  return user.id;
}
