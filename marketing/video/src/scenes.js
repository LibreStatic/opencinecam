/* SPDX-License-Identifier: Apache-2.0 */
/* The storyboard. Each scene draws itself from its local time [lt] and its exit progress [out]. */
import { Spring, clamp, easeIn, easeInOut, easeOut, lerp, phase, spring } from "./motion.js";
import { Shapes, fillShape, morph, shapePath } from "./shapes.js";
import { drawLogo } from "./logo.js";
import {
  C, Mono, captureScreen, chip, chipWidth, clipRR, cover, drawHistogram, drawVectorscope, drawWaveform,
  exposureStrip, fillRR, font, phone, rrect, scopeCard, shadowed, textBlock,
} from "./ui.js";

/** Where text and visuals go in each format. */
export function layout(st) {
  return st.portrait
    ? { text: { x: 540, y: 150, align: "center", maxW: 900, size: 84, subSize: 31 }, vis: { x: 60, y: 590, w: 960, h: 1230 } }
    : { text: { x: 130, y: 540, align: "left", maxW: 600, valign: "center", size: 88, subSize: 30 }, vis: { x: 800, y: 90, w: 1000, h: 900 } };
}

/** Enter (scale up from 0.94 with the spatial spring) and leave (fade, slight shrink) around a centre. */
function present(ctx, lt, out, cx, cy, delay = 0.25, draw) {
  const p = spring(lt - delay, Spring.slow);
  const a = clamp(p * 1.5) * (1 - easeIn(out));
  if (a <= 0) return;
  const s = lerp(0.94, 1, p) - 0.03 * easeIn(out);
  ctx.save();
  ctx.globalAlpha = a;
  ctx.translate(cx, cy);
  ctx.scale(s, s);
  ctx.translate(-cx, -cy);
  draw();
  ctx.restore();
}

const fmtTC = (frames, fps = 24) => {
  const f = Math.floor(frames);
  const ff = f % fps, s = Math.floor(f / fps) % 60, m = Math.floor(f / fps / 60) % 60, h = Math.floor(f / fps / 3600);
  return [h, m, s, ff].map((v) => String(v).padStart(2, "0")).join(":");
};

// ── 0. Logo ──────────────────────────────────────────────────────────────────────────────────────
function intro(ctx, env, lt, out) {
  const { st } = env;
  const cx = st.W / 2;
  const size = st.portrait ? 380 : 330;
  const lift = spring(lt - 1.55, Spring.slow);
  const cy = st.H / 2 - (st.portrait ? 130 : 95) * lift;
  const fade = 1 - easeIn(out);
  drawLogo(ctx, cx, cy, size * (1 - 0.04 * easeIn(out)), {
    iris: spring(lt - 0.2, Spring.slow),
    ring: easeInOut(phase(lt, 0.45, 1.35)),
    arcs: easeInOut(phase(lt, 0.85, 1.6)),
    dot: spring(lt - 1.4, Spring.fast),
    alpha: fade,
  });
  const wy = cy + size * 0.5 + (st.portrait ? 150 : 120);
  const p = spring(lt - 1.75, Spring.slow);
  ctx.globalAlpha = clamp(p * 1.5) * fade;
  ctx.textAlign = "center";
  ctx.font = font(660, st.portrait ? 120 : 112);
  ctx.letterSpacing = "-3px";
  ctx.fillStyle = C.ink;
  ctx.fillText("OpenCineCam", cx, wy + (1 - p) * 30);
  ctx.letterSpacing = "0px";
  const q = spring(lt - 2.05, Spring.slow);
  ctx.globalAlpha = clamp(q * 1.5) * fade;
  ctx.font = font(430, st.portrait ? 40 : 36);
  ctx.fillStyle = C.ink2;
  ctx.fillText("A cinema camera for your phone.", cx, wy + 70 + (1 - q) * 24);
  ctx.textAlign = "left";
  ctx.globalAlpha = 1;
  return [{ x: cx - 420, y: cy - size / 2, w: 840, h: size + 300 }];
}

// ── 1. Manual control ────────────────────────────────────────────────────────────────────────────
function manual(ctx, env, lt, out) {
  const { st } = env;
  const L = layout(st);
  const tb = textBlock(ctx, { ...L.text, eyebrow: "Manual control", title: "Manual everything.", sub: "ISO, shutter angle, Kelvin, tint and focus, shown next to what the sensor really reports." }, lt, out);
  const ph = st.portrait ? 1000 : 860, pw = ph * 0.462;
  const pcx = st.portrait ? 540 : 1130, pcy = st.portrait ? 1250 : 545;
  // Values that roll as the highlight walks across the strip.
  const steps = [[1.4, 2.4], [2.4, 3.4], [3.4, 4.4], [4.4, 5.3]];
  const roll = steps.map(([a, b]) => easeInOut(phase(lt, a + 0.15, b - 0.2)));
  const iso = Math.round(lerp(400, 800, roll[1]) / 10) * 10;
  const kelvin = Math.round(lerp(5600, 4300, roll[2]) / 50) * 50;
  const focus = lerp(0.8, 2.4, roll[3]).toFixed(1);
  const cells = [
    { label: "SHUTTER", value: roll[0] < 0.5 ? "1/50" : "180°" },
    { label: "ISO", value: String(iso) },
    { label: "WB", value: `${kelvin}K` },
    { label: "EV", value: "0.0" },
    { label: "FOCUS", value: `${focus} m` },
  ];
  const active = steps.findIndex(([a, b]) => lt >= a && lt < b);
  present(ctx, lt, out, pcx, pcy, 0.2, () => {
    const scr = phone(ctx, pcx, pcy, pw, ph);
    captureScreen(ctx, scr, { picture: env.picTall, cells, hideStrip: lt > 1.05, zoom: 1.04 + 0.03 * phase(lt, 0, 6), modes: ["Photo", "Video", "Log", "Slow motion"], selectedMode: 1 });
  });
  // The strip lifts out of the phone as a large callout.
  const lift = spring(lt - 1.0, Spring.slow);
  if (lift > 0) {
    const cw = st.portrait ? 900 : 760, ch = st.portrait ? 128 : 112;
    const cx0 = st.portrait ? 540 : 1130, cy0 = st.portrait ? 1530 : 700;
    const cx1 = st.portrait ? 540 : 1370, cy1 = st.portrait ? 1560 : 800;
    const cx = lerp(cx0, cx1, lift), cy = lerp(cy0, cy1, lift);
    const s = lerp(0.5, 1, lift);
    ctx.save();
    ctx.globalAlpha = clamp(lift * 2) * (1 - easeIn(out));
    ctx.translate(cx, cy);
    ctx.scale(s, s);
    shadowed(ctx, () => fillRR(ctx, -cw / 2, -ch / 2, cw, ch, 26, C.container), 1.3);
    let hp = 0, hi = -1;
    if (active >= 0) {
      hi = [0, 1, 2, 4][active];
      hp = clamp(spring(lt - steps[active][0], Spring.spatial));
    }
    exposureStrip(ctx, -cw / 2, -ch / 2, cw, ch, cells, { k: ch / 58, highlight: hi, highlightP: hp });
    ctx.restore();
  }
  return [tb, { x: pcx - pw / 2, y: pcy - ph / 2, w: pw, h: ph }];
}

// ── 2. Modes ─────────────────────────────────────────────────────────────────────────────────────
const Modes = [
  { name: "Photo", shape: Shapes.circle, fill: C.amberC, glyph: "lens" },
  { name: "RAW photo", shape: Shapes.square, fill: C.stoneC, glyph: "RAW" },
  { name: "Burst", shape: Shapes.burst, fill: C.cyanC, glyph: "burst" },
  { name: "Bracket", shape: Shapes.pentagon, fill: C.sageC, glyph: "±EV" },
  { name: "Light Painting", shape: Shapes.sunny, fill: C.amberC, glyph: "trail" },
  { name: "Video", shape: Shapes.cookie6, fill: C.stoneC, glyph: "rec" },
  { name: "Slow motion", shape: Shapes.pill, fill: C.cyanC, glyph: "240" },
  { name: "Time lapse", shape: Shapes.cookie9, fill: C.sageC, glyph: "clock" },
  { name: "Log", shape: Shapes.clover, fill: C.amberC, glyph: "LOG" },
];

function modeGlyph(ctx, g, x, y, s, t) {
  ctx.strokeStyle = C.ink;
  ctx.fillStyle = C.ink;
  ctx.lineWidth = s * 0.09;
  ctx.lineCap = "round";
  ctx.textAlign = "center";
  if (g === "lens") {
    ctx.beginPath(); ctx.arc(x, y, s * 0.42, 0, Math.PI * 2); ctx.stroke();
    ctx.beginPath(); ctx.arc(x, y, s * 0.16, 0, Math.PI * 2); ctx.fill();
  } else if (g === "burst") {
    for (let i = 0; i < 3; i++) { rrect(ctx, x - s * 0.42 + i * s * 0.14, y - s * 0.32 - i * s * 0.1 + s * 0.12, s * 0.56, s * 0.42, s * 0.08); ctx.stroke(); }
  } else if (g === "trail") {
    ctx.beginPath();
    for (let i = 0; i <= 40; i++) {
      const u = i / 40;
      const px = x - s * 0.5 + u * s, py = y + Math.sin(u * Math.PI * 2.2 + t * 2) * s * 0.22;
      if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
    }
    ctx.stroke();
  } else if (g === "rec") {
    ctx.fillStyle = C.rec;
    ctx.beginPath(); ctx.arc(x, y, s * 0.24, 0, Math.PI * 2); ctx.fill();
  } else if (g === "clock") {
    ctx.beginPath(); ctx.arc(x, y, s * 0.4, 0, Math.PI * 2); ctx.stroke();
    const a = t * 1.6;
    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + Math.cos(a - 1.57) * s * 0.28, y + Math.sin(a - 1.57) * s * 0.28);
    ctx.moveTo(x, y); ctx.lineTo(x, y - s * 0.18); ctx.stroke();
  } else {
    ctx.font = font(700, s * 0.36, Mono);
    ctx.fillText(g, x, y + s * 0.13);
  }
  ctx.textAlign = "left";
  ctx.lineCap = "butt";
}

function modes(ctx, env, lt, out) {
  const { st } = env;
  const L = layout(st);
  const tb = textBlock(ctx, { ...L.text, eyebrow: "Capture modes", title: "Every way to shoot.", sub: "Nine modes, from RAW stills to 240 fps slow motion and Log video." }, lt, out);
  const tile = st.portrait ? 292 : 270, gap = st.portrait ? 26 : 24;
  const gw = tile * 3 + gap * 2;
  const gx = st.portrait ? 540 - gw / 2 : 1300 - gw / 2;
  const gy = st.portrait ? 1240 - gw / 2 : 540 - gw / 2;
  const sel = lt < 1.5 ? -1 : Math.min(8, Math.floor((lt - 1.5) / 0.48));
  const fade = 1 - easeIn(out);
  Modes.forEach((m, i) => {
    const col = i % 3, row = Math.floor(i / 3);
    const x = gx + col * (tile + gap), y = gy + row * (tile + gap);
    const p = spring(lt - 0.25 - (row + col) * 0.07, Spring.spatial);
    if (p <= 0) return;
    const on = i === sel;
    const sp = on ? spring(lt - (1.5 + sel * 0.48), Spring.spatial) : 0;
    const was = i < sel ? 1 : 0;
    ctx.save();
    ctx.globalAlpha = clamp(p * 1.6) * fade;
    const cx = x + tile / 2, cy = y + tile / 2;
    const s = lerp(0.7, 1, p) * (1 + 0.04 * sp);
    ctx.translate(cx, cy);
    ctx.scale(s, s);
    ctx.translate(-cx, -cy);
    shadowed(ctx, () => fillRR(ctx, x, y, tile, tile, 44, C.card), 0.45 + 0.6 * sp);
    const shapeR = tile * 0.25;
    const shape = on ? morph(m.shape, Shapes.cookie9, sp * 0.5) : m.shape;
    fillShape(ctx, shape, cx, y + tile * 0.42, shapeR * (1 + 0.1 * sp), lt * 0.25 * (i % 2 ? 1 : -1) + sp * 0.6, on ? C.amber : was ? m.fill : m.fill);
    modeGlyph(ctx, m.glyph, cx, y + tile * 0.42, shapeR * 1.05, lt);
    ctx.font = font(600, st.portrait ? 30 : 27);
    ctx.textAlign = "center";
    ctx.fillStyle = C.ink;
    ctx.fillText(m.name, cx, y + tile * 0.84);
    ctx.textAlign = "left";
    ctx.restore();
  });
  return [tb, { x: gx, y: gy, w: gw, h: gw }];
}

// ── 3. Monitoring ────────────────────────────────────────────────────────────────────────────────
function vfRect(st) {
  return st.portrait ? { x: 70, y: 560, w: 940, h: 600, r: 30 } : { x: 800, y: 110, w: 990, h: 557, r: 30 };
}

function zebraLayer(env, vf, lt, zoom) {
  const z = env.scratch(vf.w, vf.h);
  z.clearRect(0, 0, vf.w, vf.h);
  z.save();
  z.strokeStyle = "#FFFF00";
  z.lineWidth = 5;
  const off = (lt * 40) % 18;
  z.beginPath();
  for (let x = -vf.h; x < vf.w + vf.h; x += 18) { z.moveTo(x + off, 0); z.lineTo(x + off + vf.h, vf.h); }
  z.stroke();
  z.globalCompositeOperation = "destination-in";
  cover(z, env.an.zebra, { x: 0, y: 0, w: vf.w, h: vf.h }, zoom);
  z.restore();
  return z.canvas;
}

function monitoring(ctx, env, lt, out) {
  const { st } = env;
  const L = layout(st);
  const text = st.portrait ? L.text : { ...L.text, maxW: 560 };
  const tb = textBlock(ctx, { ...text, eyebrow: "Monitoring", title: "See exposure, not guesses.", sub: "Zebra, focus peaking, false colour, waveform, vectorscope and histogram. Never baked into the take." }, lt, out);
  const vf = vfRect(st);
  const zoom = 1 + 0.05 * phase(lt, 0, 8);
  const seq = [0.7, 2.3, 3.9, 5.5]; // zebra, peaking, false colour, scopes
  const layerAt = (i, r) => {
    const local = { x: 0, y: 0, w: vf.w, h: vf.h };
    if (i === 0) ctx.drawImage(zebraLayer(env, vf, lt, zoom), r.x, r.y);
    if (i === 1) cover(ctx, env.an.peaking, r, zoom);
    if (i === 2) { ctx.globalAlpha *= 0.88; cover(ctx, env.an.falseColor, r, zoom); }
    return local;
  };
  present(ctx, lt, out, vf.x + vf.w / 2, vf.y + vf.h / 2, 0.2, () => {
    shadowed(ctx, () => fillRR(ctx, vf.x, vf.y, vf.w, vf.h, vf.r, C.graphite));
    ctx.save();
    clipRR(ctx, vf);
    cover(ctx, env.picWide, vf, zoom);
    // Each tool wipes in from the left over the previous one.
    for (let i = 0; i < 3; i++) {
      const wipeIn = easeInOut(phase(lt, seq[i], seq[i] + 0.7));
      const wipeOut = easeInOut(phase(lt, seq[i + 1], seq[i + 1] + 0.7));
      if (wipeIn <= 0 || wipeOut >= 1) continue;
      ctx.save();
      ctx.beginPath();
      const a = vf.x + vf.w * wipeOut, b = vf.x + vf.w * wipeIn;
      ctx.rect(a, vf.y, b - a, vf.h);
      ctx.clip();
      layerAt(i, vf);
      ctx.restore();
      if (wipeIn < 1) {
        ctx.fillStyle = "rgba(255,255,255,0.9)";
        ctx.fillRect(vf.x + vf.w * wipeIn - 1.5, vf.y, 3, vf.h);
      }
    }
    ctx.restore();
  });
  // Tool chips, then the scopes.
  const tools = ["Zebra", "Peaking", "False colour", "Scopes"];
  const active = seq.reduce((acc, s, i) => (lt >= s ? i : acc), -1);
  const chipsOut = easeInOut(phase(lt, 5.6, 6.0));
  const cy = vf.y + vf.h + (st.portrait ? 38 : 32);
  ctx.save();
  ctx.globalAlpha = (1 - chipsOut) * (1 - easeIn(out));
  let total = tools.reduce((s, t) => s + chipWidth(ctx, t, 24) + 14, -14);
  let x = vf.x + (st.portrait ? (vf.w - total) / 2 : 0);
  tools.forEach((t, i) => {
    const p = spring(lt - 0.5 - i * 0.07, Spring.spatial);
    if (p <= 0) return;
    const on = i === active;
    ctx.save();
    ctx.globalAlpha *= clamp(p * 1.5);
    const w = chip(ctx, x, cy + (1 - p) * 20, t, { size: 24, fill: on ? C.amber : C.card, color: C.ink, stroke: on ? null : C.line });
    ctx.restore();
    x += w + 14;
  });
  ctx.restore();
  if (lt > 5.5) {
    const sy = vf.y + vf.h + 28;
    const rects = st.portrait
      ? [{ x: 70, y: sy, w: 940, h: 300 }, { x: 70, y: sy + 320, w: 460, h: 330 }, { x: 550, y: sy + 320, w: 460, h: 330 }]
      : [{ x: 800, y: sy, w: 440, h: 270 }, { x: 1256, y: sy, w: 260, h: 270 }, { x: 1532, y: sy, w: 258, h: 270 }];
    const labels = ["Waveform", "Vectorscope", "Histogram"];
    rects.forEach((r, i) => {
      const p = spring(lt - 5.75 - i * 0.12, Spring.spatial);
      const reveal = easeOut(phase(lt, 6.0 + i * 0.12, 7.0 + i * 0.12));
      present(ctx, p > 0 ? 1 : 0, out, r.x + r.w / 2, r.y + r.h / 2, 0, () => {
        ctx.save();
        ctx.globalAlpha *= clamp(p * 1.5);
        ctx.translate(0, (1 - p) * 40);
        scopeCard(ctx, r, labels[i], (a) => {
          if (i === 0) drawWaveform(ctx, a, env.an.waveform, reveal);
          if (i === 1) drawVectorscope(ctx, a, env.an.vectorscope, reveal);
          if (i === 2) drawHistogram(ctx, a, env.an.histogram, reveal);
        });
        ctx.restore();
      });
    });
  }
  return [tb, vf];
}

// ── 4. Log and LUTs ──────────────────────────────────────────────────────────────────────────────
function logScene(ctx, env, lt, out) {
  const { st } = env;
  const L = layout(st);
  const text = st.portrait ? L.text : { ...L.text, maxW: 560 };
  const tb = textBlock(ctx, { ...text, eyebrow: "Log and LUTs", title: "Shoot Log. Monitor with LUTs.", sub: "OCLog2 on 10-bit HEVC keeps the highlights. The LUT is for your eyes, never the file." }, lt, out);
  const base = vfRect(st);
  const vf = st.portrait ? { ...base, y: 600, h: 620 } : { ...base, y: 150, h: 640 };
  const split = lerp(1, 0.5, spring(lt - 0.9, Spring.slow)) - 0.16 * spring(lt - 3.2, Spring.slow);
  const zoom = 1.03 + 0.04 * phase(lt, 0, 6);
  present(ctx, lt, out, vf.x + vf.w / 2, vf.y + vf.h / 2, 0.15, () => {
    shadowed(ctx, () => fillRR(ctx, vf.x, vf.y, vf.w, vf.h, vf.r, C.graphite));
    ctx.save();
    clipRR(ctx, vf);
    cover(ctx, env.logWide, vf, zoom);
    const sx = vf.x + vf.w * split;
    ctx.beginPath();
    ctx.rect(sx, vf.y, vf.x + vf.w - sx, vf.h);
    ctx.clip();
    cover(ctx, env.picWide, vf, zoom);
    ctx.restore();
    // Divider and knob.
    if (split < 0.995) {
      ctx.fillStyle = "#fff";
      ctx.fillRect(sx - 2, vf.y, 4, vf.h);
      ctx.beginPath();
      ctx.arc(sx, vf.y + vf.h / 2, 26, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = C.ink;
      for (const d of [-1, 1]) {
        ctx.beginPath();
        ctx.moveTo(sx + d * 6, vf.y + vf.h / 2 - 8);
        ctx.lineTo(sx + d * 14, vf.y + vf.h / 2);
        ctx.lineTo(sx + d * 6, vf.y + vf.h / 2 + 8);
        ctx.fill();
      }
    }
    ctx.save();
    clipRR(ctx, vf);
    const lp = clamp(spring(lt - 1.2, Spring.slow) * 1.5);
    ctx.globalAlpha *= lp;
    chip(ctx, vf.x + 22, vf.y + 22, "OCLog2", { size: 22, fill: "rgba(11,13,14,0.7)", color: "#fff" });
    const w = chipWidth(ctx, "Rec.709 LUT", 22);
    chip(ctx, vf.x + vf.w - 22 - w, vf.y + 22, "Rec.709 LUT", { size: 22, fill: C.amber, color: C.ink });
    ctx.restore();
  });
  const tags = ["10-bit HEVC", "HLG10", ".cube LUTs", "RAW DNG"];
  let total = tags.reduce((s, t) => s + chipWidth(ctx, t, 24) + 14, -14);
  let x = st.portrait ? 540 - total / 2 : vf.x;
  const y = vf.y + vf.h + 34;
  tags.forEach((t, i) => {
    const p = spring(lt - 1.6 - i * 0.1, Spring.spatial);
    if (p <= 0) return;
    ctx.save();
    ctx.globalAlpha = clamp(p * 1.5) * (1 - easeIn(out));
    const w = chip(ctx, x, y + (1 - p) * 20, t, { size: 24, fill: C.card, color: C.ink, stroke: C.line });
    ctx.restore();
    x += w + 14;
  });
  return [tb, vf];
}

// ── 5. Audio ─────────────────────────────────────────────────────────────────────────────────────
function level(t, ch) {
  // Follows the soundtrack's kick (120 BPM) so the meters dance with the music.
  const beat = ((t - 4) % 0.5 + 0.5) % 0.5;
  const kick = Math.exp(-beat * 9);
  const wob = 0.5 + 0.5 * Math.sin(t * 7.1 + ch * 1.9) * Math.sin(t * 2.3 + ch);
  return clamp(0.48 + 0.3 * kick + 0.12 * wob);
}

function meter(ctx, x, y, w, h, v, peak, label) {
  const segs = 44;
  const sw = w / segs;
  for (let i = 0; i < segs; i++) {
    const u = (i + 1) / segs;
    const on = u <= v;
    const col = u > 0.92 ? C.rec : u > 0.78 ? C.pending : C.ok;
    ctx.globalAlpha = on ? 1 : 0.14;
    fillRR(ctx, x + i * sw + 1.5, y, sw - 3, h, 3, col);
  }
  ctx.globalAlpha = 1;
  const px = x + w * peak;
  ctx.fillStyle = "#fff";
  ctx.fillRect(px - 2, y - 4, 3, h + 8);
  ctx.font = font(600, 20);
  ctx.fillStyle = C.onVariant;
  ctx.fillText(label, x - 30, y + h * 0.72);
}

function audio(ctx, env, lt, out, t) {
  const { st } = env;
  const L = layout(st);
  const tb = textBlock(ctx, { ...L.text, eyebrow: "Pro audio", title: "Sound that holds up.", sub: "Lossless WAV or FLAC sidecars, live meters, gain control and headphone monitoring." }, lt, out);
  const card = st.portrait ? { x: 70, y: 640, w: 940, h: 700 } : { x: 820, y: 230, w: 960, h: 560 };
  present(ctx, lt, out, card.x + card.w / 2, card.y + card.h / 2, 0.2, () => {
    shadowed(ctx, () => fillRR(ctx, card.x, card.y, card.w, card.h, 40, C.surface));
    const pad = 56;
    ctx.font = font(560, 20);
    ctx.letterSpacing = "1.6px";
    ctx.fillStyle = C.onVariant;
    ctx.fillText("INPUT · 48 KHZ · 24-BIT", card.x + pad, card.y + 66);
    ctx.letterSpacing = "0px";
    // Meter mode segmented control: Peak/RMS → VU → PPM.
    const modes = ["Peak/RMS", "VU", "PPM"];
    const m = lt < 2.4 ? 0 : lt < 4.0 ? 1 : 2;
    const seg = 120, sx = card.x + card.w - pad - seg * 3, sy = card.y + 34;
    fillRR(ctx, sx, sy, seg * 3, 50, 25, C.high);
    const mp = spring(lt - (m === 0 ? 0 : m === 1 ? 2.4 : 4.0), Spring.spatial);
    const from = m === 0 ? 0 : m - 1;
    fillRR(ctx, sx + lerp(from, m, m === 0 ? 1 : mp) * seg + 4, sy + 4, seg - 8, 42, 21, C.amber);
    ctx.textAlign = "center";
    ctx.font = font(600, 19);
    modes.forEach((name, i) => {
      ctx.fillStyle = i === m ? C.ink : C.onVariant;
      ctx.fillText(name, sx + seg * i + seg / 2, sy + 32);
    });
    ctx.textAlign = "left";
    const mx = card.x + pad + 34, mw = card.w - pad * 2 - 34;
    const top = card.y + (st.portrait ? 170 : 150);
    const hold = (ch) => {
      let p = 0;
      for (let k = 0; k < 30; k++) p = Math.max(p, level(t - k * 0.05, ch));
      return p;
    };
    const ballistics = m === 1 ? 0.7 : 1;
    meter(ctx, mx, top, mw, 30, level(t, 0) * ballistics, hold(0) * ballistics, "L");
    meter(ctx, mx, top + 52, mw, 30, level(t, 1) * ballistics, hold(1) * ballistics, "R");
    ctx.font = font(500, 17);
    ctx.fillStyle = C.onVariant;
    ctx.textAlign = "center";
    [-48, -36, -24, -12, -6, 0].forEach((db) => {
      const u = 1 + db / 54;
      ctx.fillText(String(db), mx + mw * u, top + 118);
    });
    ctx.textAlign = "left";
    // Scrolling waveform of the last seconds.
    const wy = top + (st.portrait ? 250 : 190), wh = card.y + card.h - pad - wy;
    fillRR(ctx, card.x + pad, wy, card.w - pad * 2, wh, 18, C.lowest);
    const bars = 96;
    const bw = (card.w - pad * 2 - 32) / bars;
    for (let i = 0; i < bars; i++) {
      const tt = t - (bars - i) * 0.03;
      const v = level(tt, 0) * (0.6 + 0.4 * Math.abs(Math.sin(tt * 37)));
      const hgt = v * (wh - 40);
      ctx.fillStyle = i > bars - 4 ? C.amber : "rgba(255,255,255,0.75)";
      fillRR(ctx, card.x + pad + 16 + i * bw, wy + wh / 2 - hgt / 2, Math.max(2, bw - 3), hgt, 2, ctx.fillStyle);
    }
  });
  const tags = ["WAV", "FLAC", "AAC in MP4", "Headphone monitoring"];
  let total = tags.reduce((s, x) => s + chipWidth(ctx, x, 24) + 14, -14);
  let x = st.portrait ? 540 - total / 2 : card.x;
  const y = card.y + card.h + 36;
  tags.forEach((tag, i) => {
    const p = spring(lt - 1.2 - i * 0.1, Spring.spatial);
    if (p <= 0) return;
    ctx.save();
    ctx.globalAlpha = clamp(p * 1.5) * (1 - easeIn(out));
    const w = chip(ctx, x, y + (1 - p) * 20, tag, { size: 24, fill: C.card, stroke: C.line });
    ctx.restore();
    x += w + 14;
  });
  return [tb, card];
}

// ── 6. Timecode and slate ────────────────────────────────────────────────────────────────────────
function timecode(ctx, env, lt, out) {
  const { st } = env;
  const L = layout(st);
  const tb = textBlock(ctx, { ...L.text, eyebrow: "On set", title: "Timecode and slate, built in.", sub: "SMPTE timecode on every take, with the production slate saved beside it." }, lt, out);
  const tc = st.portrait ? { x: 70, y: 640, w: 940, h: 250 } : { x: 820, y: 170, w: 960, h: 250 };
  const sl = st.portrait ? { x: 70, y: 920, w: 940, h: 560 } : { x: 820, y: 450, w: 960, h: 460 };
  present(ctx, lt, out, tc.x + tc.w / 2, tc.y + tc.h / 2, 0.2, () => {
    shadowed(ctx, () => fillRR(ctx, tc.x, tc.y, tc.w, tc.h, 40, C.surface));
    ctx.fillStyle = C.rec;
    ctx.beginPath();
    ctx.arc(tc.x + 62, tc.y + 64, 9, 0, Math.PI * 2);
    ctx.fill();
    ctx.font = font(560, 20);
    ctx.letterSpacing = "1.6px";
    ctx.fillStyle = C.onVariant;
    ctx.fillText("REC RUN · 24 FPS", tc.x + 84, tc.y + 71);
    ctx.letterSpacing = "0px";
    ctx.font = font(560, st.portrait ? 120 : 124, Mono);
    ctx.fillStyle = "#fff";
    const frames = 1 * 3600 * 24 + 4 * 60 * 24 + 22 * 24 + Math.max(0, lt) * 24;
    ctx.fillText(fmtTC(frames), tc.x + 56, tc.y + tc.h - 52);
  });
  present(ctx, lt, out, sl.x + sl.w / 2, sl.y + sl.h / 2, 0.4, () => {
    shadowed(ctx, () => fillRR(ctx, sl.x, sl.y, sl.w, sl.h, 40, C.card), 0.8);
    // Clapper stripes along the top.
    ctx.save();
    rrect(ctx, sl.x, sl.y, sl.w, 62, [40, 40, 0, 0]);
    ctx.clip();
    ctx.fillStyle = C.ink;
    ctx.fillRect(sl.x, sl.y, sl.w, 62);
    ctx.fillStyle = C.amber;
    for (let x = sl.x - 60; x < sl.x + sl.w; x += 92) {
      ctx.beginPath();
      ctx.moveTo(x, sl.y + 62); ctx.lineTo(x + 46, sl.y + 62); ctx.lineTo(x + 92, sl.y); ctx.lineTo(x + 46, sl.y);
      ctx.fill();
    }
    ctx.restore();
    const take = lt < 1.6 ? 2 : 3;
    const tp = spring(lt - 1.6, Spring.fast);
    const fields = [["PROJECT", "Lakeside"], ["SCENE", "12A"], ["TAKE", String(take)], ["CAMERA", "A"], ["REEL", "004"], ["LENS", "24 mm"]];
    const cols = 3, fw = (sl.w - 80) / cols;
    fields.forEach(([k, v], i) => {
      const fx = sl.x + 40 + (i % cols) * fw, fy = sl.y + 120 + Math.floor(i / cols) * (st.portrait ? 150 : 120);
      ctx.font = font(560, 18);
      ctx.letterSpacing = "1.4px";
      ctx.fillStyle = C.ink2;
      ctx.fillText(k, fx, fy);
      ctx.letterSpacing = "0px";
      ctx.save();
      ctx.font = font(640, 46);
      ctx.fillStyle = C.ink;
      const dy = k === "TAKE" && lt >= 1.6 ? (1 - tp) * 30 : 0;
      ctx.globalAlpha *= k === "TAKE" && lt >= 1.6 ? clamp(tp * 2) : 1;
      ctx.fillText(v, fx, fy + 54 + dy);
      ctx.restore();
    });
    let x = sl.x + 40;
    const cy = sl.y + sl.h - 92;
    ["EXT", "DAY"].forEach((t) => { x += chip(ctx, x, cy, t, { size: 22, fill: C.amberC, color: C.amberDeep }) + 12; });
    const gp = spring(lt - 2.3, Spring.fast);
    if (gp > 0) {
      ctx.save();
      const w = chipWidth(ctx, "Good take", 22, 22, 22 * 0.75);
      ctx.translate(x + w / 2, cy + 22);
      ctx.scale(gp, gp);
      chip(ctx, -w / 2, -22, "Good take", { size: 22, fill: C.sageC, color: C.okDeep, dot: C.okDeep });
      ctx.restore();
    }
  });
  return [tb, tc, sl];
}

// ── 7. Gallery ───────────────────────────────────────────────────────────────────────────────────
const Takes = [
  { name: "A004_C012", meta: "4K · 25 fps · 00:42", badge: "HEVC 10-bit · LOG", v: 0, proxy: true },
  { name: "A004_C013", meta: "4K · 25 fps · 01:18", badge: "HEVC 10-bit · LOG", v: 1 },
  { name: "IMG_0412", meta: "12 MP · RAW", badge: "DNG", v: 2 },
  { name: "A004_C014", meta: "1080p · 240 fps · 00:09", badge: "AVC · SLOW", v: 3 },
  { name: "SND_0031", meta: "48 kHz · 24-bit · 02:05", badge: "WAV", audio: true },
  { name: "IMG_0413", meta: "12 MP · HEIC", badge: "HEIC", v: 4 },
];

function gallery(ctx, env, lt, out) {
  const { st } = env;
  const L = layout(st);
  const tb = textBlock(ctx, { ...L.text, eyebrow: "Media", title: "Every take, organised.", sub: "Search, editing proxies, sharing and optional WebDAV upload over HTTPS." }, lt, out);
  const card = st.portrait ? { x: 60, y: 590, w: 960, h: 1240 } : { x: 800, y: 110, w: 1000, h: 860 };
  present(ctx, lt, out, card.x + card.w / 2, card.y + card.h / 2, 0.2, () => {
    shadowed(ctx, () => fillRR(ctx, card.x, card.y, card.w, card.h, 44, C.surface));
    const pad = 40;
    ctx.font = font(640, 40);
    ctx.fillStyle = "#fff";
    ctx.fillText("Media", card.x + pad, card.y + 78);
    const sw = 300;
    fillRR(ctx, card.x + card.w - pad - sw, card.y + 40, sw, 54, 27, C.high);
    ctx.font = font(450, 21);
    ctx.fillStyle = C.onVariant;
    ctx.fillText("Search takes", card.x + card.w - pad - sw + 54, card.y + 74);
    ctx.strokeStyle = C.onVariant;
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    ctx.arc(card.x + card.w - pad - sw + 30, card.y + 65, 8, 0, Math.PI * 2);
    ctx.stroke();
    let x = card.x + pad;
    ["All", "Videos", "Photos", "Audio", "Log"].forEach((f, i) => {
      x += chip(ctx, x, card.y + 118, f, { size: 21, fill: i === 0 ? C.primaryContainer : C.high, color: i === 0 ? C.onPrimaryContainer : C.onVariant }) + 10;
    });
    const cols = st.portrait ? 2 : 3;
    const gap = 22;
    const gw = card.w - pad * 2;
    const tw = (gw - gap * (cols - 1)) / cols, th = tw * 9 / 16;
    const rowH = th + 92;
    Takes.forEach((tk, i) => {
      const col = i % cols, row = Math.floor(i / cols);
      const tx = card.x + pad + col * (tw + gap), ty = card.y + 200 + row * (rowH + 18);
      const p = spring(lt - 0.45 - i * 0.08, Spring.spatial);
      if (p <= 0) return;
      ctx.save();
      ctx.globalAlpha *= clamp(p * 1.5);
      ctx.translate(0, (1 - p) * 30);
      const r = { x: tx, y: ty, w: tw, h: th, r: 18 };
      ctx.save();
      clipRR(ctx, r);
      if (tk.audio) {
        ctx.fillStyle = C.high;
        ctx.fillRect(r.x, r.y, r.w, r.h);
        for (let b = 0; b < 40; b++) {
          const hgt = (0.2 + 0.8 * Math.abs(Math.sin(b * 1.7) * Math.sin(b * 0.37))) * r.h * 0.6;
          fillRR(ctx, r.x + 22 + b * ((r.w - 44) / 40), r.y + r.h / 2 - hgt / 2, 4, hgt, 2, C.tertiary);
        }
      } else cover(ctx, env.thumbs[tk.v], r, 1.02);
      ctx.restore();
      chip(ctx, tx + 12, ty + 12, tk.badge, { size: 15, h: 30, pad: 12, fill: "rgba(11,13,14,0.72)", color: "#fff", weight: 600 });
      if (tk.proxy) {
        const prog = easeInOut(phase(lt, 1.2, 3.6));
        const done = spring(lt - 3.7, Spring.fast);
        const pcx = tx + tw - 34, pcy = ty + 34;
        if (done <= 0) {
          ctx.fillStyle = "rgba(11,13,14,0.72)";
          ctx.beginPath(); ctx.arc(pcx, pcy, 20, 0, Math.PI * 2); ctx.fill();
          ctx.strokeStyle = "rgba(255,255,255,0.25)";
          ctx.lineWidth = 4;
          ctx.beginPath(); ctx.arc(pcx, pcy, 13, 0, Math.PI * 2); ctx.stroke();
          ctx.strokeStyle = C.amber;
          ctx.lineCap = "round";
          ctx.beginPath(); ctx.arc(pcx, pcy, 13, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * lerp(0.3, 1, prog)); ctx.stroke();
          ctx.lineCap = "butt";
        } else {
          const w = chipWidth(ctx, "Proxy ready", 15, 12, 15 * 0.75);
          ctx.save();
          ctx.translate(tx + tw - 12 - w / 2, ty + 27);
          ctx.scale(done, done);
          chip(ctx, -w / 2, -15, "Proxy ready", { size: 15, h: 30, pad: 12, fill: "#10301F", color: C.ok, dot: C.ok, weight: 600 });
          ctx.restore();
        }
      }
      ctx.font = font(600, 21, Mono);
      ctx.fillStyle = "#fff";
      ctx.fillText(tk.name, tx + 4, ty + th + 38);
      ctx.font = font(450, 18);
      ctx.fillStyle = C.onVariant;
      ctx.fillText(tk.meta, tx + 4, ty + th + 68);
      ctx.restore();
    });
  });
  return [tb, card];
}

// ── 8. Foldables ─────────────────────────────────────────────────────────────────────────────────
const SubjectModes = ["Status", "Teleprompter", "Preview", "Fill light", "Review", "Interview", "Slate"];
const Prompter = ["Thanks for coming in.", "Tell us how the project", "started, in your own words.", "What surprised you most?", "Take your time.", "Whenever you are ready."];

function coverContent(ctx, env, scr, mode, lt, local) {
  ctx.save();
  clipRR(ctx, scr);
  const cx = scr.x + scr.w / 2, cy = scr.y + scr.h / 2;
  if (mode === 0) {
    ctx.fillStyle = "#000";
    ctx.fillRect(scr.x, scr.y, scr.w, scr.h);
    ctx.strokeStyle = C.rec;
    ctx.lineWidth = 18;
    rrect(ctx, scr.x + 9, scr.y + 9, scr.w - 18, scr.h - 18, scr.r - 9);
    ctx.stroke();
    ctx.textAlign = "center";
    ctx.fillStyle = C.rec;
    ctx.beginPath(); ctx.arc(cx - 58, cy - 92, 13, 0, Math.PI * 2); ctx.fill();
    ctx.font = font(700, 40);
    ctx.fillText("REC", cx + 16, cy - 78);
    ctx.font = font(560, 74, Mono);
    ctx.fillStyle = "#fff";
    ctx.fillText(fmtTC(24 * 12 + local * 24).slice(3), cx, cy + 20);
    ctx.font = font(500, 26);
    ctx.fillStyle = C.onVariant;
    ctx.fillText("Scene 12A · Take 3", cx, cy + 74);
  } else if (mode === 1) {
    ctx.fillStyle = "#000";
    ctx.fillRect(scr.x, scr.y, scr.w, scr.h);
    ctx.textAlign = "center";
    const scroll = local * 34;
    Prompter.forEach((line, i) => {
      const y = scr.y + 90 + i * 64 - scroll;
      const d = Math.abs(y - (cy - 20));
      ctx.globalAlpha = clamp(1 - d / 220) * 0.95 + 0.05;
      ctx.font = font(d < 40 ? 640 : 480, 36);
      ctx.fillStyle = "#fff";
      ctx.fillText(line, cx, y);
    });
    ctx.globalAlpha = 1;
  } else if (mode === 3) {
    ctx.fillStyle = "#FFE7C8";
    ctx.fillRect(scr.x, scr.y, scr.w, scr.h);
    ctx.textAlign = "center";
    ctx.font = font(600, 30);
    ctx.fillStyle = "rgba(16,20,23,0.55)";
    ctx.fillText("3200 K · 80 %", cx, scr.y + scr.h - 60);
  } else {
    ctx.fillStyle = "#000";
    ctx.fillRect(scr.x, scr.y, scr.w, scr.h);
    const flash = 1 - clamp(local / 0.25);
    ctx.textAlign = "center";
    ctx.font = font(560, 24);
    ctx.letterSpacing = "1.6px";
    ctx.fillStyle = C.onVariant;
    ctx.fillText("SCENE", cx - 90, cy - 70);
    ctx.fillText("TAKE", cx + 100, cy - 70);
    ctx.letterSpacing = "0px";
    ctx.font = font(700, 96);
    ctx.fillStyle = "#fff";
    ctx.fillText("12A", cx - 90, cy + 20);
    ctx.fillStyle = C.amber;
    ctx.fillText("3", cx + 100, cy + 20);
    ctx.font = font(500, 26);
    ctx.fillStyle = C.onVariant;
    ctx.fillText("Camera A · Reel 004", cx, cy + 90);
    if (flash > 0) {
      ctx.fillStyle = `rgba(255,255,255,${flash})`;
      ctx.fillRect(scr.x, scr.y, scr.w, scr.h);
    }
  }
  ctx.textAlign = "left";
  ctx.restore();
}

function foldables(ctx, env, lt, out) {
  const { st } = env;
  const L = layout(st);
  const tb = textBlock(ctx, { ...L.text, eyebrow: "Foldables", title: "Made for foldables.", sub: "The cover screen faces your subject: tally, teleprompter, fill light, slate and more." }, lt, out);
  const dw = st.portrait ? 560 : 500, dh = dw * 1.22;
  const dcx = st.portrait ? 540 : 1120, dcy = st.portrait ? 1110 : 540;
  const order = [0, 1, 3, 6];
  const slot = Math.max(0, Math.min(3, Math.floor((lt - 0.6) / 1.3)));
  const slotStart = 0.6 + slot * 1.3;
  const mode = order[slot];
  present(ctx, lt, out, dcx, dcy, 0.2, () => {
    const x = dcx - dw / 2, y = dcy - dh / 2;
    shadowed(ctx, () => fillRR(ctx, x, y, dw, dh, 72, "#1a1f22"));
    fillRR(ctx, x + 3, y + 3, dw - 6, dh - 6, 69, "#08090a");
    const scr = { x: x + 16, y: y + 16, w: dw - 32, h: dh - 32, r: 58 };
    // Previous mode under a growing circle of the current one (a Material container transform).
    const prev = slot > 0 ? order[slot - 1] : null;
    const reveal = slot > 0 ? easeInOut(phase(lt, slotStart, slotStart + 0.5)) : 1;
    if (prev !== null && reveal < 1) coverContent(ctx, env, scr, prev, lt, lt - (slotStart - 1.3));
    ctx.save();
    if (reveal < 1) {
      ctx.beginPath();
      ctx.arc(scr.x + scr.w / 2, scr.y + scr.h / 2, Math.hypot(scr.w, scr.h) * 0.55 * reveal, 0, Math.PI * 2);
      ctx.clip();
    }
    coverContent(ctx, env, scr, mode, lt, lt - slotStart);
    ctx.restore();
    // The two rear lenses sit in cut-outs of the cover screen.
    const lr = dw * 0.075;
    for (const [lx, ly] of [[x + dw * 0.16, y + dh * 0.86], [x + dw * 0.36, y + dh * 0.86]]) {
      ctx.fillStyle = "#08090a";
      ctx.beginPath(); ctx.arc(lx, ly, lr + 10, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = "#1e2428";
      ctx.beginPath(); ctx.arc(lx, ly, lr, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = "#0b1218";
      ctx.beginPath(); ctx.arc(lx, ly, lr * 0.62, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = "rgba(120,170,210,0.35)";
      ctx.beginPath(); ctx.arc(lx - lr * 0.2, ly - lr * 0.2, lr * 0.18, 0, Math.PI * 2); ctx.fill();
    }
  });
  // The seven subject modes; the shown one is highlighted.
  const chipSize = 24;
  const fade = 1 - easeIn(out);
  if (st.portrait) {
    const rows = [SubjectModes.slice(0, 4), SubjectModes.slice(4)];
    rows.forEach((r, ri) => {
      const total = r.reduce((s, m) => s + chipWidth(ctx, m, chipSize) + 12, -12);
      let x = 540 - total / 2;
      r.forEach((m) => {
        const i = SubjectModes.indexOf(m);
        const p = spring(lt - 0.5 - i * 0.06, Spring.spatial);
        ctx.save();
        ctx.globalAlpha = clamp(p * 1.5) * fade;
        x += chip(ctx, x, 1560 + ri * 70 + (1 - p) * 20, m, { size: chipSize, fill: i === mode ? C.amber : C.card, stroke: i === mode ? null : C.line }) + 12;
        ctx.restore();
      });
    });
  } else {
    SubjectModes.forEach((m, i) => {
      const p = spring(lt - 0.5 - i * 0.06, Spring.spatial);
      ctx.save();
      ctx.globalAlpha = clamp(p * 1.5) * fade;
      chip(ctx, 1460 + (1 - p) * 24, 290 + i * 70, m, { size: chipSize, fill: i === mode ? C.amber : C.card, stroke: i === mode ? null : C.line });
      ctx.restore();
    });
  }
  return [tb, { x: dcx - dw / 2, y: dcy - dh / 2, w: dw, h: dh }];
}

// ── 9. Keyboard ──────────────────────────────────────────────────────────────────────────────────
const Keys = [["R", "Record"], ["F", "Focus"], ["Z", "Zebra"], ["P", "Peaking"], ["G", "Grid"], ["H", "Scopes"], ["L", "Log view"]];

function keyboard(ctx, env, lt, out) {
  const { st } = env;
  const L = layout(st);
  const tb = textBlock(ctx, { ...L.text, eyebrow: "Controls", title: "Fast with a keyboard.", sub: "Shortcuts on tablets and desktop windows, assignable buttons and C1/C2 presets." }, lt, out);
  const k = st.portrait ? 170 : 116, gap = st.portrait ? 28 : 22;
  const rows = st.portrait ? [Keys.slice(0, 4), Keys.slice(4)] : [Keys];
  const fade = 1 - easeIn(out);
  let idx = 0;
  const top = st.portrait ? 900 : 400;
  rows.forEach((row, ri) => {
    const total = row.length * k + (row.length - 1) * gap;
    const x0 = (st.portrait ? 540 : 1300) - total / 2;
    row.forEach(([key, label], i) => {
      const n = idx++;
      const x = x0 + i * (k + gap), y = top + ri * (k + 110);
      const p = spring(lt - 0.3 - n * 0.06, Spring.spatial);
      if (p <= 0) return;
      const press = phase(lt, 1.0 + n * 0.36, 1.0 + n * 0.36 + 0.12) - phase(lt, 1.22 + n * 0.36, 1.4 + n * 0.36);
      const lit = phase(lt, 1.0 + n * 0.36, 1.1 + n * 0.36);
      ctx.save();
      ctx.globalAlpha = clamp(p * 1.5) * fade;
      ctx.translate(0, (1 - p) * 30);
      fillRR(ctx, x, y + 8, k, k, 26, lit > 0 ? "#C98D00" : "#D8D4CB");
      const dy = 6 * clamp(press);
      fillRR(ctx, x, y + dy, k, k, 26, lit > 0 ? C.amber : C.card);
      ctx.textAlign = "center";
      ctx.font = font(620, k * 0.42);
      ctx.fillStyle = C.ink;
      ctx.fillText(key, x + k / 2, y + dy + k * 0.62);
      ctx.font = font(500, st.portrait ? 27 : 22);
      ctx.fillStyle = lit > 0 ? C.ink : C.ink2;
      ctx.fillText(label, x + k / 2, y + k + 52);
      ctx.textAlign = "left";
      ctx.restore();
    });
  });
  const tags = ["C1 · C2 presets", "Volume keys", "Assignable buttons"];
  const total = tags.reduce((s, x) => s + chipWidth(ctx, x, 24) + 14, -14);
  let x = (st.portrait ? 540 : 1300) - total / 2;
  const y = st.portrait ? 1420 : 680;
  tags.forEach((tag, i) => {
    const p = spring(lt - 2.0 - i * 0.1, Spring.spatial);
    if (p <= 0) return;
    ctx.save();
    ctx.globalAlpha = clamp(p * 1.5) * fade;
    x += chip(ctx, x, y + (1 - p) * 20, tag, { size: 24, fill: C.cyanC, color: C.cyanDeep }) + 14;
    ctx.restore();
  });
  return [tb, { x: 800, y: top, w: 1000, h: 400 }];
}

// ── 10. Privacy ──────────────────────────────────────────────────────────────────────────────────
function privacy(ctx, env, lt, out) {
  const { st } = env;
  const items = [
    { text: ["No", "account"], shape: Shapes.cookie9, fill: C.amberC, to: Shapes.cookie6 },
    { text: ["No", "ads"], shape: Shapes.circle, fill: C.cyanC, to: Shapes.cookie4 },
    { text: ["No", "tracking"], shape: Shapes.sunny, fill: C.sageC, to: Shapes.scallop },
  ];
  const r = st.portrait ? 150 : 140;
  const gap = st.portrait ? 36 : 70;
  const cy = st.portrait ? 760 : 360;
  const fade = 1 - easeIn(out);
  items.forEach((it, i) => {
    const cx = st.W / 2 + (i - 1) * (r * 2 + gap);
    const p = spring(lt - 0.2 - i * 0.14, Spring.spatial);
    if (p <= 0) return;
    const m = easeInOut(phase(lt, 1.6 + i * 0.15, 2.6 + i * 0.15));
    ctx.save();
    ctx.globalAlpha = clamp(p * 1.5) * fade;
    fillShape(ctx, morph(it.shape, it.to, m), cx, cy, r * p, lt * 0.18 * (i % 2 ? -1 : 1), it.fill);
    ctx.textAlign = "center";
    ctx.font = font(640, st.portrait ? 38 : 34);
    ctx.fillStyle = C.ink;
    ctx.fillText(it.text[0], cx, cy - 4);
    ctx.fillText(it.text[1], cx, cy + 38);
    ctx.textAlign = "left";
    ctx.restore();
  });
  const tb = textBlock(ctx, { x: st.W / 2, y: st.portrait ? 1020 : 590, align: "center", maxW: st.portrait ? 900 : 1100, size: st.portrait ? 92 : 96, subSize: st.portrait ? 32 : 32, title: "Yours. Only yours.", sub: "Open source under Apache 2.0. No account, no ads, no analytics. Everything stays on your device.", delay: 0.6 }, lt, out);
  return [tb, { x: st.W / 2 - 3 * r - gap, y: cy - r, w: 6 * r + 2 * gap, h: 2 * r }];
}

// ── 11. End card ─────────────────────────────────────────────────────────────────────────────────
function endCard(ctx, env, lt, out) {
  const { st } = env;
  const cx = st.W / 2;
  const size = st.portrait ? 300 : 240;
  const cy = st.portrait ? 720 : 330;
  const fade = 1 - easeIn(out);
  const p = spring(lt - 0.15, Spring.slow);
  drawLogo(ctx, cx, cy, size * lerp(0.85, 1, p), { iris: p, ring: easeInOut(phase(lt, 0.1, 0.8)), arcs: easeInOut(phase(lt, 0.3, 1.0)), dot: spring(lt - 0.8, Spring.fast), alpha: clamp(p * 1.5) * fade });
  const lines = [
    { t: "OpenCineCam", f: font(660, st.portrait ? 112 : 104), c: C.ink, y: cy + size / 2 + (st.portrait ? 150 : 130), d: 0.5, ls: -3 },
    { t: "The open-source cinema camera for Android.", f: font(430, st.portrait ? 38 : 34), c: C.ink2, y: cy + size / 2 + (st.portrait ? 230 : 200), d: 0.7, ls: 0 },
  ];
  ctx.textAlign = "center";
  for (const l of lines) {
    const q = spring(lt - l.d, Spring.slow);
    ctx.globalAlpha = clamp(q * 1.5) * fade;
    ctx.font = l.f;
    ctx.letterSpacing = `${l.ls}px`;
    ctx.fillStyle = l.c;
    ctx.fillText(l.t, cx, l.y + (1 - q) * 26);
  }
  ctx.letterSpacing = "0px";
  ctx.textAlign = "left";
  const url = "github.com/librestatic/opencinecam";
  const q = spring(lt - 1.0, Spring.spatial);
  const w = chipWidth(ctx, url, 28, 30);
  ctx.globalAlpha = clamp(q * 1.5) * fade;
  const uy = cy + size / 2 + (st.portrait ? 300 : 262);
  chip(ctx, cx - w / 2, uy + (1 - q) * 20, url, { size: 28, pad: 30, fill: C.ink, color: "#fff" });
  ctx.globalAlpha = 1;
  return [{ x: cx - 500, y: cy - size / 2, w: 1000, h: size + 400 }];
}

export const Scenes = [
  { start: 0, end: 4, draw: intro },
  { start: 4, end: 10, draw: manual },
  { start: 10, end: 16, draw: modes },
  { start: 16, end: 24, draw: monitoring },
  { start: 24, end: 30, draw: logScene },
  { start: 30, end: 36, draw: audio },
  { start: 36, end: 40, draw: timecode },
  { start: 40, end: 46, draw: gallery },
  { start: 46, end: 52, draw: foldables },
  { start: 52, end: 56, draw: keyboard },
  { start: 56, end: 60, draw: privacy },
  { start: 60, end: 66, draw: endCard, hold: true },
];

export const Duration = 66;
