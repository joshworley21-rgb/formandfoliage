// Turns the SPA build into a folder the Capacitor WebView can load.
//
// The web build lands in .output/public with the client-rendered shell named
// _shell.html (nitro serves it as the fallback for every route). A WebView
// opens index.html instead, and there is no server to rewrite anything — so we
// copy the public assets and promote the shell to index.html.
import { cp, mkdir, readdir, rename, rm, stat } from "node:fs/promises";
import { join } from "node:path";

const SOURCE = ".output/public";
const TARGET = "dist-app";

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

if (!(await exists(SOURCE))) {
  console.error(`Missing ${SOURCE}. Run \`bun run build\` first.`);
  process.exit(1);
}

await rm(TARGET, { recursive: true, force: true });
await mkdir(TARGET, { recursive: true });
await cp(SOURCE, TARGET, { recursive: true });

const shell = join(TARGET, "_shell.html");
if (!(await exists(shell))) {
  console.error("Missing _shell.html — is spa.enabled still set in vite.config.ts?");
  process.exit(1);
}
await rename(shell, join(TARGET, "index.html"));

// The service worker is for the website. Inside the app every asset is already
// on the device, and a second cache layer only creates stale-content bugs.
for (const file of ["sw.js", "sw-warmup.js"]) {
  await rm(join(TARGET, file), { force: true });
}
for (const file of await readdir(TARGET)) {
  if (file.startsWith("workbox-") && file.endsWith(".js")) {
    await rm(join(TARGET, file), { force: true });
  }
}

console.log(`Built ${TARGET}/ — run \`bunx cap sync android\` to copy it into the app.`);
