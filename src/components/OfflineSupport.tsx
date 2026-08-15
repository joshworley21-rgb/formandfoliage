import { useEffect, useState } from "react";
import { WifiOff } from "lucide-react";
import type { QueryClient } from "@tanstack/react-query";
import { createSyncStoragePersister } from "@tanstack/query-sync-storage-persister";
import { persistQueryClient } from "@tanstack/react-query-persist-client";
import { registerOfflineSupport } from "@/lib/register-sw";

export function OfflineSupport({ queryClient }: { queryClient: QueryClient }) {
  const [offline, setOffline] = useState(false);

  useEffect(() => {
    registerOfflineSupport();

    const persister = createSyncStoragePersister({
      storage: window.localStorage,
      key: "jw-crm-cache",
      throttleTime: 1000,
    });

    const [unsubscribe] = persistQueryClient({
      queryClient,
      persister,
      maxAge: 1000 * 60 * 60 * 24 * 7,
      buster: "v1",
    });

    const update = () => setOffline(!navigator.onLine);
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);

    return () => {
      unsubscribe();
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, [queryClient]);

  if (!offline) return null;

  return (
    <div className="fixed inset-x-0 top-0 z-50 flex items-center justify-center gap-2 bg-muted px-4 py-1.5 text-xs font-medium text-muted-foreground">
      <WifiOff className="size-3.5" />
      Offline — showing your last saved data
    </div>
  );
}
