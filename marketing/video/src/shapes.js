/* SPDX-License-Identifier: Apache-2.0 */
/*
 * The Material 3 Expressive shapes the app uses (MaterialShapes), rebuilt as polar outlines: one
 * radius per angle. Every shape shares the same sampling, so any two can morph by interpolating radii.
 */

const N = 288;
const TAU = Math.PI * 2;

function normalise(r) {
  let max = 0;
  for (const v of r) max = Math.max(max, v);
  for (let i = 0; i < N; i++) r[i] /= max;
  return r;
}

/** Circular moving average: rounds corners like RoundedPolygon's corner rounding. */
function soften(r, width) {
  if (width < 1) return r;
  const out = new Float32Array(N);
  for (let pass = 0; pass < 3; pass++) {
    for (let i = 0; i < N; i++) {
      let s = 0;
      for (let k = -width; k <= width; k++) s += r[(i + k + N) % N];
      out[i] = s / (2 * width + 1);
    }
    r.set(out);
  }
  return r;
}

function polar(fn, rounding = 0) {
  const r = new Float32Array(N);
  for (let i = 0; i < N; i++) r[i] = fn((i / N) * TAU);
  return normalise(soften(r, Math.round(rounding * N * 0.05)));
}

/** Star-shaped region given as an inside test on [-1, 1]²: the boundary along each ray by bisection. */
function fromInside(inside, rounding = 0) {
  return polar((a) => {
    let lo = 0, hi = 1.5;
    for (let k = 0; k < 24; k++) {
      const m = (lo + hi) / 2;
      if (inside(Math.cos(a) * m, Math.sin(a) * m)) lo = m; else hi = m;
    }
    return lo;
  }, rounding);
}

const polygon = (n, rotation, rounding) =>
  polar((a) => {
    const seg = TAU / n;
    const x = (((a - rotation) % seg) + seg) % seg;
    return Math.cos(Math.PI / n) / Math.cos(x - seg / 2);
  }, rounding);

const wavy = (n, inner, sharp = 1, rounding = 0, rotation = -Math.PI / 2) =>
  polar((a) => {
    const c = 0.5 + 0.5 * Math.cos(n * (a - rotation));
    return inner + (1 - inner) * Math.pow(c, sharp);
  }, rounding);

/** Pointy star (Sunny, Burst): a polygon of 2n vertices alternating outer and inner radius. */
const star = (n, inner, rounding, rotation = -Math.PI / 2) =>
  polar((a) => {
    const seg = TAU / n;
    const x = ((((a - rotation) % seg) + seg) % seg) / seg; // 0..1 inside a spoke
    const d = Math.abs(x - 0.5) * 2; // 1 at tips, 0 at valleys
    // Straight edges between tip (radius 1) and valley (radius inner).
    const ang = (1 - d) * (seg / 2);
    const tip = [1, 0];
    const valley = [inner * Math.cos(seg / 2), inner * Math.sin(seg / 2)];
    // Ray at angle ang from the centre, intersected with the segment tip-valley.
    const dx = valley[0] - tip[0], dy = valley[1] - tip[1];
    const ca = Math.cos(ang), sa = Math.sin(ang);
    const den = ca * dy - sa * dx;
    return Math.abs(den) < 1e-6 ? 1 : (tip[0] * dy - tip[1] * dx) / den;
  }, rounding);

export const Shapes = {
  circle: polar(() => 1),
  cookie4: wavy(4, 0.8, 1, 0.4, Math.PI / 4),
  cookie6: wavy(6, 0.82, 1, 0.3),
  cookie9: wavy(9, 0.86, 1, 0.2),
  scallop: wavy(12, 0.9, 0.7, 0.1),
  pentagon: polygon(5, -Math.PI / 2, 0.55),
  triangle: polygon(3, -Math.PI / 2, 0.75),
  square: polygon(4, Math.PI / 4, 0.7),
  sunny: star(8, 0.78, 0.35),
  burst: star(12, 0.7, 0.2),
  clover: wavy(4, 0.45, 0.5, 0.6, Math.PI / 4),
  oval: fromInside((x, y) => (x / 1) ** 2 + (y / 0.64) ** 2 <= 1),
  pill: fromInside((x, y) => {
    const a = 1, b = 0.5; // stadium: half-length 1, radius 0.5
    const cx = Math.max(Math.abs(x) - (a - b), 0);
    return cx * cx + y * y <= b * b;
  }),
  pixelCircle: fromInside((x, y) => {
    const g = 7; // 7x7 grid of pixels, rounded to a circle
    const px = Math.floor((x + 1) / (2 / g)), py = Math.floor((y + 1) / (2 / g));
    if (px < 0 || py < 0 || px >= g || py >= g) return false;
    const cx = -1 + (px + 0.5) * (2 / g), cy = -1 + (py + 0.5) * (2 / g);
    return cx * cx + cy * cy <= 0.95;
  }),
};

/** The OnboardingBackdrop catalogue, in its order. */
export const BackdropCatalogue = [
  Shapes.circle, Shapes.cookie6, Shapes.pentagon, Shapes.pill, Shapes.triangle,
  Shapes.square, Shapes.sunny, Shapes.burst, Shapes.oval, Shapes.pixelCircle,
];

export function morph(a, b, t) {
  if (t <= 0) return a;
  if (t >= 1) return b;
  const r = new Float32Array(N);
  for (let i = 0; i < N; i++) r[i] = a[i] + (b[i] - a[i]) * t;
  return r;
}

/** Traces [radii] as a closed path of radius [size] around (cx, cy), turned by [rotation] radians. */
export function shapePath(ctx, radii, cx, cy, size, rotation = 0) {
  ctx.beginPath();
  for (let i = 0; i < N; i++) {
    const a = (i / N) * TAU + rotation;
    const r = radii[i] * size;
    const x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  }
  ctx.closePath();
}

export function fillShape(ctx, radii, cx, cy, size, rotation, color) {
  shapePath(ctx, radii, cx, cy, size, rotation);
  ctx.fillStyle = color;
  ctx.fill();
}
