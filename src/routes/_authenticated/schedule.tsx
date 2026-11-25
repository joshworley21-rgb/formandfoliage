import { createFileRoute } from "@tanstack/react-router";
import {
  addDays,
  addMinutes,
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
  CalendarClock,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronUp,
  ClipboardList,
  Pencil,
  MoreHorizontal,
  Plus,
  Receipt,
  Repeat,
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
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
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
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
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
import { FREQ_STEP } from "@/lib/series";
import { MoveJobDialog } from "@/components/MoveJobDialog";
import { SeriesDialog } from "@/components/SeriesDialog";
import { VisitDialog } from "@/components/VisitDialog";
import { currentUser } from "@/lib/session";
import { cn } from "@/lib/utils";

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

function SchedulePage() {
  const [view, setView] = useState<View>("week");
  const [cursor, setCursor] = useState(() => startOfDay(new Date()));
  const [selectedDay, setSelectedDay] = useState(() => startOfDay(new Date()));
  // The compact rota cards open this rather than carrying five buttons apiece.
  const [detailJob, setDetailJob] = useState<ScheduledJob | null>(null);
  const [summaryOpen, setSummaryOpen] = useState(true);

  const range = useMemo(() => {
    // The day view is one day. `days` below walks start..end inclusive, so an
    // end of "tomorrow" put tomorrow on the screen too, under its own heading —
    // which made the Day tab look like a two-day view.
    if (view === "day") return { start: startOfDay(cursor), end: startOfDay(cursor) };
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

  // The day a new booking should start on, and the fact that one is being made
  // at all. It is a date rather than a flag because the booking can now start
  // from a day on the calendar, not only from the button in the header — and
  // then the day it starts from is the one that was tapped.
  const [bookingDate, setBookingDate] = useState<Date | null>(null);
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
  // A job out of the run being edited as a run, rather than as one visit.
  const [seriesJob, setSeriesJob] = useState<ScheduledJob | null>(null);
  const [movingJob, setMovingJob] = useState<ScheduledJob | null>(null);
  const [editingVisit, setEditingVisit] = useState<Visit | null>(null);

  /** The visit a completed job wrote, so its materials can be corrected here. */
  const visitOf = (job: ScheduledJob) =>
    job.visit_id ? (visits.find((v) => v.id === job.visit_id) ?? null) : null;

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
        ? `Week of ${format(range.start, "d MMM")} – ${format(range.end, "d MMM yyyy")}`
        : format(cursor, "EEEE d MMMM yyyy");

  // What the solid block under the controls adds up. Visits are money actually
  // logged; jobs are the hours already committed on the rota.
  const rangeVisits = visits.filter((visit) => {
    const date = parseISO(visit.visit_date);
    return date >= range.start && date <= range.end && !visit.missed;
  });
  const rangeIncome = rangeVisits.reduce((total, visit) => total + Number(visit.amount), 0);
  const rangeMaterials = rangeVisits.reduce(
    (total, visit) => total + Number(visit.materials_cost),
    0,
  );
  const rangeNet = rangeIncome - rangeMaterials;
  const rangeHours = jobs.reduce((total, job) => total + job.duration_minutes / 60, 0);

  // One row per client for the summary panel: how many days on the round and
  // how many hours that adds up to.
  const clientDays = new Map<string, { days: Set<string>; hours: number }>();
  for (const job of jobs) {
    const entry = clientDays.get(job.client_id) ?? { days: new Set<string>(), hours: 0 };
    entry.days.add(format(parseISO(job.starts_at), "yyyy-MM-dd"));
    entry.hours += job.duration_minutes / 60;
    clientDays.set(job.client_id, entry);
  }
  const clientSummary = [...clientDays.entries()]
    .map(([id, entry]) => ({
      id,
      name: clientName(id),
      days: entry.days.size,
      hours: entry.hours,
    }))
    .sort((a, b) => b.hours - a.hours);

  const totalsLabel =
    view === "month" ? "Month totals" : view === "week" ? "Week totals" : "Day totals";

  return (
    <AppShell
      title="Rota"
      description={headingLabel}
      actions={
        isStaff ? null : (
          <Button
            variant="gold"
            onClick={() => setBookingDate(view === "month" ? selectedDay : cursor)}
            disabled={clients.length === 0}
          >
            <Plus className="size-4" /> Add
          </Button>
        )
      }
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-1.5">
          <Button
            variant="outline"
            size="icon"
            className="size-9 bg-card"
            onClick={() => shift(-1)}
            aria-label="Previous"
          >
            <ChevronLeft className="size-4" />
          </Button>
          <Button
            variant="outline"
            size="sm"
            className="h-9 bg-card px-3 text-xs font-semibold"
            onClick={() => {
              const now = startOfDay(new Date());
              setCursor(now);
              setSelectedDay(now);
            }}
          >
            Today
          </Button>
          <Button
            variant="outline"
            size="icon"
            className="size-9 bg-card"
            onClick={() => shift(1)}
            aria-label="Next"
          >
            <ChevronRight className="size-4" />
          </Button>
        </div>
        <Tabs value={view} onValueChange={(v) => setView(v as View)}>
          <TabsList className="h-9">
            <TabsTrigger value="week">Week</TabsTrigger>
            <TabsTrigger value="month">Month</TabsTrigger>
            <TabsTrigger value="day">List</TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      {/* The four figures that decide whether the week is working. */}
      <section className="mt-3 rounded-2xl bg-band px-4 py-3.5 text-band-foreground">
        <p className="text-[0.55rem] uppercase tracking-[0.18em] text-band-foreground/50">
          {totalsLabel}
        </p>
        <div className="mt-2 grid grid-cols-4 divide-x divide-band-foreground/15">
          {[
            { label: "Income", value: money(rangeIncome), gold: true },
            { label: "Materials", value: money(rangeMaterials), gold: false },
            { label: "Net", value: money(rangeNet), gold: true },
            { label: "Hours", value: hoursLabel(rangeHours), gold: false },
          ].map((item, index) => (
            <div key={item.label} className={index === 0 ? "pr-2" : "px-2"}>
              <p
                className={cn(
                  "figure truncate text-[0.95rem] font-semibold leading-none",
                  item.gold ? "text-gold" : "text-band-foreground",
                )}
              >
                {item.value}
              </p>
              <p className="mt-1 truncate text-[0.55rem] text-band-foreground/50">{item.label}</p>
            </div>
          ))}
        </div>
      </section>

      {view === "week" ? (
        <>
          {/* Seven narrow columns, scrolled sideways the way a paper rota is. */}
          <div className="-mx-4 mt-3 overflow-x-auto px-4 pb-1">
            <div className="grid auto-cols-[minmax(150px,1fr)] grid-flow-col gap-2">
              {days.map((day) => {
                const dayJobs = jobsOn(day);
                return (
                  <div key={day.toISOString()} className="flex flex-col gap-2">
                    <div
                      className={cn(
                        "rounded-xl border px-2 py-2 text-center",
                        isToday(day)
                          ? "border-primary bg-primary text-primary-foreground"
                          : "border-border bg-secondary/70 text-foreground",
                      )}
                    >
                      <p className="figure text-[0.55rem] uppercase tracking-[0.14em] opacity-65">
                        {format(day, "EEE")}
                      </p>
                      <p className="figure font-display text-xl font-bold leading-none">
                        {format(day, "d")}
                      </p>
                    </div>

                    <div className="flex flex-1 flex-col gap-2">
                      {dayJobs.map((job) => {
                        const start = parseISO(job.starts_at);
                        const client = clients.find((c) => c.id === job.client_id);
                        return (
                          <button
                            key={job.id}
                            type="button"
                            onClick={() => setDetailJob(job)}
                            className={cn(
                              "stripe w-full rounded-lg border border-border bg-card px-2 py-2 text-left shadow-sm transition-colors hover:border-primary/40 active:bg-secondary/50",
                              job.status === "done" && "opacity-65",
                            )}
                            style={{
                              ["--stripe" as string]:
                                job.status === "done"
                                  ? "var(--color-primary)"
                                  : isToday(day)
                                    ? "var(--color-lime)"
                                    : "var(--color-border)",
                            }}
                          >
                            <p className="truncate pl-1 text-[0.72rem] font-semibold leading-tight">
                              {clientName(job.client_id)}
                            </p>
                            {client?.address ? (
                              <p className="truncate pl-1 text-[0.6rem] text-muted-foreground">
                                {client.address}
                              </p>
                            ) : null}
                            <p className="figure mt-1 pl-1 text-[0.62rem] font-medium">
                              {format(start, "HH:mm")}–
                              {format(addMinutes(start, job.duration_minutes), "HH:mm")}
                            </p>
                            <p className="figure pl-1 text-[0.58rem] text-muted-foreground">
                              {job.duration_minutes} min
                              {job.status === "done" ? " · done" : ""}
                            </p>
                          </button>
                        );
                      })}

                      {isStaff || clients.length === 0 ? null : (
                        <button
                          type="button"
                          onClick={() => setBookingDate(day)}
                          className="flex items-center justify-center gap-1 rounded-lg border border-dashed border-border px-2 py-2 text-[0.62rem] font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:text-primary"
                        >
                          <Plus className="size-3" /> Add
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Who is on the round, in place of the staff table we cannot fill. */}
          <div className="mt-4 overflow-hidden rounded-2xl border border-border bg-card">
            <button
              type="button"
              onClick={() => setSummaryOpen((open) => !open)}
              className="flex w-full items-center justify-between gap-2 bg-primary px-4 py-2.5 text-left text-primary-foreground"
            >
              <span className="text-sm font-semibold">
                Clients — {view === "week" ? "This week" : "This period"}
              </span>
              {summaryOpen ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
            </button>
            {summaryOpen ? (
              <div className="divide-y divide-border">
                {clientSummary.length === 0 ? (
                  <p className="px-4 py-4 text-sm text-muted-foreground">
                    Nothing on the rota for this period yet.
                  </p>
                ) : (
                  clientSummary.slice(0, 10).map((client) => (
                    <div key={client.id} className="flex items-center gap-3 px-4 py-2.5">
                      <p className="min-w-0 flex-1 truncate text-sm font-medium">{client.name}</p>
                      <p className="figure text-xs text-muted-foreground">
                        {client.days} {client.days === 1 ? "day" : "days"}
                      </p>
                      <p className="figure w-12 text-right text-xs font-semibold">
                        {client.hours % 1 === 0 ? client.hours.toFixed(0) : client.hours.toFixed(1)}
                        hrs
                      </p>
                    </div>
                  ))
                )}
              </div>
            ) : null}
          </div>
        </>
      ) : null}

      {view === "month" ? (
        <>
          <Card className="mt-3 overflow-hidden py-0">
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
                    /*
                    The first tap picks the day — the list underneath is what
                    most taps are after. The second tap, on the day already
                    picked, books on it: the shortest route from "that Tuesday"
                    to a job, without taking the first tap away from looking.
                  */
                    onClick={() => {
                      if (selected && !isStaff && clients.length > 0) setBookingDate(day);
                      else setSelectedDay(day);
                    }}
                    aria-label={`${format(day, "EEEE d MMMM")}${
                      dayJobs.length ? `, ${dayJobs.length} booked` : ", nothing booked"
                    }${selected && !isStaff ? " — tap again to book" : ""}`}
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
          {/* A gesture nobody is told about is a gesture nobody uses. */}
          {isStaff || clients.length === 0 ? null : (
            <p className="mt-2 text-center text-xs text-muted-foreground">
              Tap a day to see it — tap it again to book on it.
            </p>
          )}
        </>
      ) : null}

      {view !== "week" ? (
        <div className="mt-5 space-y-4">
          {(view === "month" ? [selectedDay] : days).map((day) => {
            const dayJobs = jobsOn(day);
            return (
              <div key={day.toISOString()}>
                {/* Every day heading carries its own add button, so in week view
                  the day you book on is the one you are looking at rather than
                  whichever one the cursor happens to be on. */}
                <div className="mb-2 flex items-center gap-2">
                  {isToday(day) ? (
                    <div className="flex flex-1 items-baseline justify-between rounded-2xl bg-band px-4 py-3 text-band-foreground">
                      <p className="figure text-[0.6rem] uppercase tracking-[0.14em] opacity-75">
                        Today
                      </p>
                      <p className="font-display text-lg font-bold leading-none">
                        {format(day, "EEE d MMM")}
                      </p>
                    </div>
                  ) : (
                    <h2 className="figure flex-1 text-[0.65rem] uppercase tracking-[0.12em] text-muted-foreground">
                      {format(day, "EEEE d MMMM")}
                    </h2>
                  )}
                  {isStaff || clients.length === 0 ? null : (
                    <Button
                      variant="outline"
                      size="icon"
                      className="size-10 shrink-0"
                      aria-label={`Book a job on ${format(day, "EEEE d MMMM")}`}
                      onClick={() => setBookingDate(day)}
                    >
                      <Plus className="size-4" />
                    </Button>
                  )}
                </div>
                {dayJobs.length === 0 ? (
                  isStaff || clients.length === 0 ? (
                    <p className="rounded-lg border border-dashed border-border px-3 py-4 text-sm text-muted-foreground">
                      Nothing booked.
                    </p>
                  ) : (
                    /* An empty day is the one place where "book something" is
                     certainly what you came for, so the empty state is the
                     button rather than sitting next to one. */
                    <button
                      type="button"
                      onClick={() => setBookingDate(day)}
                      className="flex w-full items-center justify-center gap-2 rounded-lg border border-dashed border-border px-3 py-5 text-sm text-muted-foreground transition-colors hover:bg-accent/60 active:bg-accent"
                    >
                      <Plus className="size-4" /> Book a job on {format(day, "EEE d MMM")}
                    </button>
                  )
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
                            <p className="line-clamp-2 text-sm text-muted-foreground">
                              {job.notes}
                            </p>
                          ) : null}

                          <div className="flex flex-wrap items-center gap-2">
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
                            {/*
                            A done job has no Complete button, which leaves the
                            room for the thing actually wanted afterwards: the
                            materials you forgot. Without this the only route to
                            them was the Visits screen, for work on the card in
                            front of you.
                          */}
                            {job.status === "done" && !isStaff && visitOf(job) ? (
                              <Button
                                variant="outline"
                                size="sm"
                                className="h-10 flex-1"
                                onClick={() => setEditingVisit(visitOf(job)!)}
                              >
                                <Receipt className="size-4" /> Edit visit
                              </Button>
                            ) : null}
                            {/*
                            Everything occasional behind one button. Moving,
                            editing and deleting used to sit out here competing
                            for thumb space with the two things you actually
                            touch mid-job, and the row had grown to five
                            controls — which is how a card stops being readable
                            at arm's length in a garden.
                          */}
                            {isStaff ? null : (
                              <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                  <Button
                                    variant="ghost"
                                    size="icon"
                                    className="size-10 shrink-0"
                                    aria-label={`More for ${clientName(job.client_id)}'s job`}
                                  >
                                    <MoreHorizontal className="size-4" />
                                  </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end" className="w-56">
                                  {job.status !== "done" ? (
                                    <DropdownMenuItem
                                      className="h-11"
                                      onSelect={() => setMovingJob(job)}
                                    >
                                      <CalendarClock className="size-4" /> Move to another day
                                    </DropdownMenuItem>
                                  ) : null}
                                  <DropdownMenuItem
                                    className="h-11"
                                    onSelect={() => setEditingJob(job)}
                                  >
                                    <Pencil className="size-4" /> Edit this job
                                  </DropdownMenuItem>
                                  {job.series_id ? (
                                    <DropdownMenuItem
                                      className="h-11"
                                      onSelect={() => setSeriesJob(job)}
                                    >
                                      <Repeat className="size-4" /> Edit the whole run
                                    </DropdownMenuItem>
                                  ) : null}
                                  <DropdownMenuSeparator />
                                  <DropdownMenuItem
                                    className="h-11 text-destructive focus:text-destructive"
                                    onSelect={async () => {
                                      const wholeSeries =
                                        job.series_id && confirm("Delete the whole repeating run?")
                                          ? job.series_id
                                          : null;
                                      await deleteJob.mutateAsync({ id: job.id, wholeSeries });
                                      toast.success("Job removed");
                                    }}
                                  >
                                    <Trash2 className="size-4" /> Delete
                                  </DropdownMenuItem>
                                </DropdownMenuContent>
                              </DropdownMenu>
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
      ) : null}

      <JobDialog
        open={bookingDate !== null || editingJob !== null}
        job={editingJob}
        onOpenChange={(v) => {
          if (!v) {
            setBookingDate(null);
            setEditingJob(null);
          }
        }}
        defaultDate={bookingDate ?? (view === "month" ? selectedDay : cursor)}
        onSaved={() => {
          setBookingDate(null);
          setEditingJob(null);
        }}
        onEditSeries={(job) => {
          // One dialog at a time: this replaces the single-visit edit rather
          // than stacking on top of it.
          setEditingJob(null);
          setBookingDate(null);
          setSeriesJob(job);
        }}
        saveJob={saveJob.mutateAsync}
      />

      <SeriesDialog
        job={seriesJob}
        clientName={seriesJob ? clientName(seriesJob.client_id) : ""}
        onClose={() => setSeriesJob(null)}
      />

      <MoveJobDialog
        job={movingJob}
        clientName={movingJob ? clientName(movingJob.client_id) : ""}
        onClose={() => setMovingJob(null)}
      />

      <VisitDialog
        open={editingVisit !== null}
        visit={editingVisit}
        onOpenChange={(v) => {
          if (!v) setEditingVisit(null);
        }}
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

      {/*
        One job, opened from its rota card. The five things you can do to a
        job live here rather than on the card, which is why the week stays
        readable at arm's length.
      */}
      <Sheet
        open={detailJob !== null}
        onOpenChange={(open) => {
          if (!open) setDetailJob(null);
        }}
      >
        <SheetContent side="bottom" className="rounded-t-3xl">
          {detailJob ? (
            <>
              <SheetHeader className="text-left">
                <SheetTitle className="font-display text-xl">
                  {clientName(detailJob.client_id)}
                </SheetTitle>
                <div className="space-y-0.5">
                  {clients.find((c) => c.id === detailJob.client_id)?.address ? (
                    <p className="text-sm text-muted-foreground">
                      {clients.find((c) => c.id === detailJob.client_id)?.address}
                    </p>
                  ) : null}
                  <p className="figure text-sm font-medium">
                    {format(parseISO(detailJob.starts_at), "EEEE d MMM")} ·{" "}
                    {format(parseISO(detailJob.starts_at), "HH:mm")}–
                    {format(
                      addMinutes(parseISO(detailJob.starts_at), detailJob.duration_minutes),
                      "HH:mm",
                    )}{" "}
                    · {detailJob.duration_minutes} min
                  </p>
                </div>
              </SheetHeader>

              {detailJob.notes ? (
                <p className="mt-3 rounded-xl bg-background px-3 py-2.5 text-sm text-muted-foreground">
                  {detailJob.notes}
                </p>
              ) : null}

              <div className="mt-4 grid gap-2">
                <Button
                  variant="outline"
                  className="h-11 justify-start"
                  onClick={() => {
                    setSheetJob(detailJob);
                    setDetailJob(null);
                  }}
                >
                  <ClipboardList className="size-4" /> Job sheet
                </Button>
                {detailJob.status !== "done" ? (
                  <Button
                    variant="gold"
                    className="h-11 justify-start"
                    onClick={() => {
                      setCompleting(detailJob);
                      setDetailJob(null);
                    }}
                  >
                    <CalendarCheck className="size-4" /> Complete job
                  </Button>
                ) : null}
                {detailJob.status === "done" && !isStaff && visitOf(detailJob) ? (
                  <Button
                    variant="outline"
                    className="h-11 justify-start"
                    onClick={() => {
                      setEditingVisit(visitOf(detailJob)!);
                      setDetailJob(null);
                    }}
                  >
                    <Receipt className="size-4" /> Edit visit
                  </Button>
                ) : null}
                {isStaff ? null : (
                  <>
                    <Button
                      variant="outline"
                      className="h-11 justify-start"
                      onClick={() => {
                        setEditingJob(detailJob);
                        setDetailJob(null);
                      }}
                    >
                      <Pencil className="size-4" /> Edit this job
                    </Button>
                    {detailJob.status !== "done" ? (
                      <Button
                        variant="outline"
                        className="h-11 justify-start"
                        onClick={() => {
                          setMovingJob(detailJob);
                          setDetailJob(null);
                        }}
                      >
                        <CalendarClock className="size-4" /> Move to another day
                      </Button>
                    ) : null}
                    {detailJob.series_id ? (
                      <Button
                        variant="outline"
                        className="h-11 justify-start"
                        onClick={() => {
                          setSeriesJob(detailJob);
                          setDetailJob(null);
                        }}
                      >
                        <Repeat className="size-4" /> Edit the whole run
                      </Button>
                    ) : null}
                    <Button
                      variant="ghost"
                      className="h-11 justify-start text-destructive hover:text-destructive"
                      onClick={async () => {
                        const job = detailJob;
                        setDetailJob(null);
                        const wholeSeries =
                          job.series_id && confirm("Delete the whole repeating run?")
                            ? job.series_id
                            : null;
                        await deleteJob.mutateAsync({ id: job.id, wholeSeries });
                        toast.success("Job removed");
                      }}
                    >
                      <Trash2 className="size-4" /> Delete job
                    </Button>
                  </>
                )}
              </div>
            </>
          ) : null}
        </SheetContent>
      </Sheet>

      {isStaff || clients.length === 0 ? null : (
        <button
          type="button"
          onClick={() => setBookingDate(view === "month" ? selectedDay : cursor)}
          aria-label="Add a job"
          className="fixed bottom-[calc(env(safe-area-inset-bottom)+4.75rem)] right-4 z-30 flex size-14 items-center justify-center rounded-full bg-gold text-gold-foreground shadow-lg shadow-black/25 transition-transform active:scale-95 lg:bottom-6 lg:right-6"
        >
          <Plus className="size-6" />
        </button>
      )}
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
  onEditSeries,
  saveJob,
}: {
  open: boolean;
  job: ScheduledJob | null;
  onOpenChange: (v: boolean) => void;
  defaultDate: Date;
  onSaved: () => void;
  onEditSeries: (job: ScheduledJob) => void;
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
              /*
                The sentence explaining the limit is exactly where someone finds
                out about it, so it is also where the way past it belongs.
              */
              <div className="space-y-2 rounded-lg border border-border bg-muted/40 px-3 py-2.5">
                <p className="text-xs text-muted-foreground">
                  This visit is part of a repeating run. Changes here apply to this visit only — the
                  rest of the run is left as it is.
                </p>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-10 w-full"
                  onClick={() => onEditSeries(job)}
                >
                  <Repeat className="size-4" /> Edit the whole run instead
                </Button>
              </div>
            ) : null
          ) : (
            /*
              "Repeats" on its own said nothing about what it would do, and a
              regular round is the normal case rather than the exotic one —
              someone went looking for weekly and fortnightly on an entirely
              different screen. It now says what ticking it books.
            */
            <label className="flex items-start gap-2.5 rounded-lg border border-border p-3 text-sm">
              <Checkbox
                checked={repeats}
                onCheckedChange={(v) => setRepeats(Boolean(v))}
                className="mt-0.5"
              />
              <span>
                <span className="font-medium">Repeats</span>
                <span className="block text-xs text-muted-foreground">
                  Book this client in weekly, fortnightly or monthly and the whole run goes on the
                  calendar at once.
                </span>
              </span>
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
