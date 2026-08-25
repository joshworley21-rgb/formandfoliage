/**
 * Copy text to the clipboard, and say whether it worked.
 *
 * navigator.clipboard is not always there to be used: the Android WebView
 * refuses it in some configurations, and the promise it hands back rejects
 * rather than returning false. The old call site awaited it with nothing to
 * catch the rejection, so a failed copy looked exactly like a button that did
 * nothing at all.
 *
 * The fallback is the old execCommand trick, which the WebView does allow. It
 * is deprecated on the web and perfectly alive in a WebView, which is the only
 * place it is reached from.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // Fall through: refused, or no permission. The textarea below still works.
  }

  return legacyCopy(text);
}

function legacyCopy(text: string): boolean {
  if (typeof document === "undefined") return false;

  const area = document.createElement("textarea");
  area.value = text;
  // Off-screen rather than hidden: a display:none element cannot be selected,
  // and the copy silently does nothing.
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.top = "-1000px";
  area.style.opacity = "0";
  document.body.appendChild(area);

  try {
    area.select();
    area.setSelectionRange(0, text.length);
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    document.body.removeChild(area);
  }
}
