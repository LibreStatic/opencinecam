#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Renders the promo frame by frame in headless Chromium and encodes it with ffmpeg.
 *
 *   node render.mjs --format landscape|portrait [--fps 60] [--from 0 --to 66] [--workers 4]
 *   node render.mjs --format portrait --stills 2,7,13      # PNG stills into out/stills/
 *
 * Playwright comes from the global install (NODE_PATH or /opt/node-tools/node_modules) and
 * Chromium from PLAYWRIGHT_BROWSERS_PATH; set CHROMIUM to point at another executable.
 */
import { spawn } from "node:child_process";
import { createReadStream, existsSync, mkdirSync, readdirSync, statSync, writeFileSync, rmSync } from "node:fs";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith("--")) acc.push([a.slice(2), all[i + 1]?.startsWith("--") || all[i + 1] === undefined ? "1" : all[i + 1]]);
  return acc;
}, []));
const format = args.format ?? "landscape";
const fps = Number(args.fps ?? 60);
const workers = Number(args.workers ?? 4);

function loadPlaywright() {
  for (const p of ["playwright", "/opt/node-tools/node_modules/playwright"]) {
    try { return require(p); } catch {}
  }
  throw new Error("Playwright not found: npm i -g playwright, or set NODE_PATH");
}

function findChromium() {
  if (process.env.CHROMIUM) return process.env.CHROMIUM;
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH ?? "/opt/pw-browsers";
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).filter((d) => /^chromium-\d+$/.test(d)).sort().pop();
  return dir ? join(root, dir, "chrome-linux", "chrome") : undefined;
}

const types = { ".html": "text/html", ".js": "text/javascript", ".woff2": "font/woff2", ".css": "text/css" };
function serve() {
  const server = createServer((req, res) => {
    const path = join(here, decodeURIComponent(new URL(req.url, "http://x").pathname));
    if (!path.startsWith(here) || !existsSync(path) || statSync(path).isDirectory()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { "content-type": types[extname(path)] ?? "application/octet-stream" });
    createReadStream(path).pipe(res);
  });
  return new Promise((ok) => server.listen(0, "127.0.0.1", () => ok(server)));
}

async function openPage(browser, port) {
  const portrait = format === "portrait";
  const page = await browser.newPage({ viewport: { width: portrait ? 1080 : 1920, height: portrait ? 1920 : 1080 }, deviceScaleFactor: 1 });
  page.on("pageerror", (e) => console.error("page error:", e.message));
  await page.goto(`http://127.0.0.1:${port}/index.html?format=${format}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 120000 });
  return page;
}

const shot = (page) => page.locator("#stage").screenshot({ type: "png", animations: "disabled" });

function encoder(file) {
  const ff = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(fps), "-c:v", "png", "-i", "-",
    "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p", "-colorspace", "bt709", "-color_primaries", "bt709",
    "-color_trc", "bt709", "-movflags", "+faststart", file], { stdio: ["pipe", "inherit", "inherit"] });
  const done = new Promise((ok, fail) => ff.on("close", (c) => (c === 0 ? ok() : fail(new Error(`ffmpeg exited ${c}`)))));
  return {
    write: (buf) => new Promise((ok) => (ff.stdin.write(buf) ? ok() : ff.stdin.once("drain", ok))),
    close: () => { ff.stdin.end(); return done; },
  };
}

const { chromium } = loadPlaywright();
const server = await serve();
const port = server.address().port;
const browser = await chromium.launch({ executablePath: findChromium(), args: ["--force-color-profile=srgb", "--disable-lcd-text", "--font-render-hinting=none"] });
const out = join(here, "out");
mkdirSync(out, { recursive: true });

try {
  if (args.stills) {
    mkdirSync(join(out, "stills"), { recursive: true });
    const page = await openPage(browser, port);
    for (const t of args.stills.split(",").map(Number)) {
      await page.evaluate((x) => { window.__seek(x - 1 / 60); window.__seek(x); }, t);
      writeFileSync(join(out, "stills", `${format}-${t.toFixed(2).padStart(5, "0")}.png`), await shot(page));
    }
    console.log(`stills written to ${join(out, "stills")}`);
  } else {
    const page0 = await openPage(browser, port);
    const duration = await page0.evaluate(() => window.__duration);
    await page0.close();
    const from = Number(args.from ?? 0), to = Math.min(Number(args.to ?? duration), duration);
    const total = Math.round((to - from) * fps);
    const per = Math.ceil(total / workers);
    const parts = [];
    let rendered = 0;
    const started = Date.now();
    await Promise.all(Array.from({ length: workers }, async (_, w) => {
      const first = w * per, last = Math.min(total, first + per);
      if (first >= last) return;
      const page = await openPage(browser, port);
      const file = join(out, `.part-${format}-${w}.mp4`);
      parts[w] = file;
      const enc = encoder(file);
      // A warm-up frame so the first frame of each part has its scene bounds, like a continuous run.
      await page.evaluate((x) => window.__seek(x), from + (first - 1) / fps);
      for (let f = first; f < last; f++) {
        await page.evaluate((x) => window.__seek(x), from + f / fps);
        await enc.write(await shot(page));
        if (++rendered % 120 === 0) {
          const rate = rendered / ((Date.now() - started) / 1000);
          console.log(`${format}: ${rendered}/${total} frames, ${rate.toFixed(1)} fps, ~${Math.round((total - rendered) / rate)} s left`);
        }
      }
      await enc.close();
      await page.close();
    }));
    const list = join(out, `.parts-${format}.txt`);
    writeFileSync(list, parts.filter(Boolean).map((p) => `file '${p}'`).join("\n"));
    const video = join(out, `opencinecam-${format}-video.mp4`);
    await new Promise((ok, fail) => spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list, "-c", "copy", "-movflags", "+faststart", video], { stdio: "inherit" })
      .on("close", (c) => (c === 0 ? ok() : fail(new Error("concat failed")))));
    for (const p of parts.filter(Boolean)) rmSync(p);
    rmSync(list);
    console.log(`video written to ${video}`);
  }
} finally {
  await browser.close();
  server.close();
}
