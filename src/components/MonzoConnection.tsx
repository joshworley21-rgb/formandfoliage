// The Monzo bank feed, as Settings sees it.
//
// Connecting Monzo has more states than most switches: the server may have no
// API credentials, the account holder may not have approved access in the Monzo
// app yet, the webhook may have failed to register, and the account may be a
// business one the developer API does not serve. Each of those is a different
// sentence here, because "not working" on its own gives nobody anything to do.
import { useState } from "react";
import { format, parseISO } from "date-fns";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  useConnectMonzo,
  useDisconnectMonzo,
  useMonzoAccounts,
  useMonzoStatus,
  useSelectMonzoAccount,
  useSyncMonzo,
} from "@/hooks/use-bank";
import { followUrl, openBlankTab } from "@/lib/native";

export function MonzoConnection() {
  const { data: status, isLoading } = useMonzoStatus();
  const connect = useConnectMonzo();
  const sync = useSyncMonzo();
  const disconnect = useDisconnectMonzo();
  const selectAccount = useSelectMonzoAccount();
  const [switching, setSwitching] = useState(false);
  const { data: accountList } = useMonzoAccounts(switching);

  async function startConnecting() {
    // Claimed before anything is awaited — see openBlankTab.
    const tab = openBlankTab();
    try {
      const url = await connect.mutateAsync();
      await followUrl(url, tab);
    } catch (error) {
      tab?.close();
      toast.error(error instanceof Error ? error.message : "Could not start the Monzo connection");
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Monzo bank feed</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Checking…</p>
        ) : !status?.configured ? (
          <p className="text-sm text-muted-foreground">
            Monzo is not set up on the server yet. Register a confidential OAuth client at
            developers.monzo.com, then add <code>MONZO_CLIENT_ID</code> and{" "}
            <code>MONZO_CLIENT_SECRET</code> to the Edge Function secrets. The steps are in
            supabase/functions/README.md.
          </p>
        ) : !status.connected ? (
          <>
            <p className="text-sm text-muted-foreground">
              Connect your Monzo account and payments into it appear here to be matched against
              invoices. Nothing is ever marked paid without you confirming it, and the CRM can only
              read — it cannot move money.
            </p>
            <Button type="button" onClick={startConnecting} disabled={connect.isPending}>
              {connect.isPending ? "Opening Monzo…" : "Connect Monzo"}
            </Button>
          </>
        ) : (
          <>
            <p className="text-sm">
              Connected to <strong>{status.accountName}</strong>.
              {status.lastSyncedAt ? (
                <span className="text-muted-foreground">
                  {" "}
                  Last checked {format(parseISO(status.lastSyncedAt), "d MMM 'at' HH:mm")}.
                </span>
              ) : null}
            </p>

            {/* The one thing that stops a connection working while looking fine. */}
            {!status.approved ? (
              <p className="rounded-lg border border-primary/40 bg-primary/5 p-3 text-sm">
                <strong>Open your Monzo app and approve access.</strong> Monzo holds back every
                transaction until you do — the connection is made, but it cannot see anything yet.
                Then tap Sync now.
              </p>
            ) : null}

            {status.businessAccount ? (
              <p className="text-xs text-muted-foreground">
                This is a business account. Monzo documents its developer API for personal accounts,
                so if payments never appear, that is why rather than anything here being broken —
                invoices can still be marked paid by hand.
              </p>
            ) : null}

            {status.approved && !status.webhookRegistered ? (
              <p className="text-xs text-muted-foreground">
                Monzo is not pushing payments to the CRM, so they will show up when a sync runs
                rather than the moment they land.
              </p>
            ) : null}

            {status.lastSyncError ? (
              <p className="text-xs text-destructive">Last sync said: {status.lastSyncError}</p>
            ) : null}

            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="secondary"
                disabled={sync.isPending}
                onClick={async () => {
                  try {
                    const result = await sync.mutateAsync();
                    if (result.awaitingApproval) {
                      toast.warning(result.reason ?? "Approve access in your Monzo app first");
                    } else if (result.added > 0) {
                      toast.success(
                        `${result.added} new ${result.added === 1 ? "payment" : "payments"} to review`,
                      );
                    } else {
                      toast.success("No new payments");
                    }
                  } catch (error) {
                    toast.error(error instanceof Error ? error.message : "Sync failed");
                  }
                }}
              >
                {sync.isPending ? "Checking…" : "Sync now"}
              </Button>

              <Button type="button" variant="ghost" onClick={() => setSwitching((on) => !on)}>
                {switching ? "Cancel" : "Change account"}
              </Button>

              <Button
                type="button"
                variant="ghost"
                className="text-destructive hover:text-destructive"
                disabled={disconnect.isPending}
                onClick={async () => {
                  if (
                    !confirm("Disconnect Monzo? Payments already matched stay on their invoices.")
                  )
                    return;
                  try {
                    await disconnect.mutateAsync();
                    setSwitching(false);
                    toast.success("Monzo disconnected");
                  } catch (error) {
                    toast.error(error instanceof Error ? error.message : "Could not disconnect");
                  }
                }}
              >
                Disconnect
              </Button>
            </div>

            {/* Monzo hands back every account on the login; the first was picked
                for you, which is not always the one the business banks with. */}
            {switching ? (
              <Select
                value={accountList?.accounts.find((account) => account.selected)?.id ?? ""}
                onValueChange={async (accountId) => {
                  try {
                    const result = await selectAccount.mutateAsync(accountId);
                    setSwitching(false);
                    toast.success(`Now watching ${result.accountName}`);
                  } catch (error) {
                    toast.error(
                      error instanceof Error ? error.message : "Could not switch account",
                    );
                  }
                }}
              >
                <SelectTrigger className="w-full sm:w-80">
                  <SelectValue placeholder="Loading your accounts…" />
                </SelectTrigger>
                <SelectContent>
                  {(accountList?.accounts ?? []).map((account) => (
                    <SelectItem key={account.id} value={account.id}>
                      {account.name}
                      {account.type ? ` · ${account.type}` : ""}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}
