// Shared HTTP helpers for the Edge Functions.

export const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
  });
}

export function preflight(): Response {
  return new Response(null, { status: 204, headers: CORS_HEADERS });
}

/** Wrap a handler so preflight and thrown errors are handled consistently. */
export function serveJson(handler: (request: Request) => Promise<Response>) {
  return async (request: Request): Promise<Response> => {
    if (request.method === "OPTIONS") return preflight();
    try {
      return await handler(request);
    } catch (error) {
      console.error(error);
      const message = error instanceof Error ? error.message : String(error);
      return json({ error: message }, 400);
    }
  };
}
