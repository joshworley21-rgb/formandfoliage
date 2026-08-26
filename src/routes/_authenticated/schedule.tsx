import { createFileRoute } from "@tanstack/react-router";
import {
  addDays,
  addMonths,
  addWeeks,
  endOfMonth,
  endOfWeek,
  format,
  isSameDay,
  isSameMonth,
  isToday,
  parseISO,
  startOfDay,
  startOfMonth,
  startOfWeek,
} from "date-fns";
import {
  CalendarCheck,
  ChevronLeft,
  ChevronRight,
  ClipboardList,
  Pencil,
  Plus,
  Trash2,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { AppShell } from "@/components/AppShell";
import { useIsStaff } from "@/hooks/use-workspace";
import { JobSheetDialog } from "@/components/JobSheetDialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import {
  useClients,
  useDeleteJob,
  useJobs,
  useSaveJob,
  useSaveVisit,
  useStaffCompleteJob,
  useVisits,
} from "@/hooks/use-crm";
import { supabase } from "@/integrations/supabase/client";
import {
  FREQUENCY_LABELS,
  hoursLabel,
  money,
  priceVisit,
  type Frequency,
  type ScheduledJob,
  type Visit,
} from "@/lib/crm";
import { findVisitForJob } from "@/lib/job-visit-link";
import { currentUser } from "@/lib/session";

export const Route = createFileRoute("/_authenticated/schedule")({
  head: () => ({
    meta: [
      { title: "Schedule — JW Garden Services CRM" },
      {
        name: "description",
        content: "Plan and track upcoming garden jobs in month, week and day views.",
      },
      { property: "og:title", content: "Schedule — JW Garden Services CRM" },
      {
        property: "og:description",
        content: "Plan and track upcoming garden jobs in month, week and day views.",
      },
    ],
  }),
  component: SchedulePage,
});

type View = "month" | "week" | "day";

const FREQ_STEP: Record<Frequency, (d: Date, i: number) => Date> = {
  weekly: (d, i) => addWeeks(d, i),
  fortnightly: (d, i) => addWeeks(d, i * 2),
  four_weekly: (d, i) => addWeeks(d, i * 4),
  monthly: (d, i) => addMonths(d, i),
  quarterly: (d, i) => addMonths(d, i * 3),
};

function SchedulePage() {
  const [view, setView] = useState<View>("month");
  const [cursor, setCursor] = useState(() => startOfDay(new Date()));
  const [selectedDay, setSelectedDay] = useState(() => startOfDay(new Date()));

  const range = useMemo(() => {
    if (view === "day") return { start: startOfDay(cursor), end: addDays(startOfDay(cursor), 1) };
    if (view === "week")
      return {
        start: startOfWeek(cursor, { weekStartsOn: 1 }),
        end: endOfWeek(cursor, { weekStartsOn: 1 }),
      };
    return {
      start: startOfWeek(startOfMonth(cursor), { weekStartsOn: 1 }),
      end: endOfWeek(endOfMonth(cursor), { weekStartsOn: 1 }),
    };
  }, [cursor, view]);

  const isStaff = useIsStaff();
  const staffComplete = useStaffCompleteJob();
  const { data: clients = [] } = useClients();
  const { data: jobs = [] } = useJobs({
    from: range.start.toISOString(),
    to: addDays(range.end, 1).toISOString(),
  });
  const { data: visits = [] } = useVisits();
  const saveJob = useSaveJob();
  const deleteJob = useDeleteJob();
  const [editingJob, setEditingJob] = useState<ScheduledJob | null>(null);
  const saveVisit = useSaveVisit();

  const [bookingOpen, setBookingOpen] = useState(false);
  const [completing, setCompleting] = useState<ScheduledJob | null>(null);

  // Visits already claimed by a job, so two jobs on one day cannot both point
  // at the same one.
  const claimedVisitIds = useMemo(
    () => jobs.map((job) => job.visit_id).filter((id): id is string => Boolean(id)),
    [jobs],
  );
  const alreadyLogged = completing
    ? findVisitForJob(visits, completing.client_id, completing.starts_at, claimedVisitIds)
    : null;
  const [sheetJob, setSheetJob] = useState<ScheduledJob | null>(null);

  const clientName = (id: string) => clients.find((c) => c.id === id)?.name ?? "Client";

  const days = useMemo(() => {
    const out: Date[] = [];
    let d = range.start;
    while (d <= range.end) {
      out.push(d);
      d = addDays(d, 1);
    }
    return out;
  }, [range]);

  const jobsOn = (day: Date) =>
    jobs
      .filter((j) => isSameDay(parseISO(j.starts_at), day))
      .sort((a, b) => a.starts_at.localeCompare(b.starts_at));

  function shift(dir: 1 | -1) {
    setCursor((c) =>
      view === "month" ? addMonths(c, dir) : view === "week" ? addWeeks(c, dir) : addDays(c, dir),
    );
  }

  const headingLabel =
    view === "month"
      ? format(cursor, "MMMM yyyy")
      : view === "week"
        ? `${format(range.start, "d MMM")} – ${format(range.end, "d MMM yyyy")}`
        : format(cursor, "EEEE d MMMM yyyy");

  return (
    <AppShell
      title="Schedule"
      description="Plan the round and tick jobs off as you go."
      actions={
        isStaff ? null : (
          <Button onClick={() => setBookingOpen(true)} disabled={clients.length === 0}>
            <Plus className="size-4" /> Book job
          </Button>
        )
      }
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-1">
          <Button variant="outline" size="icon" onClick={() => shift(-1)} aria-label="Previous">
            <ChevronLeft className="size-4" />
          </Button>
          <Button variant="outline" size="icon" onClick={() => shift(1)} aria-label="Next">
            <ChevronRight className="size-4" />
          </Button>
          <span className="ml-2 font-display text-lg font-bold tracking-tight">{headingLabel}</span>
        </div>
        <Tabs value={view} onValueChange={(v) => setView(v as View)}>
          <TabsList>
            <TabsTrigger value="month">Month</TabsTrigger>
            <TabsTrigger value="week">Week</TabsTrigger>
            <TabsTrigger value="day">Day</TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      {view === "month" ? (
        <Card className="overflow-hidden py-0">
          <div className="figure grid grid-cols-7 border-b border-border bg-secondary text-center text-[0.6rem] uppercase tracking-[0.1em] text-muted-foreground">
            {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((d) => (
              <div key={d} className="py-2">
                {d}
              </div>
            ))}
          </div>
          <div className="grid grid-cols-7">
            {days.map((day) => {
              const dayJobs = jobsOn(day);
              const selected = isSameDay(day, selectedDay);
              return (
                <button
                  key={day.toISOString()}
                  type="button"
                  onClick={() => setSelectedDay(day)}
                  className={`min-h-20 border-b border-r border-border p-1.5 text-left align-top transition-colors last:border-r-0 hover:bg-accent/60 ${
                    isSameMonth(day, cursor) ? "" : "bg-muted/40 text-muted-foreground"
                  } ${selected ? "ring-2 ring-inset ring-primary" : ""}`}
                >
                  <span
                    className={`figure inline-flex size-6 items-center justify-center rounded-full text-xs font-semibold ${
                      isToday(day) ? "bg-primary text-primary-foreground" : ""
                    }`}
                  >
                    {format(day, "d")}
                  </span>
                  {/*
                    A phone gives each cell about fifty pixels. A name truncated
                    to "09:…" tells you nothing, so on a phone the cell says only
                    how much work is on the day, as dots; the names appear from
                    the tablet breakpoint up, where they actually fit.
                  */}
                  <div className="mt-1 flex gap-0.5 sm:hidden">
                    {dayJobs.slice(0, 4).map((job) => (
                      <span
                        key={job.id}
                        className={`size-1.5 rounded-full ${
                          job.status === "done" ? "bg-primary" : "bg-lime"
                        }`}
                      />
                    ))}
                  </div>
                  <div className="mt-1 hidden space-y-0.5 sm:block">
                    {dayJobs.slice(0, 2).map((job) => (
                      <p
                        key={job.id}
                        className={`truncate rounded px-1 py-0.5 text-[0.65rem] font-medium ${
                          job.status === "done"
                            ? "bg-secondary text-muted-foreground line-through"
                            : "bg-lime/20 text-foreground"
                        }`}
                      >
                        {format(parseISO(job.starts_at), "HH:mm")} {clientName(job.client_id)}
                      </p>
                    ))}
                    {dayJobs.length > 2 ? (
                      <p className="figure px-1 text-[0.6rem] text-muted-foreground">
                        +{dayJobs.length - 2} more
                      </p>
                    ) : null}
                  </div>
                </button>
              );
            })}
          </div>
        </Card>
      ) : null}

      <div className="mt-5 space-y-4">
        {(view === "month" ? [selectedDay] : days).map((day) => {
          const dayJobs = jobsOn(day);
          return (
            <div key={day.toISOString()}>
              {isToday(day) ? (
                <div className="mb-2 flex items-baseline justify-between rounded-2xl bg-band px-4 py-3 text-band-foreground">
                  <p className="figure text-[0.6rem] uppercase tracking-[0.14em] opacity-75">
                    Today
                  </p>
                  <p className="font-display text-lg font-bold leading-none">
                    {format(day, "EEE d MMM")}
                  </p>
                </div>
              ) : (
                <h2 className="figure mb-2 text-[0.65rem] uppercase tracking-[0.12em] text-muted-foreground">
                  {format(day, "EEEE d MMMM")}
                </h2>
              )}
              {dayJobs.length === 0 ? (
                <p className="rounded-lg border border-dashed border-border px-3 py-4 text-sm text-muted-foreground">
                  Nothing booked.
                </p>
              ) : (
                <div className="space-y-2">
                  {dayJobs.map((job) => (
                    <Card
                      key={job.id}
                      className="stripe gap-0 overflow-hidden py-0"
                      style={{
                        ["--stripe" as string]:
                          job.status === "done"
                            ? "var(--color-primary)"
                            : isToday(day)
                              ? "var(--color-lime)"
                              : "var(--color-border)",
                      }}
                    >
                      {/* The two actions wanted mid-job sit on their own row at
                          thumb height; edit and delete stay small, because they
                          are not what this card is for. */}
                      <CardContent className="space-y-2.5 py-3 pl-4 pr-3">
                        <div className="flex items-start justify-between gap-3">
                          <div className="min-w-0">
                            <p className="truncate font-semibold">{clientName(job.client_id)}</p>
                            <p className="figure text-xs text-muted-foreground">
                              {job.duration_minutes} min
                              {job.status === "done" ? " · done" : ""}
                            </p>
                          </div>
                          <p className="figure shrink-0 text-base font-semibold leading-none">
                            {format(parseISO(job.starts_at), "HH:mm")}
                          </p>
                        </div>

                        {job.notes ? (
                          <p className="line-clamp-2 text-sm text-muted-foreground">{job.notes}</p>
                        ) : null}

                        <div className="flex items-center gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            className="h-10 flex-1"
                            onClick={() => setSheetJob(job)}
                          >
                            <ClipboardList className="size-4" /> Job sheet
                          </Button>
                          {job.status !== "done" ? (
                            <Button
                              size="sm"
                              className="h-10 flex-1"
                              onClick={() => setCompleting(job)}
                            >
                              <CalendarCheck className="size-4" /> Complete
                            </Button>
                          ) : null}
                          {isStaff ? null : (
                            <Button
                              variant="ghost"
                              size="icon"
                              className="size-10 shrink-0"
                              aria-label="Edit job"
                              onClick={() => setEditingJob(job)}
                            >
                              <Pencil className="size-4" />
                            </Button>
                          )}
                          {isStaff ? null : (
                            <Button
                              variant="ghost"
                              size="icon"
                              className="size-10 shrink-0"
                              aria-label="Delete job"
                              onClick={async () => {
                                const wholeSeries =
                                  job.series_id && confirm("Delete the whole repeating series?")
                                    ? job.series_id
                                    : null;
                                await deleteJob.mutateAsync({ id: job.id, wholeSeries });
                                toast.success("Job removed");
                              }}
                            >
                              <Trash2 className="size-4 text-destructive" />
                            </Button>
                          )}
                        </div>
                      </CardContent>
                    </Card>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <JobDialog
        open={bookingOpen || editingJob !== null}
        job={editingJob}
        onOpenChange={(v) => {
          if (!v) {
            setBookingOpen(false);
            setEditingJob(null);
          }
        }}
        defaultDate={view === "month" ? selectedDay : cursor}
        onSaved={() => {
          setBookingOpen(false);
          setEditingJob(null);
        }}
        saveJob={saveJob.mutateAsync}
      />

      <JobSheetDialog
        job={sheetJob}
        clientName={sheetJob ? clientName(sheetJob.client_id) : ""}
        onClose={() => setSheetJob(null)}
      />

      <CompleteDialog
        job={completing}
        alreadyLogged={alreadyLogged}
        onUseLogged={async () => {
          if (!completing || !alreadyLogged) return;
          await saveJob.mutateAsync({
            id: completing.id,
            client_id: completing.client_id,
            starts_at: completing.starts_at,
            status: "done",
            visit_id: alreadyLogged.id,
          });
          setCompleting(null);
          toast.success("Job completed using the visit already logged");
        }}
        onClose={() => setCompleting(null)}
        defaultHourlyRate={
          completing
            ? Number(clients.find((c) => c.id === completing.client_id)?.hourly_rate ?? 0)
            : 0
        }
        defaultDayRate={
          completing
            ? Number(clients.find((c) => c.id === completing.client_id)?.fixed_price ?? 0)
            : 0
        }
        defaultPayMode={
          completing && clients.find((c) => c.id === completing.client_id)?.billing_mode === "fixed"
            ? "day"
            : "hourly"
        }

        onComplete={async ({ hours, materials, description, amount }) => {
          if (!completing) return;
          if (isStaff) {
            await staffComplete.mutateAsync({
              jobId: completing.id,
              hours,
              ...(description || completing.notes
                ? { description: description || completing.notes || "" }
                : {}),
            });
            setCompleting(null);
            toast.success("Job completed");
            return;
          }
          const client = clients.find((c) => c.id === completing.client_id);
          if (!client) return;
          const visit = await saveVisit.mutateAsync({
            client_id: client.id,
            visit_date: format(parseISO(completing.starts_at), "yyyy-MM-dd"),
            hours,
            materials_cost: materials,
            description: description || completing.notes,
            amount: amount ?? priceVisit(client, hours, materials),
          });
          await saveJob.mutateAsync({
            id: completing.id,
            client_id: completing.client_id,
            starts_at: completing.starts_at,
            status: "done",
            visit_id: visit?.id ?? null,
          });
          setCompleting(null);
          toast.success("Job completed and visit logged");
        }}
        clientName={completing ? clientName(completing.client_id) : ""}
        hideMaterials={isStaff}
      />
    </AppShell>
  );
}

/** Books a new job, or edits one that already exists. */
function JobDialog({
  open,
  job,
  onOpenChange,
  defaultDate,
  onSaved,
  saveJob,
}: {
  open: boolean;
  job: ScheduledJob | null;
  onOpenChange: (v: boolean) => void;
  defaultDate: Date;
  onSaved: () => void;
  saveJob: (input: {
    id?: string;
    client_id: string;
    starts_at: string;
    duration_minutes?: number;
    notes?: string | null;
    series_id?: string | null;
  }) => Promise<unknown>;
}) {
  const { data: clients = [] } = useClients();
  const [clientId, setClientId] = useState("");
  const [date, setDate] = useState(format(defaultDate, "yyyy-MM-dd"));
  const [time, setTime] = useState("09:00");
  const [endTime, setEndTime] = useState("10:00");
  const [notes, setNotes] = useState("");
  const [repeats, setRepeats] = useState(false);
  const [frequency, setFrequency] = useState<Frequency>("fortnightly");
  const [occurrences, setOccurrences] = useState("12");
  const [busy, setBusy] = useState(false);

  // The dialog stays mounted between openings, so its fields have to be put
  // back deliberately. Without this an edit would leave its values behind for
  // the next booking, and a booking would open on whichever day was picked
  // first rather than the one being looked at.
  useEffect(() => {
    if (!open) return;
    if (job) {
      const starts = parseISO(job.starts_at);
      const mins = starts.getHours() * 60 + starts.getMinutes() + (job.duration_minutes ?? 60);
      setClientId(job.client_id);
      setDate(format(starts, "yyyy-MM-dd"));
      setTime(format(starts, "HH:mm"));
      setEndTime(
        `${String(Math.floor(Math.min(mins, 23 * 60 + 59) / 60)).padStart(2, "0")}:${String(
          Math.min(mins, 23 * 60 + 59) % 60,
        ).padStart(2, "0")}`,
      );
      setNotes(job.notes ?? "");
    } else {
      setClientId("");
      setDate(format(defaultDate, "yyyy-MM-dd"));
      setTime("09:00");
      setEndTime("10:00");
      setNotes("");
    }
    // Repeating is a booking-time decision; editing never re-creates a series.
    setRepeats(false);
    setFrequency("fortnightly");
    setOccurrences("12");
  }, [open, job, defaultDate]);

  const toMinutes = (t: string) => {
    const [h, m] = t.split(":").map(Number);
    return (h || 0) * 60 + (m || 0);
  };
  const fromMinutes = (mins: number) => {
    const c = Math.max(0, Math.min(mins, 23 * 60 + 59));
    return `${String(Math.floor(c / 60)).padStart(2, "0")}:${String(c % 60).padStart(2, "0")}`;
  };
  const duration = Math.max(0, toMinutes(endTime) - toMinutes(time));

  function applyPreset(mins: number) {
    setEndTime(fromMinutes(toMinutes(time) + mins));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const client = clients.find((c) => c.id === clientId);
    if (!client) {
      toast.error("Pick a client");
      return;
    }
    if (duration <= 0) {
      toast.error("Finish time must be after the start time");
      return;
    }
    setBusy(true);
    try {
      const first = new Date(`${date}T${time}`);

      if (job) {
        await saveJob({
          id: job.id,
          client_id: client.id,
          starts_at: first.toISOString(),
          duration_minutes: duration,
          notes: notes || null,
        });
        toast.success("Job updated");
        onSaved();
        return;
      }

      let seriesId: string | null = null;

      if (repeats) {
        const authUser = await currentUser();
        const { data, error } = await supabase
          .from("job_series")
          .insert({
            user_id: authUser!.id,
            client_id: client.id,
            frequency,
            start_date: date,
            start_time: time,
            duration_minutes: duration,
            notes: notes || null,
          })
          .select("id")
          .single();
        if (error) throw error;
        seriesId = data.id;
      }

      const count = repeats ? Math.max(1, Math.min(Number(occurrences) || 1, 60)) : 1;
      for (let i = 0; i < count; i++) {
        const when = repeats ? FREQ_STEP[frequency](first, i) : first;
        await saveJob({
          client_id: client.id,
          starts_at: when.toISOString(),
          duration_minutes: duration,
          notes: notes || null,
          series_id: seriesId,
        });
      }
      toast.success(repeats ? `${count} jobs booked` : "Job booked");
      onSaved();
    } catch (error) {
      const fallback = job ? "Could not update the job" : "Could not book job";
      toast.error(error instanceof Error ? error.message : fallback);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{job ? "Edit job" : "Book a job"}</DialogTitle>
        </DialogHeader>
        <form className="space-y-3" onSubmit={submit}>
          <div className="space-y-1.5">
            <Label>Client</Label>
            <Select value={clientId} onValueChange={setClientId}>
              <SelectTrigger>
                <SelectValue placeholder="Choose client" />
              </SelectTrigger>
              <SelectContent>
                {clients.map((c) => (
                  <SelectItem key={c.id} value={c.id}>
                    {c.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            <div className="space-y-1.5">
              <Label htmlFor="jdate">Date</Label>
              <Input
                id="jdate"
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="jtime">Start</Label>
              <Input
                id="jtime"
                type="time"
                value={time}
                onChange={(e) => setTime(e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="jend">Finish</Label>
              <Input
                id="jend"
                type="time"
                value={endTime}
                onChange={(e) => setEndTime(e.target.value)}
              />
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="outline" size="sm" onClick={() => applyPreset(480)}>
              Full day (8h)
            </Button>
            <Button type="button" variant="outline" size="sm" onClick={() => applyPreset(240)}>
              Half day (4h)
            </Button>
            <Button type="button" variant="outline" size="sm" onClick={() => applyPreset(120)}>
              2h
            </Button>
            <span className="text-sm text-muted-foreground">
              {duration > 0
                ? `${Math.floor(duration / 60)}h${duration % 60 ? ` ${duration % 60}m` : ""}`
                : "Finish must be after start"}
            </span>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="jnotes">Notes</Label>
            <Textarea
              id="jnotes"
              rows={2}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Mow, edge, hedge front"
            />
          </div>
          {job ? (
            job.series_id ? (
              <p className="rounded-lg border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
                This visit is part of a repeating series. Changes here apply to this visit only —
                the rest of the series is left as it is.
              </p>
            ) : null
          ) : (
            <label className="flex items-center gap-2 text-sm font-medium">
              <Checkbox checked={repeats} onCheckedChange={(v) => setRepeats(Boolean(v))} />
              Repeats
            </label>
          )}
          {!job && repeats ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label>Frequency</Label>
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
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="occ">Number of visits</Label>
                <Input
                  id="occ"
                  type="number"
                  min="1"
                  max="60"
                  value={occurrences}
                  onChange={(e) => setOccurrences(e.target.value)}
                />
              </div>
            </div>
          ) : null}
          <DialogFooter>
            <Button type="submit" disabled={busy}>
              {job ? "Save changes" : "Book"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function CompleteDialog({
  job,
  clientName,
  alreadyLogged,
  onUseLogged,
  onClose,
  onComplete,
  hideMaterials,
  defaultHourlyRate = 0,
  defaultDayRate = 0,
  defaultPayMode = "hourly",
}: {
  job: ScheduledJob | null;
  clientName: string;
  /** A visit logged for this client on this day that no job has claimed. */
  alreadyLogged?: Visit | null;
  onUseLogged?: () => Promise<void>;
  hideMaterials?: boolean;
  defaultHourlyRate?: number;
  defaultDayRate?: number;
  defaultPayMode?: "hourly" | "day";
  onClose: () => void;
  onComplete: (input: {
    hours: number;
    materials: number;
    description: string;
    amount?: number;
  }) => Promise<void>;
}) {
  const [hours, setHours] = useState("1");
  const [materials, setMaterials] = useState("0");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [payMode, setPayMode] = useState<"hourly" | "day">(defaultPayMode);
  const [rate, setRate] = useState("0");
  const [days, setDays] = useState("1");
  // Set when the offer to reuse a logged visit is turned down, so the form
  // appears in its place rather than in addition to it.
  const [enteringAnyway, setEnteringAnyway] = useState(false);

  const rateFor = (mode: "hourly" | "day") =>
    String(Number((mode === "day" ? defaultDayRate : defaultHourlyRate) || 0));

  const labour =
    payMode === "day"
      ? (Number(rate) || 0) * (Number(days) || 0)
      : (Number(rate) || 0) * (Number(hours) || 0);
  const amountOverride =
    Math.round((labour + (hideMaterials ? 0 : Number(materials) || 0)) * 100) / 100;

  return (
    <Dialog
      open={Boolean(job)}
      onOpenChange={(v) => {
        if (!v) onClose();
        if (v && job) {
          setHours(String((job.duration_minutes / 60).toFixed(2)));
          setMaterials("0");
          setDescription(job.notes ?? "");
          setPayMode(defaultPayMode);
          setRate(rateFor(defaultPayMode));
          setDays("1");
          setEnteringAnyway(false);
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Complete job — {clientName}</DialogTitle>
        </DialogHeader>

        {/*
          The hours for this day are already written down. Asking for them a
          second time is how the same afternoon ended up logged twice.
        */}
        {alreadyLogged && !enteringAnyway ? (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">
              A visit is already logged for {clientName} on{" "}
              {format(parseISO(alreadyLogged.visit_date), "d MMM")}.
            </p>
            <div className="rounded-xl border border-border bg-secondary/50 p-3">
              <p className="figure text-base font-semibold">
                {hoursLabel(Number(alreadyLogged.hours))} · {money(Number(alreadyLogged.amount))}
              </p>
              {alreadyLogged.description ? (
                <p className="mt-0.5 text-sm text-muted-foreground">{alreadyLogged.description}</p>
              ) : null}
            </div>
            <div className="flex flex-col gap-2">
              <Button
                className="h-11"
                disabled={busy}
                onClick={async () => {
                  if (!onUseLogged) return;
                  setBusy(true);
                  try {
                    await onUseLogged();
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                <CalendarCheck className="size-4" /> Complete using this visit
              </Button>
              <Button
                variant="ghost"
                className="h-11"
                disabled={busy}
                onClick={() => setEnteringAnyway(true)}
              >
                Log separate hours instead
              </Button>
            </div>
          </div>
        ) : (
          <form
            className="space-y-3"
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy(true);
              try {
                await onComplete({
                  hours: Number(hours) || 0,
                  materials: Number(materials) || 0,
                  description,
                  ...(amountOverride === undefined ? {} : { amount: amountOverride }),
                });
              } finally {
                setBusy(false);
              }
            }}
          >
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="chours">Hours worked</Label>
                <Input
                  id="chours"
                  type="number"
                  step="0.25"
                  min="0"
                  value={hours}
                  onChange={(e) => setHours(e.target.value)}
                />
              </div>
              {hideMaterials ? null : (
                <div className="space-y-1.5">
                  <Label htmlFor="cmat">Materials (£)</Label>
                  <Input
                    id="cmat"
                    type="number"
                    step="0.01"
                    min="0"
                    value={materials}
                    onChange={(e) => setMaterials(e.target.value)}
                  />
                </div>
              )}
            </div>
            {hideMaterials ? null : (
              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label>Pay basis</Label>
                  <Select
                    value={payMode}
                    onValueChange={(v) => {
                      const mode = v as "hourly" | "day";
                      setPayMode(mode);
                      setRate(rateFor(mode));
                    }}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="hourly">Hourly rate</SelectItem>
                      <SelectItem value="day">Day rate</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="crate">
                    {payMode === "day" ? "Day rate (£)" : "Hourly rate (£)"}
                  </Label>
                  <Input
                    id="crate"
                    type="number"
                    step="0.01"
                    min="0"
                    value={rate}
                    onChange={(e) => setRate(e.target.value)}
                  />
                  <p className="text-xs text-muted-foreground">From the client record</p>
                </div>
                {payMode === "day" ? (
                  <div className="space-y-1.5">
                    <Label htmlFor="cdays">Days worked</Label>
                    <Input
                      id="cdays"
                      type="number"
                      step="0.5"
                      min="0"
                      value={days}
                      onChange={(e) => setDays(e.target.value)}
                    />
                  </div>
                ) : null}
                <p className="self-end text-sm text-muted-foreground sm:col-span-2">
                  Visit total: £{amountOverride.toFixed(2)}
                </p>
              </div>
            )}
            <div className="space-y-1.5">
              <Label htmlFor="cdesc">Work done</Label>
              <Textarea
                id="cdesc"
                rows={3}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
            <DialogFooter>
              <Button type="submit" disabled={busy}>
                Log visit
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
