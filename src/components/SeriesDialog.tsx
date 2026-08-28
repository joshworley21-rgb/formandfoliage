// Editing a repeating run of jobs, rather than one visit out of it.
//
// Booking with Repeats ticked creates every job up front — twelve by default,
// sixty at most — and tags them with a shared series_id. Nothing on the server
// ever adds more. So there were two things you could not do: change the round
// (its time, its rhythm) without editing a dozen jobs by hand, and carry it on
// once the last one had been worked. This screen is both.
//
// Two separate actions, deliberately not merged into one button: changing what
// is already booked and adding to it are different decisions, and the count on
// each says exactly how many jobs it will write.
import { format, parseISO } from "date-fns";
import { CalendarPlus, Repeat } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { useExtendSeries, useSaveSeries, useSeries, useSeriesJobs } from "@/hooks/use-crm";
import { FREQUENCY_LABELS, type Frequency, type ScheduledJob } from "@/lib/crm";
import { editableVisits, nextVisits, retimedVisits } from "@/lib/series";

/** "09:00" from a timestamp, and back again as minutes for the arithmetic. */
const toMinutes = (t: string) => {
  const [h, m] = t.split(":").map(Number);
  return (h || 0) * 60 + (m || 0);
};
const fromMinutes = (mins: number) => {
  const clamped = Math.max(0, Math.min(mins, 23 * 60 + 59));
  return `${String(Math.floor(clamped / 60)).padStart(2, "0")}:${String(clamped % 60).padStart(2, "0")}`;
};

export function SeriesDialog({
  job,
  clientName,
  onClose,
}: {
  /** Any one job out of the run — it carries the series id and the defaults. */
  job: ScheduledJob | null;
  clientName: string;
  onClose: () => void;
}) {
  const seriesId = job?.series_id ?? null;
  const { data: jobs = [], isLoading } = useSeriesJobs(seriesId);
  const { data: series = [] } = useSeries();
  const saveSeries = useSaveSeries();
  const extendSeries = useExtendSeries();

  const [time, setTime] = useState("09:00");
  const [endTime, setEndTime] = useState("10:00");
  const [frequency, setFrequency] = useState<Frequency>("fortnightly");
  const [notes, setNotes] = useState("");
  const [more, setMore] = useState("6");
  const [busy, setBusy] = useState(false);

  // What the run currently looks like, taken from the job that was tapped. The
  // series row carries the same values, but this saves waiting for it and is
  // what the person is looking at on screen.
  const [initialFrequency, setInitialFrequency] = useState<Frequency>("fortnightly");

  useEffect(() => {
    if (!job) return;
    const starts = parseISO(job.starts_at);
    setTime(format(starts, "HH:mm"));
    setEndTime(fromMinutes(toMinutes(format(starts, "HH:mm")) + (job.duration_minutes ?? 60)));
    setNotes(job.notes ?? "");
    setMore("6");
  }, [job]);

  // How often the run repeats is not on a job, only on the run itself, so it
  // is read from the series row rather than inferred from the gap between two
  // visits — one visit dragged to a different day would make that inference
  // wrong, and it is this value that decides whether saving re-spaces anything.
  const current = series.find((s) => s.id === seriesId)?.frequency as Frequency | undefined;

  useEffect(() => {
    if (!current) return;
    setFrequency(current);
    setInitialFrequency(current);
  }, [current]);

  // Read when the run is loaded rather than on every render, so the list the
  // button counts is the same one the save writes.
  const remaining = useMemo(() => editableVisits(jobs, new Date()), [jobs]);
  const last = jobs.at(-1);
  const duration = Math.max(0, toMinutes(endTime) - toMinutes(time));
  const respace = frequency !== initialFrequency;
  const extra = Math.max(0, Math.min(Number(more) || 0, 60));

  async function save() {
    if (!seriesId) return;
    if (duration <= 0) {
      toast.error("Finish time must be after the start time");
      return;
    }
    if (remaining.length === 0) {
      toast.error("Every visit in this run has been worked — add more below instead");
      return;
    }
    setBusy(true);
    try {
      await saveSeries.mutateAsync({
        seriesId,
        visits: retimedVisits(remaining, { startTime: time, frequency, respace }),
        durationMinutes: duration,
        notes: notes || null,
        frequency,
        startTime: time,
      });
      toast.success(`${remaining.length} visit${remaining.length === 1 ? "" : "s"} updated`);
      onClose();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not update the series");
    } finally {
      setBusy(false);
    }
  }

  async function extend() {
    if (!seriesId || !job) return;
    if (duration <= 0) {
      toast.error("Finish time must be after the start time");
      return;
    }
    if (extra === 0) {
      toast.error("Choose how many visits to add");
      return;
    }
    if (respace) {
      // Otherwise the new visits get the new rhythm while the booked ones keep
      // the old, and the run comes out spaced two different ways.
      toast.error("Save the change to how often first, then add more visits");
      return;
    }
    setBusy(true);
    try {
      const startsAt = nextVisits(jobs, { startTime: time, frequency, count: extra });
      await extendSeries.mutateAsync({
        seriesId,
        clientId: job.client_id,
        startsAt,
        durationMinutes: duration,
        notes: notes || null,
      });
      toast.success(`${extra} more visit${extra === 1 ? "" : "s"} booked`);
      onClose();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "Could not extend the series");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={Boolean(job)} onOpenChange={(open) => (open ? null : onClose())}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Repeat className="size-4" /> Repeating job — {clientName}
          </DialogTitle>
        </DialogHeader>

        {isLoading ? (
          <p className="py-6 text-sm text-muted-foreground">Loading the whole run…</p>
        ) : (
          <div className="space-y-4">
            <p className="rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
              {jobs.length} visit{jobs.length === 1 ? "" : "s"} in this run
              {last ? `, ending ${format(parseISO(last.starts_at), "d MMM yyyy")}` : ""}.{" "}
              {remaining.length} still to come.
            </p>

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="stime">Start</Label>
                <Input
                  id="stime"
                  type="time"
                  value={time}
                  onChange={(e) => setTime(e.target.value)}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="send">Finish</Label>
                <Input
                  id="send"
                  type="time"
                  value={endTime}
                  onChange={(e) => setEndTime(e.target.value)}
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <Label>How often</Label>
              <Select value={frequency} onValueChange={(v) => setFrequency(v as Frequency)}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {Object.entries(FREQUENCY_LABELS).map(([value, label]) => (
                    <SelectItem key={value} value={value}>
                      {label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {/*
                Changing the rhythm moves days, not just clock times, so it says
                so before you commit rather than after.
              */}
              {respace && remaining.length > 0 ? (
                <p className="rounded-lg border border-border bg-secondary/60 px-3 py-2 text-xs">
                  The {remaining.length} visit{remaining.length === 1 ? "" : "s"} still to come will
                  be re-spaced {FREQUENCY_LABELS[frequency].toLowerCase()} from{" "}
                  <strong>{format(parseISO(remaining[0]!.starts_at), "EEE d MMM")}</strong>, which
                  keeps its day.
                </p>
              ) : null}
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="snotes">Notes</Label>
              <Textarea
                id="snotes"
                rows={2}
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="Mow, edge, hedge front"
              />
            </div>

            <Button
              className="h-11 w-full"
              disabled={busy || remaining.length === 0}
              onClick={save}
            >
              Save to the {remaining.length} visit{remaining.length === 1 ? "" : "s"} still to come
            </Button>
            <p className="text-xs text-muted-foreground">
              Visits already done, and anything in the past, are left exactly as they are.
            </p>

            <div className="space-y-2 border-t border-border pt-4">
              <Label htmlFor="smore">Add more visits</Label>
              <p className="text-xs text-muted-foreground">
                A repeating job is a fixed number of visits, not a standing rule — this one stops
                after {last ? format(parseISO(last.starts_at), "d MMM yyyy") : "its last visit"}.
              </p>
              <div className="flex items-center gap-2">
                <Input
                  id="smore"
                  type="number"
                  min="1"
                  max="60"
                  className="w-24"
                  value={more}
                  onChange={(e) => setMore(e.target.value)}
                />
                <Button
                  variant="outline"
                  className="h-11 flex-1"
                  disabled={busy || jobs.length === 0}
                  onClick={extend}
                >
                  <CalendarPlus className="size-4" /> Add {extra || 0} more
                </Button>
              </div>
              {respace ? (
                <p className="text-xs text-muted-foreground">
                  Save the change to how often first — otherwise the new visits would be spaced
                  differently from the ones already booked.
                </p>
              ) : null}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
