import type jsPDF from "jspdf";
import { Filesystem, Directory } from "@capacitor/filesystem";
import { Share } from "@capacitor/share";
import { isNativeApp } from "@/lib/native";
import { pdfToBase64 } from "@/lib/invoice-pdf";

export type SaveOutcome =
  | { kind: "downloaded" }
  | { kind: "shared" }
  | { kind: "saved"; where: string }
  | { kind: "cancelled" };

/**
 * Put a generated PDF somewhere the person can get at it.
 *
 * On the web jsPDF's own save() is right: it makes a blob and clicks a hidden
 * link, which is how a browser download works. In the Android app that link
 * does nothing whatsoever — a WebView will not action an <a download> — so the
 * button appeared to work and produced no file and no error.
 *
 * The native path tries the share sheet first, since that is where "save to
 * Files", Drive, WhatsApp and email all are. If sharing fails for any reason
 * the file is still written somewhere real and its location reported, so the
 * button always does something and never fails in silence again.
 */
export async function savePdf(doc: jsPDF, filename: string): Promise<SaveOutcome> {
  if (!isNativeApp()) {
    doc.save(filename);
    return { kind: "downloaded" };
  }

  const data = pdfToBase64(doc);

  // Written to the cache first: no permission needed, and the FileProvider in
  // AndroidManifest.xml already exposes that directory for sharing.
  const cached = await Filesystem.writeFile({
    path: filename,
    data,
    directory: Directory.Cache,
  });

  try {
    await Share.share({ title: filename, files: [cached.uri] });
    return { kind: "shared" };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    // Backing out of the share sheet arrives here as an error. Nothing went
    // wrong and there is nothing to report.
    if (/cancel/i.test(message)) return { kind: "cancelled" };

    // Sharing is the nicety; having the file is the point. Keep a copy
    // somewhere the file manager can see and say where it went.
    await Filesystem.writeFile({ path: filename, data, directory: Directory.Documents });
    console.error("Share failed, saved to Documents instead:", message);
    return { kind: "saved", where: `Documents/${filename}` };
  }
}
