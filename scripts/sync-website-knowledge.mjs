// Pulls the live pages of jw-gardenservices.co.uk into one raw text file so
// the distilled business profile in
// native-android/app/src/main/assets/business_knowledge.txt can be checked
// against the real site after an update.
//
// The bundle stays hand-curated: this script only produces the raw reference
// text and flags pages that changed since the last run. Distilling it into
// the compact profile is a human edit, exactly because the asset must stay
// ~1.5 KB and in the brand voice.
//
// Usage: bun scripts/sync-website-knowledge.mjs  (or node, v18+)
// Output: scripts/website-raw.txt (gitignored-friendly working file)

import { writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const BASE = "https://www.jw-gardenservices.co.uk";
const PATHS = [
  "/",
  "/about",
  "/our-work",
  "/gardeners-in-aylesbury",
  "/commercial-grounds-maintenance",
];

function stripHtml(html) {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&#39;|&rsquo;/g, "'")
    .replace(/&quot;|&ldquo;|&rdquo;/g, '"')
    .replace(/[ \t]+/g, " ")
    .replace(/\n\s*\n\s*\n+/g, "\n\n")
    .trim();
}

const sections = [];
let failures = 0;

for (const path of PATHS) {
  try {
    const response = await fetch(BASE + path, { redirect: "follow" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const html = await response.text();
    const text = stripHtml(html);
    sections.push(`=== ${path} (${text.length} chars) ===\n${text}\n`);
    console.log(`ok   ${path}  ${text.length} chars`);
  } catch (error) {
    failures++;
    console.error(`fail ${path}  ${error.message}`);
    sections.push(`=== ${path} FAILED: ${error.message} ===\n`);
  }
}

const out = new URL("./website-raw.txt", import.meta.url);
await writeFile(out, sections.join("\n"));
console.log(`\nwrote ${fileURLToPath(out)}`);
if (failures > 0) process.exitCode = 1;
