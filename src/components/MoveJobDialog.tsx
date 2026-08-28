// Moving one job to a different day.
//
// This was always possible through the edit dialog's date field, which is
// four taps and a keyboard for the commonest thing that happens to a round:
// it rains, and today goes to tomorrow. The reasons a job moves are nearly
// always the same shape and nearly always small, so the usual answers are
// buttons and anything else is still a date picker.
//
// The time of day is carried over rather than reset. A job moved to tomorrow
// is the same job on a different day, and re-entering 09:00 every time would
// be the whole saving given back.
import { addDays, format, parseISO } from "date-fns";
import { CalendarClock } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useSaveJob } from "@/hooks/use-crm";
import type { ScheduledJob } from "@/lib/crm";
import { atTime } from "@/lib/series";

export function MoveJobDialog({
  job,
  clientName,
  onClose,
}: {
  job: ScheduledJob | null;
  clientName: string;
  onClose: () => void;
}) {
  const saveJob = useSaveJob();
  const [date, setDate] = useState("");
  const [busy, setBusy] = useState(false);

  const starts = job ? parseISO(job.starts_at) : null;
  const time = starts ? format(starts, "HH:mm") : "09:00";

  useEffect(() => {
    if (starts) setDate(format(starts, "yyyy-MM-dd"));
    // Only when the job being moved changes — not on every re-parse.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job?.id]);

  async function moveTo(day: Date) {
    if (!job) return;
    setBusy(true);
    try {
      await saveJob.mutateAsync({
        id: job.id,
        client_id: job.client_id,
        starts_at: atTime(day, time).toISOString(),
      });
      toast.success(`Moved to ${format(day, "EEE d MMM")}`);
      onClose();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not move the job");
    } finally {
      setBusy(false);
    }
  }

  // Offered relative to the job's own day, not to today: pushing next
  // Tuesday's job back a week means the Tuesday after, whenever you happen
  // to be looking at it from.
  const presets = starts
    ? [
        { label: "Next day", day: addDays(starts, 1) },
        { label: "In 2 days", day: addDays(starts, 2) },
        { label: "Next week", day: addDays(starts, 7) },
      ]
    : [];

  return (
    <Dialog open={Boolean(job)} onOpenChange={(open) => (open ? null : onClose())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <CalendarClock className="size-4" /> Move {clientName}&rsquo;s job
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <p className="rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            Currently {starts ? format(starts, "EEE d MMM") : ""} at {time}. The time stays the same
            wherever it lands
            {job?.series_id ? ", and only this visit moves — the rest of the run stays put" : ""}.
          </p>

          <div className="grid grid-cols-3 gap-2">
            {presets.map((preset) => (
              <Button
                key={preset.label}
                variant="outline"
                className="h-auto flex-col gap-0.5 py-3"
                disabled={busy}
                onClick={() => moveTo(preset.day)}
              >
                <span className="text-xs font-medium">{preset.label}</span>
                <span className="figure text-[0.7rem] text-muted-foreground">
                  {format(preset.day, "EEE d MMM")}
                </span>
              </Button>
            ))}
          </div>

          <div className="space-y-1.5 border-t border-border pt-4">
            <Label htmlFor="movedate">Or pick a day</Label>
            <div className="flex items-center gap-2">
              <Input
                id="movedate"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
              <Button
                className="h-10 shrink-0"
                disabled={busy || !date}
                onClick={() => {
                  // Parsed as local midnight, then given the job's own time.
                  const picked = new Date(`${date}T00:00`);
                  if (Number.isNaN(picked.getTime())) {
                    toast.error("Pick a valid day");
                    return;
                  }
                  void moveTo(picked);
                }}
              >
                Move
              </Button>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
