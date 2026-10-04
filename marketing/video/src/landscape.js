/* SPDX-License-Identifier: Apache-2.0 */
/*
 * A calm procedural landscape (lake, ridges, pines, low sun) standing in for the camera feed, and the
 * monitoring tools computed from its real pixels: zebra, focus peaking, false colour, Log, and the
 * waveform, vectorscope and histogram data. Nothing is painted on by hand.
 */
import { rng } from "./motion.js";

const Variants = {
  dusk: { sky: ["#294a66", "#5f86a3", "#c9b6a6", "#f3c79a"], sun: "#fff4dc", glow: "rgba(255,214,160,", far: "#7d8ea0", mid: "#4b6277", near: "#1d2a35", water: ["#e8bd93", "#6f8ca3", "#22394b"], pine: "#0c1419", sunY: 0.5 },
  day: { sky: ["#3f7fb3", "#78aed2", "#b9d7e6", "#e6eef0"], sun: "#ffffff", glow: "rgba(255,255,240,", far: "#93abbd", mid: "#5b7b6b", near: "#2e4a37", water: ["#c8dde6", "#6f9bb5", "#2b5068"], pine: "#14261b", sunY: 0.2 },
  night: { sky: ["#0a1422", "#14263a", "#2c3f55", "#4d5e70"], sun: "#e9eef5", glow: "rgba(200,220,255,", far: "#33465a", mid: "#22313f", near: "#0d151d", water: ["#3f5266", "#1a2a3a", "#0a121a"], pine: "#05090c", sunY: 0.3 },
  fog: { sky: ["#8c9aa3", "#aab5bb", "#c9d0d2", "#dfe2e0"], sun: "#f4f2ea", glow: "rgba(255,250,235,", far: "#b3bcc0", mid: "#8f9ca2", near: "#59666c", water: ["#cfd4d3", "#a4afb3", "#6f7d83"], pine: "#2e383d", sunY: 0.35 },
  amber: { sky: ["#3b2f3a", "#8a5a4a", "#e09a63", "#ffd08a"], sun: "#fff6e0", glow: "rgba(255,200,120,", far: "#9b7262", mid: "#5e4448", near: "#271c22", water: ["#f0b070", "#9a6650", "#2c2026"], pine: "#120c10", sunY: 0.55 },
};

function ridge(random, w, base, amp, roughness) {
  const p = [random() * 10, random() * 10, random() * 10];
  const pts = [];
  for (let x = 0; x <= w; x += Math.max(2, w / 240)) {
    const u = x / w;
    const y = base
      - amp * (0.55 * Math.sin(u * 3.1 + p[0]) + 0.3 * Math.sin(u * 7.3 + p[1]) + roughness * Math.sin(u * 23 + p[2]))
      - amp * 0.35 * Math.pow(Math.sin(u * Math.PI), 1.5);
    pts.push([x, y]);
  }
  return pts;
}

function fillRidge(ctx, pts, w, h, color) {
  ctx.beginPath();
  ctx.moveTo(0, h);
  for (const [x, y] of pts) ctx.lineTo(x, y);
  ctx.lineTo(w, h);
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.fill();
}

function pine(ctx, x, base, height, color) {
  const w = height * 0.32;
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(x, base - height);
  const tiers = 6;
  for (let i = 1; i <= tiers; i++) {
    const y = base - height + (height * 0.92 * i) / tiers;
    const half = (w / 2) * (0.35 + 0.65 * (i / tiers));
    ctx.lineTo(x + half, y);
    ctx.lineTo(x + half * 0.45, y - height * 0.04);
  }
  ctx.lineTo(x + w * 0.05, base);
  ctx.lineTo(x - w * 0.05, base);
  for (let i = tiers; i >= 1; i--) {
    const y = base - height + (height * 0.92 * i) / tiers;
    const half = (w / 2) * (0.35 + 0.65 * (i / tiers));
    ctx.lineTo(x - half * 0.45, y - height * 0.04);
    ctx.lineTo(x - half, y);
  }
  ctx.closePath();
  ctx.fill();
}

/** Draws the landscape into a fresh canvas of w x h. */
export function renderLandscape(w, h, variant = "dusk", seed = 11) {
  const v = Variants[variant];
  const random = rng(seed);
  const c = new OffscreenCanvas(w, h);
  const ctx = c.getContext("2d");
  const horizon = h * 0.6;
  const s = Math.min(w, h);

  const sky = ctx.createLinearGradient(0, 0, 0, horizon);
  v.sky.forEach((col, i) => sky.addColorStop(i / (v.sky.length - 1), col));
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, w, horizon + 2);

  // Sun and its halo.
  const sx = w * 0.66, sy = horizon * v.sunY + horizon * 0.42 * (1 - v.sunY) * 0.6, sr = s * 0.045;
  const halo = ctx.createRadialGradient(sx, sy, sr * 0.5, sx, sy, sr * 9);
  halo.addColorStop(0, v.glow + "0.75)");
  halo.addColorStop(0.25, v.glow + "0.28)");
  halo.addColorStop(1, v.glow + "0)");
  ctx.fillStyle = halo;
  ctx.fillRect(0, 0, w, horizon);
  ctx.fillStyle = v.sun;
  ctx.beginPath();
  ctx.arc(sx, sy, sr, 0, Math.PI * 2);
  ctx.fill();

  // Thin cloud bands lit from below.
  for (let i = 0; i < 5; i++) {
    const cy = horizon * (0.18 + random() * 0.5);
    const cw = w * (0.25 + random() * 0.35);
    const cx = random() * w;
    const g = ctx.createLinearGradient(0, cy - 8, 0, cy + 10);
    g.addColorStop(0, "rgba(255,255,255,0)");
    g.addColorStop(0.6, v.glow + "0.22)");
    g.addColorStop(1, "rgba(255,255,255,0)");
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.ellipse(cx, cy, cw / 2, s * 0.012 + random() * s * 0.01, 0, 0, Math.PI * 2);
    ctx.fill();
  }

  const far = ridge(random, w, horizon - s * 0.02, s * 0.16, 0.12);
  const mid = ridge(random, w, horizon + s * 0.005, s * 0.09, 0.2);
  fillRidge(ctx, far, w, horizon + 2, v.far);
  fillRidge(ctx, mid, w, horizon + 2, v.mid);

  // Water: the sky mirrored and darkened, with the sun's streak and soft ripples.
  const water = ctx.createLinearGradient(0, horizon, 0, h);
  v.water.forEach((col, i) => water.addColorStop(i / (v.water.length - 1), col));
  ctx.fillStyle = water;
  ctx.fillRect(0, horizon, w, h - horizon);
  ctx.save();
  ctx.globalAlpha = 0.35;
  ctx.translate(0, horizon * 2);
  ctx.scale(1, -1);
  fillRidge(ctx, far, w, horizon + 2, v.far);
  fillRidge(ctx, mid, w, horizon + 2, v.mid);
  ctx.restore();
  for (let i = 0; i < 26; i++) {
    const y = horizon + (h - horizon) * Math.pow(i / 26, 1.6) + 2;
    const len = sr * (5 - 3.5 * (i / 26)) * (0.7 + random() * 0.6);
    ctx.fillStyle = v.glow + (0.55 - i * 0.018) + ")";
    ctx.fillRect(sx - len / 2 + (random() - 0.5) * sr, y, len, 1 + i * 0.12);
  }
  ctx.fillStyle = v.sun;
  ctx.globalAlpha = 0.9;
  ctx.fillRect(sx - sr * 1.1, horizon + 1, sr * 2.2, Math.max(2, s * 0.004));
  ctx.globalAlpha = 1;

  // Near shore with pines: hard, sharp edges for focus peaking.
  const shore = ridge(random, w, h * 0.74, s * 0.05, 0.3).map(([x, y]) => [x, y + (x / w) * h * 0.1]);
  ctx.beginPath();
  ctx.moveTo(0, h);
  for (const [x, y] of shore) ctx.lineTo(x, x < w * 0.42 ? y : Math.max(y, h * 0.74 + (x - w * 0.42) * 0.9));
  ctx.lineTo(w, h);
  ctx.closePath();
  ctx.fillStyle = v.near;
  ctx.fill();
  for (let i = 0; i < 9; i++) {
    const x = w * (0.02 + i * 0.045 + random() * 0.02);
    const base = h * 0.76 + random() * h * 0.03;
    pine(ctx, x, base, s * (0.16 + random() * 0.14) * (1 - i * 0.05), v.pine);
  }
  return c;
}

const luma = (r, g, b) => (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;

/** The app's Log curve, approximated for display: lifted shadows, held highlights, less saturation. */
export function toLog(src) {
  const { width: w, height: h } = src;
  const c = new OffscreenCanvas(w, h);
  const ctx = c.getContext("2d");
  ctx.drawImage(src, 0, 0);
  const img = ctx.getImageData(0, 0, w, h);
  const d = img.data;
  const curve = (x) => 0.12 + 0.62 * (Math.log2(1 + 10 * x) / Math.log2(11));
  for (let i = 0; i < d.length; i += 4) {
    const r = d[i] / 255, g = d[i + 1] / 255, b = d[i + 2] / 255;
    const y = luma(d[i], d[i + 1], d[i + 2]);
    const k = 0.55;
    d[i] = 255 * curve(y + (r - y) * k);
    d[i + 1] = 255 * curve(y + (g - y) * k);
    d[i + 2] = 255 * curve(y + (b - y) * k);
  }
  ctx.putImageData(img, 0, 0);
  return c;
}

const ZebraHigh = 0.92; // MonitoringOptions.zebraHighPercent
const FalseColor = [ // FalseColorPalette.CLASSIC bands
  [0.025, [108, 66, 193]], [0.18, [22, 140, 255]], [0.7, [64, 182, 106]], [0.97, [255, 255, 0]], [2, [255, 0, 0]],
];

/** Overlays at full size plus scope data, all computed from the picture's pixels. */
export function analyse(src) {
  const { width: w, height: h } = src;
  const ctx = src.getContext("2d");
  const d = ctx.getImageData(0, 0, w, h).data;
  const Y = new Float32Array(w * h);
  for (let i = 0, p = 0; p < Y.length; p++, i += 4) Y[p] = luma(d[i], d[i + 1], d[i + 2]);

  const make = () => {
    const c = new OffscreenCanvas(w, h);
    const x = c.getContext("2d");
    return [c, x, x.createImageData(w, h)];
  };
  const [zc, zx, zi] = make();
  const [pc, px, pi] = make();
  const [fc, fx, fi] = make();
  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const p = y * w + x, o = p * 4;
      if (Y[p] >= ZebraHigh) { zi.data[o] = 255; zi.data[o + 1] = 255; zi.data[o + 3] = 255; }
      const gx = -Y[p - w - 1] - 2 * Y[p - 1] - Y[p + w - 1] + Y[p - w + 1] + 2 * Y[p + 1] + Y[p + w + 1];
      const gy = -Y[p - w - 1] - 2 * Y[p - w] - Y[p - w + 1] + Y[p + w - 1] + 2 * Y[p + w] + Y[p + w + 1];
      const g = Math.hypot(gx, gy);
      if (g > 0.22) { pi.data[o + 1] = 255; pi.data[o + 2] = 255; pi.data[o + 3] = Math.min(255, 170 + g * 200); }
      const band = FalseColor.find(([cut]) => Y[p] < cut)[1];
      fi.data[o] = band[0]; fi.data[o + 1] = band[1]; fi.data[o + 2] = band[2]; fi.data[o + 3] = 255;
    }
  }
  zx.putImageData(zi, 0, 0);
  px.putImageData(pi, 0, 0);
  fx.putImageData(fi, 0, 0);

  // Scope data from a reduced copy, as the analysis stream does.
  const sw = 320, sh = Math.round((h / w) * 320);
  const small = new OffscreenCanvas(sw, sh).getContext("2d");
  small.drawImage(src, 0, 0, sw, sh);
  const s = small.getImageData(0, 0, sw, sh).data;
  const wave = new Uint32Array(sw * 128);
  const vec = new Uint32Array(128 * 128);
  const hist = [new Uint32Array(64), new Uint32Array(64), new Uint32Array(64)];
  for (let p = 0, i = 0; p < sw * sh; p++, i += 4) {
    const x = p % sw;
    const r = s[i] / 255, g = s[i + 1] / 255, b = s[i + 2] / 255;
    const yv = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    wave[Math.min(127, Math.floor(yv * 127.99)) * sw + x]++;
    const cb = (b - yv) / 1.8556, cr = (r - yv) / 1.5748;
    const vx = Math.round(64 + cb * 5 * 63), vy = Math.round(64 - cr * 5 * 63); // 2.5x zoom, as on a narrow-gamut scene
    vec[Math.max(0, Math.min(127, vy)) * 128 + Math.max(0, Math.min(127, vx))]++;
    hist[0][Math.min(63, s[i] >> 2)]++;
    hist[1][Math.min(63, s[i + 1] >> 2)]++;
    hist[2][Math.min(63, s[i + 2] >> 2)]++;
  }
  const density = (counts, cw, ch) => {
    const c = new OffscreenCanvas(cw, ch);
    const x = c.getContext("2d");
    const img = x.createImageData(cw, ch);
    let max = 1;
    for (const v of counts) max = Math.max(max, v);
    for (let row = 0; row < ch; row++) for (let col = 0; col < cw; col++) {
      const v = counts[(counts === wave ? (ch - 1 - row) : row) * cw + col];
      if (!v) continue;
      const o = (row * cw + col) * 4;
      img.data[o] = img.data[o + 1] = img.data[o + 2] = 255;
      img.data[o + 3] = Math.min(255, 20 + 235 * Math.pow(Math.log(1 + v) / Math.log(1 + max), 1.4));
    }
    x.putImageData(img, 0, 0);
    return c;
  };
  // Smooth the histograms a little: a flat graded sky otherwise gives single-bin spikes.
  for (const ch of hist) for (let pass = 0; pass < 3; pass++) {
    const c = Float32Array.from(ch);
    for (let i = 0; i < 64; i++) ch[i] = (c[Math.max(0, i - 1)] + 2 * c[i] + c[Math.min(63, i + 1)]) / 4;
  }
  return {
    zebra: zc, peaking: pc, falseColor: fc,
    waveform: density(wave, sw, 128), vectorscope: density(vec, 128, 128), histogram: hist,
  };
}
