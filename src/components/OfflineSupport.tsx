import { useEffect, useState, useSyncExternalStore } from "react";
import { CloudUpload, RefreshCw, WifiOff } from "lucide-react";
import { toast } from "sonner";
import type { QueryClient } from "@tanstack/react-query";
import { createSyncStoragePersister } from "@tanstack/query-sync-storage-persister";
import { persistQueryClient } from "@tanstack/react-query-persist-client";
import { registerOfflineSupport } from "@/lib/register-sw";
import { flushQueue, getQueueSnapshot, startAutoSync, subscribeQueue } from "@/lib/offline-queue";

const emptySnapshot = {
  pending: [] as ReturnType<typeof getQueueSnapshot>["pending"],
  syncing: false,
  lastSyncedAt: null,
  lastError: null,
};

export function OfflineSupport({ queryClient }: { queryClient: QueryClient }) {
  const [offline, setOffline] = useState(false);

  const queue = useSyncExternalStore(subscribeQueue, getQueueSnapshot, () => emptySnapshot);

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

    const stopSync = startAutoSync((count) => {
      void queryClient.invalidateQueries();
      toast.success(count === 1 ? "1 saved change synced" : `${count} saved changes synced`);
    });

    const update = () => setOffline(!navigator.onLine);
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);

    return () => {
      unsubscribe();
      stopSync();
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, [queryClient]);

  useEffect(() => {
    if (queue.lastError) toast.error(queue.lastError);
  }, [queue.lastError]);

  const pending = queue.pending.length;
  if (!offline && pending === 0 && !queue.syncing) return null;

  return (
    <div className="fixed inset-x-0 top-0 z-50 flex flex-wrap items-center justify-center gap-x-3 gap-y-1 bg-muted px-4 py-1.5 text-xs font-medium text-muted-foreground">
      {offline ? (
        <span className="flex items-center gap-2">
          <WifiOff className="size-3.5" />
          Offline — showing your last saved data
        </span>
      ) : null}

      {queue.syncing ? (
        <span className="flex items-center gap-2">
          <RefreshCw className="size-3.5 animate-spin" />
          Syncing your changes…
        </span>
      ) : pending > 0 ? (
        <span className="flex items-center gap-2">
          <CloudUpload className="size-3.5" />
          {pending === 1 ? "1 change" : `${pending} changes`} waiting to sync
          {offline ? " — they'll upload automatically" : null}
          {!offline ? (
            <button
              type="button"
              onClick={async () => {
                const count = await flushQueue();
                if (count > 0) void queryClient.invalidateQueries();
              }}
              className="underline underline-offset-2"
            >
              Sync now
            </button>
          ) : null}
        </span>
      ) : null}
    </div>
  );
}
