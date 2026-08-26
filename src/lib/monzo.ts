// Client-side entry points for the Monzo Edge Functions.
//
// The bank connection itself is never readable from here — bank_connections
// holds live access tokens and is granted to service_role alone — so anything
// about the connection has to come back through one of these calls. The
// payments it produces are ordinary rows in bank_transactions, which the app
// reads directly under RLS; see use-bank.ts.
import { invoke } from "@/lib/functions";

export type MonzoStatus = {
  /** Whether the server has Monzo API credentials at all. */
  configured: boolean;
  connected: boolean;
  accountName: string | null;
  accountType: string | null;
  /** Monzo serves no transactions until access is approved in the phone app. */
  approved: boolean;
  /** The developer API is documented for personal accounts; this one is not one. */
  businessAccount: boolean;
  lastSyncedAt: string | null;
  lastSyncError: string | null;
  webhookRegistered: boolean;
};

export function getMonzoStatus() {
  return invoke<MonzoStatus>("monzo-connect", { action: "status" });
}

/** Returns the Monzo page to send the account holder to. */
export function startMonzoConnection() {
  return invoke<{ url: string }>("monzo-connect", { action: "start" });
}

export type MonzoAccountOption = {
  id: string;
  name: string;
  type: string | null;
  selected: boolean;
};

export function listMonzoAccounts() {
  return invoke<{ accounts: MonzoAccountOption[] }>("monzo-connect", { action: "accounts" });
}

export function selectMonzoAccount(accountId: string) {
  return invoke<{ ok: true; accountName: string }>("monzo-connect", {
    action: "select",
    accountId,
  });
}

export function disconnectMonzo() {
  return invoke<{ ok: true }>("monzo-connect", { action: "disconnect" });
}

export type MonzoSyncResult = {
  added: number;
  awaitingApproval: boolean;
  reason?: string;
};

export function syncMonzo() {
  return invoke<MonzoSyncResult>("monzo-sync", {});
}
