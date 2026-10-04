/* SPDX-License-Identifier: Apache-2.0 */
/* Drawing kit: tokens, type, devices, chips, and the app surfaces rebuilt from the Cine theme. */
import { Spring, clamp, easeIn, spring } from "./motion.js";

export const C = {
  // Marketing canvas.
  paper: "#F5F3EE", ink: "#101417", ink2: "#5B6469", line: "#E2DFD7", card: "#FFFFFF",
  amberC: "#FFE8B3", cyanC: "#D4F4F8", sageC: "#DDEFD9", stoneC: "#E8E6E0",
  amberDeep: "#7A5900", cyanDeep: "#006874", okDeep: "#386A20",
  // Cine theme (ui/theme/OpenCineCamTheme.kt).
  amber: "#FFB300", cyan: "#45D6E8", tertiary: "#7FD9E4", rec: "#E23A3A", ok: "#4BD28A", pending: "#FFCF66",
  graphite: "#0B0D0E", surface: "#101417", lowest: "#07090A", low: "#0E1214", container: "#12171A",
  high: "#1B2226", highest: "#232B30", bright: "#2A3237", onVariant: "#AAB4BA", outline: "#41494C",
  outlineVariant: "#263036", primaryContainer: "#3A2E12", onPrimaryContainer: "#FFCF66",
};

export const Sans = "GSF";
export const Mono = "GSC";
export const font = (weight, size, family = Sans) => `${weight} ${size}px "${family}"`;

export function rrect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, Math.min(r, w / 2, h / 2));
}

export function fillRR(ctx, x, y, w, h, r, color) {
  rrect(ctx, x, y, w, h, r);
  ctx.fillStyle = color;
  ctx.fill();
}

/** A soft, product-shot shadow: one wide ambient layer and one tight contact layer. */
export function shadowed(ctx, draw, strength = 1) {
  ctx.save();
  ctx.shadowColor = `rgba(16,20,23,${0.13 * strength})`;
  ctx.shadowBlur = 70;
  ctx.shadowOffsetY = 34;
  draw();
  ctx.shadowColor = `rgba(16,20,23,${0.08 * strength})`;
  ctx.shadowBlur = 6;
  ctx.shadowOffsetY = 2;
  draw();
  ctx.restore();
}

function wrap(ctx, text, maxW) {
  const words = text.split(" ");
  const lines = [];
  let line = [];
  let width = 0;
  const space = ctx.measureText(" ").width;
  for (const w of words) {
    const ww = ctx.measureText(w).width;
    if (line.length && width + space + ww > maxW) {
      lines.push({ words: line, width });
      line = [];
      width = 0;
    }
    line.push({ text: w, w: ww, x: line.length ? width + space : 0 });
    width = line.length > 1 ? width + space + ww : ww;
  }
  if (line.length) lines.push({ words: line, width });
  return lines;
}

/**
 * Eyebrow, headline and subtitle that rise in word by word with a slow spring, then fade out as
 * [out] goes 0→1. Returns the block's bounds (for the backdrop's calm areas).
 */
export function textBlock(ctx, spec, lt, out = 0) {
  const { x, align = "left", maxW, eyebrow, title, sub, size = 92, subSize = 32, delay = 0.15 } = spec;
  const titleFont = font(640, size);
  const subFont = font(430, subSize);
  ctx.font = titleFont;
  ctx.letterSpacing = `${-size * 0.03}px`;
  const tLines = wrap(ctx, title, maxW);
  ctx.letterSpacing = "0px";
  ctx.font = subFont;
  const sLines = sub ? wrap(ctx, sub, maxW * (align === "center" ? 0.92 : 0.95)) : [];
  const eyeH = eyebrow ? 30 + 26 : 0;
  const tLH = size * 1.06, sLH = subSize * 1.42;
  const height = eyeH + tLines.length * tLH + (sub ? 26 + sLines.length * sLH : 0);
  let y = spec.valign === "center" ? spec.y - height / 2 : spec.y;
  const top = y;
  const fade = 1 - easeIn(out);
  const lift = -18 * out;
  const ax = (w) => (align === "center" ? x - w / 2 : x);

  const word = (text, wx, wy, t0, f, color) => {
    const p = spring(lt - t0, Spring.slow);
    const a = clamp(p * 1.6) * fade;
    if (a <= 0) return;
    ctx.globalAlpha = a;
    ctx.font = f;
    ctx.fillStyle = color;
    ctx.fillText(text, wx, wy + (1 - p) * 34 + lift);
  };
  ctx.textBaseline = "alphabetic";
  ctx.textAlign = "left";
  let k = 0;
  if (eyebrow) {
    ctx.font = font(560, 24);
    ctx.letterSpacing = "2.4px";
    const w = ctx.measureText(eyebrow.toUpperCase()).width;
    word(eyebrow.toUpperCase(), ax(w), y + 24, delay, font(560, 24), C.amberDeep);
    ctx.letterSpacing = "0px";
    y += eyeH;
  }
  ctx.letterSpacing = `${-size * 0.03}px`;
  for (const line of tLines) {
    y += tLH;
    for (const w of line.words) word(w.text, ax(line.width) + w.x, y - size * 0.18, delay + 0.08 + k++ * 0.05, titleFont, C.ink);
  }
  ctx.letterSpacing = "0px";
  if (sub) {
    y += 26;
    const t0 = delay + 0.32 + k * 0.05;
    for (const line of sLines) {
      y += sLH;
      const lw = line.width;
      for (const w of line.words) word(w.text, ax(lw) + w.x, y - subSize * 0.3, t0, subFont, C.ink2);
    }
  }
  ctx.globalAlpha = 1;
  const bw = Math.max(...tLines.map((l) => l.width), ...sLines.map((l) => l.width), 1);
  return { x: ax(bw), y: top, w: bw, h: height };
}

/** Measures a chip without drawing it. */
export function chipWidth(ctx, text, size = 24, pad = 22, icon = 0) {
  ctx.font = font(560, size);
  return ctx.measureText(text).width + pad * 2 + icon;
}

/** A Material chip / pill label. */
export function chip(ctx, x, y, text, { size = 24, h = size * 2, pad = 22, fill = C.card, color = C.ink, stroke = null, dot = null, weight = 560 } = {}) {
  ctx.font = font(weight, size);
  const dotW = dot ? size * 0.75 : 0;
  const w = ctx.measureText(text).width + pad * 2 + dotW;
  rrect(ctx, x, y, w, h, h / 2);
  ctx.fillStyle = fill;
  ctx.fill();
  if (stroke) {
    ctx.strokeStyle = stroke;
    ctx.lineWidth = 2;
    ctx.stroke();
  }
  if (dot) {
    ctx.fillStyle = dot;
    ctx.beginPath();
    ctx.arc(x + pad + size * 0.22, y + h / 2, size * 0.22, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.fillStyle = color;
  ctx.textBaseline = "middle";
  ctx.fillText(text, x + pad + dotW, y + h / 2 + size * 0.04);
  ctx.textBaseline = "alphabetic";
  return w;
}

/** A graphite phone. Returns the screen rectangle. */
export function phone(ctx, cx, cy, w, h, { landscape = false } = {}) {
  const r = Math.min(w, h) * 0.13;
  const x = cx - w / 2, y = cy - h / 2;
  shadowed(ctx, () => fillRR(ctx, x, y, w, h, r, "#1a1f22"));
  fillRR(ctx, x + 2, y + 2, w - 4, h - 4, r - 2, "#050607");
  const b = Math.min(w, h) * 0.028;
  const scr = { x: x + b, y: y + b, w: w - b * 2, h: h - b * 2, r: r - b };
  fillRR(ctx, scr.x, scr.y, scr.w, scr.h, scr.r, C.graphite);
  // Punch-hole camera.
  ctx.fillStyle = "#000";
  ctx.beginPath();
  if (landscape) ctx.arc(scr.x + b * 1.6, cy, b * 0.55, 0, Math.PI * 2);
  else ctx.arc(cx, scr.y + b * 1.6, b * 0.55, 0, Math.PI * 2);
  ctx.fill();
  return scr;
}

export function clipRR(ctx, r) {
  rrect(ctx, r.x, r.y, r.w, r.h, r.r ?? 0);
  ctx.clip();
}

/** Draws [img] to cover [r] (centre crop) with an optional extra zoom. */
export function cover(ctx, img, r, zoom = 1, fx = 0.5, fy = 0.5) {
  const s = Math.max(r.w / img.width, r.h / img.height) * zoom;
  const w = img.width * s, h = img.height * s;
  ctx.drawImage(img, r.x + (r.w - w) * fx, r.y + (r.h - h) * fy, w, h);
}

/** The exposure strip from the capture screen: SHUTTER, ISO, WB, EV, FOCUS. */
export function exposureStrip(ctx, x, y, w, h, cells, { k = 1, highlight = -1, highlightP = 0 } = {}) {
  fillRR(ctx, x, y, w, h, 14 * k, C.container);
  rrect(ctx, x, y, w, h, 14 * k);
  ctx.strokeStyle = C.outlineVariant;
  ctx.lineWidth = 1.5 * k;
  ctx.stroke();
  const cw = w / cells.length;
  if (highlight >= 0) {
    const hx = x + cw * highlight + 5 * k;
    ctx.globalAlpha = highlightP;
    fillRR(ctx, hx, y + 5 * k, cw - 10 * k, h - 10 * k, 10 * k, C.primaryContainer);
    ctx.globalAlpha = 1;
  }
  cells.forEach((c, i) => {
    const cx = x + cw * (i + 0.5);
    if (i > 0) {
      ctx.fillStyle = C.outlineVariant;
      ctx.fillRect(x + cw * i, y + h * 0.22, 1.5 * k, h * 0.56);
    }
    ctx.textAlign = "center";
    ctx.font = font(560, 11.5 * k);
    ctx.letterSpacing = `${0.6 * k}px`;
    ctx.fillStyle = i === highlight ? C.onPrimaryContainer : C.onVariant;
    ctx.fillText(c.label, cx, y + h * 0.4);
    ctx.letterSpacing = "0px";
    ctx.font = font(650, 19 * k);
    ctx.fillStyle = i === highlight ? C.amber : "#FFFFFF";
    ctx.fillText(c.value, cx, y + h * 0.78);
  });
  ctx.textAlign = "left";
}

/** Small round icon button as on the capture rail. */
export function iconButton(ctx, cx, cy, r, glyph, { fill = C.high, color = "#fff" } = {}) {
  ctx.fillStyle = fill;
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = r * 0.12;
  glyph?.(ctx, cx, cy, r * 0.5);
}

export const Glyph = {
  scopes: (ctx, x, y, s) => {
    ctx.beginPath();
    ctx.roundRect(x - s, y - s * 0.75, s * 2, s * 1.5, s * 0.2);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(x - s * 0.7, y + s * 0.4);
    ctx.lineTo(x - s * 0.2, y - s * 0.2);
    ctx.lineTo(x + s * 0.2, y + s * 0.15);
    ctx.lineTo(x + s * 0.7, y - s * 0.4);
    ctx.stroke();
  },
  gear: (ctx, x, y, s) => {
    ctx.beginPath();
    ctx.arc(x, y, s * 0.75, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(x, y, s * 0.28, 0, Math.PI * 2);
    ctx.stroke();
  },
  bolt: (ctx, x, y, s) => {
    ctx.beginPath();
    ctx.moveTo(x + s * 0.2, y - s);
    ctx.lineTo(x - s * 0.5, y + s * 0.1);
    ctx.lineTo(x, y + s * 0.1);
    ctx.lineTo(x - s * 0.2, y + s);
    ctx.lineTo(x + s * 0.5, y - s * 0.1);
    ctx.lineTo(x, y - s * 0.1);
    ctx.closePath();
    ctx.fill();
  },
  split: (ctx, x, y, s) => {
    ctx.beginPath();
    ctx.roundRect(x - s, y - s * 0.75, s * 2, s * 1.5, s * 0.15);
    ctx.moveTo(x, y - s * 0.75);
    ctx.lineTo(x, y + s * 0.75);
    ctx.stroke();
  },
};

/** The full capture screen inside a portrait phone screen [scr], in dp scaled by k = scr.w / 411. */
export function captureScreen(ctx, scr, o) {
  const k = scr.w / 411;
  ctx.save();
  clipRR(ctx, scr);
  ctx.fillStyle = C.graphite;
  ctx.fillRect(scr.x, scr.y, scr.w, scr.h);
  const X = (v) => scr.x + v * k, Y = (v) => scr.y + v * k;

  // Top bar.
  ctx.font = font(560, 13 * k);
  ctx.fillStyle = C.onVariant;
  ctx.textBaseline = "middle";
  ctx.fillText("BAT", X(18), Y(48));
  ctx.fillStyle = C.ok;
  ctx.fillText("100%", X(18) + ctx.measureText("BAT ").width, Y(48));
  if (o.recording) {
    fillRR(ctx, X(150), Y(36), 111 * k, 24 * k, 12 * k, "rgba(226,58,58,0.18)");
    ctx.fillStyle = C.rec;
    ctx.beginPath();
    ctx.arc(X(164), Y(48), 4.5 * k, 0, Math.PI * 2);
    ctx.fill();
    ctx.font = font(600, 13 * k, Mono);
    ctx.fillStyle = "#fff";
    ctx.fillText(o.timecode ?? "00:00:12:08", X(174), Y(48.5));
  }
  ctx.textBaseline = "alphabetic";
  iconButton(ctx, X(316), Y(48), 15 * k, Glyph.bolt, { fill: "transparent" });
  iconButton(ctx, X(372), Y(48), 15 * k, Glyph.gear, { fill: "transparent" });

  // Viewfinder (3:4 photo / full width).
  const vf = { x: X(0), y: Y(72), w: scr.w, h: 411 * k * (4 / 3), r: 0 };
  ctx.save();
  ctx.beginPath();
  ctx.rect(vf.x, vf.y, vf.w, vf.h);
  ctx.clip();
  if (o.picture) cover(ctx, o.picture, vf, o.zoom ?? 1.04);
  o.overlay?.(vf);
  ctx.restore();
  // Zoom chip.
  fillRR(ctx, X(14), vf.y + 14 * k, 46 * k, 26 * k, 8 * k, "rgba(11,13,14,0.72)");
  ctx.font = font(650, 13 * k);
  ctx.fillStyle = C.amber;
  ctx.textAlign = "center";
  ctx.fillText("1.0×", X(37), vf.y + 31.5 * k);
  ctx.textAlign = "left";

  const by = vf.y + vf.h;
  if (!o.hideStrip) exposureStrip(ctx, X(10), by + 14 * k, 391 * k, 58 * k, o.cells, { k, highlight: o.highlight ?? -1, highlightP: o.highlightP ?? 0 });

  // Mode row.
  const modes = o.modes ?? ["Photo", "Video", "Log", "Slow motion"];
  const sel = o.selectedMode ?? 1;
  ctx.font = font(600, 17 * k);
  let mx = X(205);
  const widths = modes.map((m) => ctx.measureText(m).width);
  mx -= widths.slice(0, sel).reduce((a, b) => a + b + 28 * k, 0) + widths[sel] / 2;
  modes.forEach((m, i) => {
    ctx.fillStyle = i === sel ? C.amber : "rgba(255,255,255,0.78)";
    ctx.fillText(m, mx, by + 108 * k);
    if (i === sel) {
      ctx.beginPath();
      ctx.arc(mx + widths[i] / 2, by + 120 * k, 2.6 * k, 0, Math.PI * 2);
      ctx.fill();
    }
    mx += widths[i] + 28 * k;
  });

  // Shutter row.
  const sy = by + 172 * k;
  const thumb = { x: X(32), y: sy - 30 * k, w: 60 * k, h: 60 * k, r: 14 * k };
  ctx.save();
  clipRR(ctx, thumb);
  if (o.picture) cover(ctx, o.picture, thumb, 1.4, 0.3, 0.6);
  ctx.restore();
  ctx.lineWidth = 4 * k;
  ctx.strokeStyle = "#fff";
  ctx.beginPath();
  ctx.arc(X(205), sy, 38 * k, 0, Math.PI * 2);
  ctx.stroke();
  const video = o.video ?? true;
  if (o.recording) fillRR(ctx, X(205) - 15 * k, sy - 15 * k, 30 * k, 30 * k, 7 * k, C.rec);
  else {
    ctx.fillStyle = video ? C.rec : C.amber;
    ctx.beginPath();
    ctx.arc(X(205), sy, 31 * k, 0, Math.PI * 2);
    ctx.fill();
  }
  iconButton(ctx, X(352), sy, 30 * k, Glyph.scopes, { fill: C.high });
  ctx.restore();
  return vf;
}

/** Card frame for a scope, in the Cine surface colours. */
export function scopeCard(ctx, r, label, draw) {
  fillRR(ctx, r.x, r.y, r.w, r.h, 22, C.container);
  ctx.font = font(560, 17);
  ctx.letterSpacing = "1.2px";
  ctx.fillStyle = C.onVariant;
  ctx.fillText(label.toUpperCase(), r.x + 20, r.y + 34);
  ctx.letterSpacing = "0px";
  draw({ x: r.x + 16, y: r.y + 50, w: r.w - 32, h: r.h - 66 });
}

export function drawWaveform(ctx, a, wave, reveal = 1) {
  fillRR(ctx, a.x, a.y, a.w, a.h, 8, C.lowest);
  ctx.strokeStyle = "rgba(170,180,186,0.3)";
  for (let l = 0; l <= 4; l++) {
    const y = a.y + a.h - (a.h * l) / 4;
    ctx.lineWidth = l % 2 === 0 ? 1.5 : 0.75;
    ctx.beginPath();
    ctx.moveTo(a.x, y);
    ctx.lineTo(a.x + a.w, y);
    ctx.stroke();
  }
  ctx.save();
  ctx.beginPath();
  ctx.rect(a.x, a.y, a.w * reveal, a.h);
  ctx.clip();
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(wave, a.x, a.y, a.w, a.h);
  ctx.restore();
}

export function drawVectorscope(ctx, a, vec, reveal = 1) {
  const r = Math.min(a.w, a.h) / 2;
  const cx = a.x + a.w / 2, cy = a.y + a.h / 2;
  ctx.fillStyle = C.lowest;
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = "rgba(170,180,186,0.35)";
  ctx.lineWidth = 1.5;
  ctx.stroke();
  ctx.lineWidth = 0.75;
  ctx.beginPath();
  ctx.moveTo(cx - r, cy); ctx.lineTo(cx + r, cy);
  ctx.moveTo(cx, cy - r); ctx.lineTo(cx, cy + r);
  ctx.stroke();
  const skin = (123 * Math.PI) / 180;
  ctx.beginPath();
  ctx.moveTo(cx, cy);
  ctx.lineTo(cx + Math.cos(skin) * r, cy - Math.sin(skin) * r);
  ctx.stroke();
  // 75 % colour-bar targets (R, Mg, B, Cy, G, Yl).
  ctx.strokeStyle = "rgba(170,180,186,0.7)";
  for (const deg of [103, 61, 347, 283, 241, 167]) {
    const t = (deg * Math.PI) / 180;
    const px = cx + Math.cos(t) * r * 0.75, py = cy - Math.sin(t) * r * 0.75;
    ctx.strokeRect(px - r * 0.045, py - r * 0.045, r * 0.09, r * 0.09);
  }
  ctx.save();
  ctx.globalAlpha = reveal;
  ctx.translate(cx, cy);
  ctx.scale(0.6 + 0.4 * reveal, 0.6 + 0.4 * reveal);
  ctx.drawImage(vec, -r, -r, r * 2, r * 2);
  ctx.restore();
}

export function drawHistogram(ctx, a, hist, reveal = 1) {
  fillRR(ctx, a.x, a.y, a.w, a.h, 8, C.lowest);
  let max = 1;
  for (const ch of hist) for (let i = 2; i < 62; i++) max = Math.max(max, ch[i]);
  const colors = ["rgba(255,90,90,0.55)", "rgba(90,230,120,0.55)", "rgba(90,150,255,0.55)"];
  ctx.save();
  ctx.globalCompositeOperation = "lighter";
  hist.forEach((ch, c) => {
    ctx.beginPath();
    ctx.moveTo(a.x, a.y + a.h);
    for (let i = 0; i < 64; i++) {
      const v = Math.min(1, ch[i] / max) * reveal;
      ctx.lineTo(a.x + (a.w * (i + 0.5)) / 64, a.y + a.h - v * a.h * 0.92);
    }
    ctx.lineTo(a.x + a.w, a.y + a.h);
    ctx.closePath();
    ctx.fillStyle = colors[c];
    ctx.fill();
  });
  ctx.restore();
}
