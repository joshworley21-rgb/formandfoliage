import logoAsset from "@/assets/jw-logo.png.asset.json";
import { cn } from "@/lib/utils";

export const LOGO_URL = logoAsset.url;

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
