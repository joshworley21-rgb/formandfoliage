import { chromium } from "playwright";

async function snap(url = "http://localhost:5173", out = "public/preview.png") {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 412, height: 915 } }); // Galaxy S22+ viewport
  await page.goto(url, { waitUntil: "networkidle" });
  await page.screenshot({ path: out, fullPage: true });
  await browser.close();
  console.log(`Saved screenshot to ${out}`);
}
snap(process.argv[2], process.argv[3]);
