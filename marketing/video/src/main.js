/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Stage set-up and the frame function. window.__seek(t) draws the frame at t seconds; render.mjs
 * calls it for every frame. ?format=portrait gives the 1080x1920 cut, ?dev=1 a scrubber.
 */
import { createBackdrop, drawBackdrop } from "./backdrop.js";
import { analyse, renderLandscape, toLog } from "./landscape.js";
import { clamp, phase } from "./motion.js";
import { Duration, Scenes } from "./scenes.js";
import { C } from "./ui.js";

const params = new URLSearchParams(location.search);
const portrait = params.get("format") === "portrait";
const st = { W: portrait ? 1080 : 1920, H: portrait ? 1920 : 1080, portrait };

const canvas = document.getElementById("stage");
canvas.width = st.W;
canvas.height = st.H;
const ctx = canvas.getContext("2d");

async function loadFonts() {
  const faces = [
    new FontFace("GSF", "url(fonts/googlesansflex.woff2)", { weight: "1 1000" }),
    new FontFace("GSC", "url(fonts/googlesanscode.woff2)", { weight: "300 800" }),
  ];
  for (const f of faces) document.fonts.add(await f.load());
}

await loadFonts();
const picWide = renderLandscape(1920, 1080, "dusk", 11);
const scratch = new OffscreenCanvas(8, 8).getContext("2d");
const env = {
  st,
  picWide,
  picTall: renderLandscape(1080, 1440, "dusk", 11),
  logWide: toLog(picWide),
  an: analyse(picWide),
  thumbs: [["dusk", 11], ["day", 4], ["amber", 8], ["night", 5], ["fog", 2]].map(([v, s]) => renderLandscape(640, 360, v, s)),
  scratch(w, h) {
    if (scratch.canvas.width !== Math.ceil(w) || scratch.canvas.height !== Math.ceil(h)) {
      scratch.canvas.width = Math.ceil(w);
      scratch.canvas.height = Math.ceil(h);
    }
    return scratch;
  },
};
const backdrop = createBackdrop(st);
const beats = Scenes.slice(1).map((s) => s.start);

function draw(t) {
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalAlpha = 1;
  ctx.fillStyle = C.paper;
  ctx.fillRect(0, 0, st.W, st.H);
  // Scenes draw onto a second pass so their bounds can calm the shapes behind them.
  const active = Scenes.filter((s) => t >= s.start && t < s.end + 0.001);
  const calm = active.flatMap((s) => s.calm ?? []);
  drawBackdrop(ctx, backdrop, t, beats, calm);
  for (const s of active) {
    const lt = t - s.start;
    const out = s.hold ? phase(t, s.end - 1.1, s.end) : phase(t, s.end - 0.5, s.end);
    ctx.save();
    s.calm = s.draw(ctx, env, lt, out, t) ?? [];
    ctx.restore();
  }
}

window.__duration = Duration;
window.__seek = (t) => draw(clamp(t, 0, Duration - 1e-6));
window.__ready = true;

if (params.has("dev")) {
  const bar = document.getElementById("dev");
  bar.hidden = false;
  const range = bar.querySelector("input");
  const label = bar.querySelector("span");
  range.max = String(Duration);
  let playing = true, t = Number(params.get("t") ?? 0), last = performance.now();
  bar.querySelector("button").onclick = () => (playing = !playing);
  range.oninput = () => { t = Number(range.value); playing = false; };
  const loop = (now) => {
    if (playing) t = (t + (now - last) / 1000) % Duration;
    last = now;
    range.value = String(t);
    label.textContent = t.toFixed(2) + " s";
    draw(t);
    requestAnimationFrame(loop);
  };
  requestAnimationFrame(loop);
} else {
  draw(Number(params.get("t") ?? 0));
}
