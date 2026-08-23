import { cn } from "@/lib/utils";

/**
 * The logo was a pointer to a Lovable-hosted asset (/__l5e/assets-v1/...),
 * which 404s anywhere but that hosting — the app header, the enquiry and quote
 * pages customers see, and every invoice PDF, which fetches this to embed it.
 *
 * Same artwork, but the copy in public/ that ships with the build, so it works
 * offline and inside the Android app too.
 */
export const LOGO_URL = "/icon-512.png";

export function Logo({
  className,
  showWordmark = true,
  invert = false,
}: {
  className?: string;
  showWordmark?: boolean;
  invert?: boolean;
}) {
  return (
    <span className={cn("flex items-center gap-2.5", className)}>
      <img
        src={LOGO_URL}
        alt="JW Garden Services logo"
        className={cn("h-9 w-auto object-contain", invert && "brightness-0 invert")}
        width={36}
        height={36}
      />
      {showWordmark ? (
        <span className="leading-tight">
          <span className="block font-display text-base font-bold tracking-tight">
            JW Garden Services
          </span>
          <span className="block text-[0.65rem] uppercase tracking-[0.18em] opacity-70">
            Grounds &amp; garden care
          </span>
        </span>
      ) : null}
    </span>
  );
}
