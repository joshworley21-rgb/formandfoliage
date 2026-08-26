// Keeping the query cache across app launches.
//
// React Query holds its cache in memory, so closing the app threw it away and
// every cold start began from nothing: a blank dashboard, six queries in
// flight, and a wait before any number appeared. The router already asked for a
// week-long gcTime, which only ever meant "until you close the app" — this is
// the missing half of that intention.
//
// With it, the app opens showing what it last knew and refreshes underneath.
// Stale figures for a second beat no figures for five, and every screen here is
// something you were looking at recently anyway.
import { createSyncStoragePersister } from "@tanstack/query-sync-storage-persister";
import type { PersistedClient, Persister } from "@tanstack/react-query-persist-client";

/**
 * Bumped when a change would make yesterday's cache wrong rather than merely
 * old — a column that moves, a query key that changes shape. Restoring a cache
 * the code no longer understands is worse than a slow start.
 */
const CACHE_VERSION = "v1";

export const PERSIST_KEY = `jw-crm-query-cache-${CACHE_VERSION}`;

/** A week, matching the gcTime the router already sets. */
export const MAX_CACHE_AGE = 1000 * 60 * 60 * 24 * 7;

/**
 * Remembers nothing, forgets nothing, never throws.
 *
 * Used on the server, where there is no storage, and on any device that refuses
 * it. Returning this rather than null keeps one provider component in the tree
 * either way: swapping the provider between the server render and the browser
 * one would be a hydration mismatch, and a blank screen is a poor trade for a
 * faster start.
 */
const noopPersister: Persister = {
  persistClient: async () => {},
  restoreClient: async () => undefined,
  removeClient: async () => {},
};

/**
 * localStorage can be missing or full — private windows, a WebView with storage
 * disabled, a quota that a few thousand visits could reach. None of that is
 * worth a broken app, so a persister that cannot write is simply one that does
 * nothing, and the app behaves exactly as it did before.
 */
export function createPersister(): Persister {
  if (typeof window === "undefined") return noopPersister;
  try {
    const probe = "__jw_probe__";
    window.localStorage.setItem(probe, "1");
    window.localStorage.removeItem(probe);
  } catch {
    return noopPersister;
  }
  return createSyncStoragePersister({
    storage: window.localStorage,
    key: PERSIST_KEY,
    // A write per query result would be a JSON stringify of the whole cache on
    // every keystroke-driven refetch. One second of quiet is imperceptible and
    // turns a burst of writes into one.
    throttleTime: 1000,
  });
}

/**
 * Wipes the saved cache. Called on sign-out: the live cache going away is not
 * enough while a copy of the same data is still sitting in storage.
 */
export function clearPersistedCache(): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(PERSIST_KEY);
  } catch {
    // Nothing to remove if storage is refusing us in the first place.
  }
}

/**
 * What is worth keeping. Failed and still-loading queries are not: restoring an
 * error from last Tuesday would show it as though it had just happened.
 */
export function shouldPersist(query: { state: { status: string } }): boolean {
  return query.state.status === "success";
}

/**
 * Everything the app writes is the user's own data, already on their own
 * device. The one thing that must never be written here is anything that would
 * let someone read it without signing in — auth tokens live in Supabase's own
 * storage, not in this cache, and no query in the app returns one.
 */
export function persistOptions(persister: Persister) {
  return {
    persister,
    maxAge: MAX_CACHE_AGE,
    buster: CACHE_VERSION,
    dehydrateOptions: { shouldDehydrateQuery: shouldPersist },
  };
}

export type { PersistedClient };
