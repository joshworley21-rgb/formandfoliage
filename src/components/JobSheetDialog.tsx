import { Camera, Loader2, Plus, Trash2 } from "lucide-react";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  useDeleteJobPhoto,
  useDeleteJobTask,
  useJobPhotos,
  useJobTasks,
  useSaveJobTask,
  useUploadJobPhoto,
} from "@/hooks/use-crm";
import type { ScheduledJob } from "@/lib/crm";

export function JobSheetDialog({
  job,
  clientName,
  onClose,
}: {
  job: ScheduledJob | null;
  clientName: string;
  onClose: () => void;
}) {
  const jobId = job?.id;
  const { data: tasks = [] } = useJobTasks(jobId);
  const { data: photos = [], isLoading: photosLoading } = useJobPhotos(jobId);
  const saveTask = useSaveJobTask();
  const deleteTask = useDeleteJobTask();
  const uploadPhoto = useUploadJobPhoto();
  const deletePhoto = useDeleteJobPhoto();

  const [newTask, setNewTask] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);

  const doneCount = tasks.filter((t) => t.done).length;

  async function addTask(e: React.FormEvent) {
    e.preventDefault();
    if (!jobId || !newTask.trim()) return;
    await saveTask.mutateAsync({
      job_id: jobId,
      title: newTask.trim(),
      position: tasks.length,
    });
    setNewTask("");
  }

  async function onFiles(files: FileList | null) {
    if (!jobId || !files?.length) return;
    try {
      for (const file of Array.from(files)) {
        await uploadPhoto.mutateAsync({ jobId, file });
      }
      toast.success(files.length > 1 ? "Photos added" : "Photo added");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Upload failed");
    }
    if (fileRef.current) fileRef.current.value = "";
  }

  return (
    <Dialog open={Boolean(job)} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Job sheet — {clientName}</DialogTitle>
          <DialogDescription>Tick off tasks and attach photos of completed work.</DialogDescription>
        </DialogHeader>

        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold">Tasks</h3>
            <span className="text-xs text-muted-foreground">
              {doneCount}/{tasks.length} done
            </span>
          </div>

          <ul className="space-y-1.5">
            {tasks.map((task) => (
              <li
                key={task.id}
                className="flex items-center gap-2 rounded-lg border border-border px-3 py-2"
              >
                <Checkbox
                  checked={task.done}
                  onCheckedChange={(v) =>
                    saveTask.mutate({
                      id: task.id,
                      job_id: task.job_id,
                      title: task.title,
                      done: Boolean(v),
                    })
                  }
                  aria-label={`Mark ${task.title} done`}
                />
                <span
                  className={`flex-1 text-sm ${task.done ? "text-muted-foreground line-through" : ""}`}
                >
                  {task.title}
                </span>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="Remove task"
                  onClick={() => deleteTask.mutate({ id: task.id, jobId: task.job_id })}
                >
                  <Trash2 className="size-4 text-destructive" />
                </Button>
              </li>
            ))}
            {tasks.length === 0 ? (
              <li className="rounded-lg border border-dashed border-border px-3 py-4 text-sm text-muted-foreground">
                No tasks yet — add the work planned for this visit.
              </li>
            ) : null}
          </ul>

          <form onSubmit={addTask} className="flex gap-2">
            <Input
              value={newTask}
              onChange={(e) => setNewTask(e.target.value)}
              placeholder="e.g. Mow front lawn"
            />
            <Button type="submit" disabled={!newTask.trim() || saveTask.isPending}>
              <Plus className="size-4" /> Add
            </Button>
          </form>
        </section>

        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold">Photos</h3>
            <Button
              variant="outline"
              size="sm"
              onClick={() => fileRef.current?.click()}
              disabled={uploadPhoto.isPending}
            >
              {uploadPhoto.isPending ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                <Camera className="size-4" />
              )}
              Add photos
            </Button>
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              multiple
              className="hidden"
              onChange={(e) => onFiles(e.target.files)}
            />
          </div>

          {photosLoading ? (
            <p className="text-sm text-muted-foreground">Loading photos…</p>
          ) : photos.length === 0 ? (
            <p className="rounded-lg border border-dashed border-border px-3 py-4 text-sm text-muted-foreground">
              No photos yet.
            </p>
          ) : (
            <div className="grid grid-cols-3 gap-2">
              {photos.map((photo) => (
                <div
                  key={photo.id}
                  className="group relative overflow-hidden rounded-lg border border-border"
                >
                  {photo.url ? (
                    <img
                      src={photo.url}
                      alt={photo.caption ?? "Completed work photo"}
                      loading="lazy"
                      className="aspect-square w-full object-cover"
                    />
                  ) : (
                    <div className="aspect-square w-full bg-muted" />
                  )}
                  <Button
                    variant="secondary"
                    size="icon"
                    aria-label="Delete photo"
                    className="absolute right-1 top-1 size-7 opacity-90"
                    onClick={() =>
                      deletePhoto.mutate({
                        id: photo.id,
                        path: photo.storage_path,
                        jobId: photo.job_id,
                      })
                    }
                  >
                    <Trash2 className="size-3.5 text-destructive" />
                  </Button>
                </div>
              ))}
            </div>
          )}
        </section>
      </DialogContent>
    </Dialog>
  );
}
