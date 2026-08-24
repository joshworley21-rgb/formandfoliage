import type jsPDF from "jspdf";
import { Filesystem, Directory } from "@capacitor/filesystem";
import { Share } from "@capacitor/share";
import { isNativeApp } from "@/lib/native";
import { pdfToBase64 } from "@/lib/invoice-pdf";

/**
 * Put a generated PDF somewhere the person can actually get at it.
 *
 * On the web, jsPDF's own save() is right: it makes a blob and clicks a hidden
 * link, which is exactly how a browser download works.
 *
 * In the Android app that link does nothing at all. A WebView will not action
 * an <a download>, so the button appeared to work and produced no file and no
 * error. There the PDF is written to the app's cache and handed to the system
 * share sheet, which is where "save to Files", Drive, WhatsApp and email all
 * live — more use than a download folder, and it needs no storage permission.
 */
export async function savePdf(doc: jsPDF, filename: string): Promise<"downloaded" | "shared"> {
  if (!isNativeApp()) {
    doc.save(filename);
    return "downloaded";
  }

  const { uri } = await Filesystem.writeFile({
    path: filename,
    data: pdfToBase64(doc),
    directory: Directory.Cache,
  });

  await Share.share({
    title: filename,
    // Some targets read the file, others only the text; give them both.
    text: filename,
    files: [uri],
  });
  return "shared";
}
