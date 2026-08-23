import { createStart, createMiddleware } from "@tanstack/react-start";

import { renderErrorPage } from "./lib/error-page";

const errorMiddleware = createMiddleware().server(async ({ next }) => {
  try {
    return await next();
  } catch (error) {
    if (error != null && typeof error === "object" && "statusCode" in error) {
      throw error;
    }
    console.error(error);
    return new Response(renderErrorPage(), {
      status: 500,
      headers: { "content-type": "text/html; charset=utf-8" },
    });
  }
});

// There are no server functions left — invoicing and email now run as Supabase
// Edge Functions — so the CSRF and auth-attaching middleware they needed is
// gone with them. This just keeps SSR errors rendering a real page.
export const startInstance = createStart(() => ({
  requestMiddleware: [errorMiddleware],
}));
