// Reports whether email sending is configured, for the Settings screen.
// Ported from the getResendStatus server function.
import { requireUser } from "../_shared/supabase.ts";
import { json, serveJson } from "../_shared/http.ts";
import { verifyResendKey } from "../_shared/email.ts";

Deno.serve(
  serveJson(async (request) => {
    await requireUser(request);
    return json({ connected: await verifyResendKey() });
  }),
);
