import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { currentUser } from "@/lib/session";

export const Route = createFileRoute("/_authenticated")({
  ssr: false,
  beforeLoad: async () => {
    // Reads the stored session rather than asking the server. Losing signal is
    // not the same as being signed out, and this guard used to treat it that
    // way — one failed request on the way into a page and you were back at the
    // sign-in screen with no way through until the signal returned.
    const user = await currentUser();
    if (!user) throw redirect({ to: "/auth" });
    return { user };
  },
  component: () => <Outlet />,
});
