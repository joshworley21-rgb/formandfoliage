import { supabase } from "@/integrations/supabase/client";

/**
 * A tiny durable write queue. When the device is offline (or a write fails
 * because the network dropped mid-request) the change is stored in
 * localStorage and replayed automatically as soon as connectivity returns.
 */

export type QueuedOp =
  | { kind: "insert"; table: string; payload: Record<string, unknown> }
  | {
      kind: "update";
      table: string;
      payload: Record<string, unknown>;
      match: Record<string, unknown>;
    }
  | {
      kind: "upsert";
      table: string;
      payload: Record<string, unknown>;
      onConflict?: string;
    }
  | { kind: "delete"; table: string; match: Record<string, unknown> }
  | { kind: "rpc"; fn: string; args: Record<string, unknown> };

export type QueuedChange = {
  id: string;
  label: string;
  createdAt: string;
  attempts: number;
  ops: QueuedOp[];
};

const STORAGE_KEY = "jw-crm-pending-writes";

type QueueState = {
  pending: QueuedChange[];
  syncing: boolean;
  lastSyncedAt: string | null;
  lastError: string | null;
};

let state: QueueState = {
  pending: [],
  syncing: false,
  lastSyncedAt: null,
  lastError: null,
};

const listeners = new Set<() => void>();

function emit() {
  for (const l of listeners) l();
}

function persist() {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state.pending));
  } catch {
    /* storage full or unavailable — keep the in-memory queue */
  }
}

function load() {
  if (typeof window === "undefined") return;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    state = { ...state, pending: raw ? (JSON.parse(raw) as QueuedChange[]) : [] };
  } catch {
    state = { ...state, pending: [] };
  }
}

export function subscribeQueue(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getQueueSnapshot(): QueueState {
  return state;
}

/* ---------------- execution ---------------- */

// The generated Supabase types are table-literal specific; the queue is generic
// by design, so we go through a loosely typed handle here.
const db = supabase as unknown as {
  from: (table: string) => any;
  rpc: (fn: string, args: Record<string, unknown>) => Promise<{ error: unknown }>;
};

async function runOp(op: QueuedOp) {
  let error: unknown = null;
  if (op.kind === "insert") {
    ({ error } = await db.from(op.table).insert(op.payload));
  } else if (op.kind === "update") {
    let q = db.from(op.table).update(op.payload);
    for (const [k, v] of Object.entries(op.match)) q = q.eq(k, v);
    ({ error } = await q);
  } else if (op.kind === "upsert") {
    ({ error } = await db
      .from(op.table)
      .upsert(op.payload, op.onConflict ? { onConflict: op.onConflict } : undefined));
  } else if (op.kind === "delete") {
    let q = db.from(op.table).delete();
    for (const [k, v] of Object.entries(op.match)) q = q.eq(k, v);
    ({ error } = await q);
  } else {
    ({ error } = await db.rpc(op.fn, op.args));
  }
  if (error) throw error;
}

export async function runOps(ops: QueuedOp[]) {
  for (const op of ops) await runOp(op);
}

function isOffline() {
  return typeof navigator !== "undefined" && navigator.onLine === false;
}

function looksLikeNetworkError(err: unknown) {
  const message = err instanceof Error ? err.message : String(err ?? "");
  return /failed to fetch|network|load failed|timeout|offline/i.test(message);
}

function enqueue(label: string, ops: QueuedOp[]) {
  state = {
    ...state,
    pending: [
      ...state.pending,
      {
        id:
          typeof crypto !== "undefined" && "randomUUID" in crypto
            ? crypto.randomUUID()
            : String(Date.now() + Math.random()),
        label,
        createdAt: new Date().toISOString(),
        attempts: 0,
        ops,
      },
    ],
  };
  persist();
  emit();
}

/**
 * Run a write immediately when online. If the device is offline — or the
 * request fails for network reasons — the change is queued and replayed later.
 * Returns true when it was applied straight away.
 */
export async function runOrQueue(label: string, ops: QueuedOp[]): Promise<boolean> {
  if (isOffline()) {
    enqueue(label, ops);
    return false;
  }
  try {
    await runOps(ops);
    return true;
  } catch (err) {
    if (looksLikeNetworkError(err) || isOffline()) {
      enqueue(label, ops);
      return false;
    }
    throw err;
  }
}

export function pendingCount() {
  return state.pending.length;
}

export function discardPending() {
  state = { ...state, pending: [], lastError: null };
  persist();
  emit();
}

let flushing: Promise<number> | null = null;

/** Replay every queued change. Resolves with the number successfully synced. */
export function flushQueue(): Promise<number> {
  if (flushing) return flushing;
  if (state.pending.length === 0 || isOffline()) return Promise.resolve(0);

  flushing = (async () => {
    state = { ...state, syncing: true, lastError: null };
    emit();
    let synced = 0;
    try {
      // Preserve order: stop at the first item that can't be applied yet.
      while (state.pending.length > 0 && !isOffline()) {
        const [next, ...rest] = state.pending;
        try {
          await runOps(next.ops);
          state = { ...state, pending: rest };
          synced += 1;
        } catch (err) {
          if (looksLikeNetworkError(err) || isOffline()) break;
          // Permanent failure (validation, permissions): drop it so the queue
          // can't wedge, but surface why.
          state = {
            ...state,
            pending: rest,
            lastError: `${next.label}: ${err instanceof Error ? err.message : "could not be saved"}`,
          };
        }
        persist();
        emit();
      }
    } finally {
      state = {
        ...state,
        syncing: false,
        lastSyncedAt: synced > 0 ? new Date().toISOString() : state.lastSyncedAt,
      };
      persist();
      emit();
      flushing = null;
    }
    return synced;
  })();

  return flushing;
}

let started = false;

/** Start automatic syncing: on reconnect, on tab focus, and on a slow poll. */
export function startAutoSync(onSynced: (count: number) => void) {
  load();
  emit();
  if (started) return () => {};
  started = true;

  const attempt = async () => {
    const count = await flushQueue();
    if (count > 0) onSynced(count);
  };

  const onOnline = () => void attempt();
  const onFocus = () => {
    if (!isOffline()) void attempt();
  };
  const timer = window.setInterval(() => {
    if (!isOffline() && state.pending.length > 0) void attempt();
  }, 30_000);

  window.addEventListener("online", onOnline);
  window.addEventListener("focus", onFocus);
  document.addEventListener("visibilitychange", onFocus);
  void attempt();

  return () => {
    started = false;
    window.clearInterval(timer);
    window.removeEventListener("online", onOnline);
    window.removeEventListener("focus", onFocus);
    document.removeEventListener("visibilitychange", onFocus);
  };
}
