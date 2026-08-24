import { RefreshCw } from "lucide-react";
import { useEffect, useRef, useState } from "react";

/** How far to drag before letting go actually refreshes. */
const TRIGGER = 70;
/** Dragging past this is ignored, so the indicator cannot be hauled down the screen. */
const MAX_PULL = 110;
/** A drag has to be mostly vertical, or sideways swipes would start a pull. */
const DIRECTION_RATIO = 1.5;

/**
 * Drag down at the top of the page to reload it.
 *
 * The app is used one-handed outdoors, where reaching for a menu to refresh is
 * a nuisance and the instinct is to pull. Only starts when the page is already
 * scrolled to the top, so it never competes with ordinary scrolling.
 */
export function PullToRefresh({
  onRefresh,
  children,
}: {
  onRefresh: () => Promise<unknown>;
  children: React.ReactNode;
}) {
  const [pull, setPull] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const start = useRef<{ x: number; y: number } | null>(null);
  const active = useRef(false);
  // The listeners are registered once per refresh state, so they need the live
  // pull distance rather than the one captured when they were created.
  const pullRef = useRef(0);
  pullRef.current = pull;

  useEffect(() => {
    function onTouchStart(event: TouchEvent) {
      // Only from the very top, and never mid-refresh or mid-pinch.
      if (refreshing || window.scrollY > 0 || event.touches.length !== 1) {
        start.current = null;
        return;
      }
      const touch = event.touches[0]!;
      start.current = { x: touch.clientX, y: touch.clientY };
      active.current = false;
    }

    function onTouchMove(event: TouchEvent) {
      const from = start.current;
      if (!from || refreshing) return;
      const touch = event.touches[0]!;
      const dy = touch.clientY - from.y;
      const dx = Math.abs(touch.clientX - from.x);

      if (dy <= 0 || dy < dx * DIRECTION_RATIO) {
        // Going up, or mostly sideways: leave it to the browser.
        if (!active.current) start.current = null;
        return;
      }
      // Scrolled away from the top mid-drag — abandon rather than fight it.
      if (window.scrollY > 0) {
        start.current = null;
        setPull(0);
        return;
      }

      active.current = true;
      // Resistance, so it feels attached to the finger rather than free.
      setPull(Math.min(MAX_PULL, dy * 0.5));
    }

    async function onTouchEnd() {
      const reached = pullRef.current >= TRIGGER;
      start.current = null;
      active.current = false;
      if (!reached || refreshing) {
        setPull(0);
        return;
      }
      setRefreshing(true);
      setPull(TRIGGER);
      try {
        await onRefresh();
      } finally {
        setRefreshing(false);
        setPull(0);
      }
    }

    window.addEventListener("touchstart", onTouchStart, { passive: true });
    window.addEventListener("touchmove", onTouchMove, { passive: true });
    window.addEventListener("touchend", onTouchEnd);
    window.addEventListener("touchcancel", onTouchEnd);
    return () => {
      window.removeEventListener("touchstart", onTouchStart);
      window.removeEventListener("touchmove", onTouchMove);
      window.removeEventListener("touchend", onTouchEnd);
      window.removeEventListener("touchcancel", onTouchEnd);
    };
  }, [onRefresh, refreshing]);

  const ready = pull >= TRIGGER;

  return (
    <div className="relative">
      <div
        aria-hidden={pull === 0 && !refreshing}
        className="pointer-events-none absolute inset-x-0 top-0 z-20 flex justify-center overflow-hidden"
        style={{ height: pull, opacity: pull === 0 ? 0 : 1 }}
      >
        <span className="mt-2 flex items-center gap-2 rounded-full bg-card px-3 py-1.5 text-xs font-medium shadow-sm">
          <RefreshCw
            className={`size-3.5 ${refreshing ? "animate-spin" : ""}`}
            style={refreshing ? undefined : { transform: `rotate(${pull * 3}deg)` }}
          />
          {refreshing ? "Refreshing…" : ready ? "Let go to refresh" : "Pull to refresh"}
        </span>
      </div>
      <div
        style={{
          transform: `translateY(${pull}px)`,
          transition: pull === 0 || refreshing ? "transform 200ms ease-out" : "none",
        }}
      >
        {children}
      </div>
    </div>
  );
}
