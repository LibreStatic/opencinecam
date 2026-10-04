/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Port of app/src/main/java/com/librestatic/opencinecam/OnboardingBackdrop.kt: photo and video
 * shapes of many sizes drifting behind everything, with parallax by depth, a slow wobble and spin,
 * and a small radial push on every scene cut (the wizard's "beat"). Fainter than in the app so it
 * never competes with the content.
 */
import { BackdropCatalogue, fillShape } from "./shapes.js";
import { clamp, easeOut, impulse, rng } from "./motion.js";

const WrapSpan = 1.5; // backdrop widths the shapes wrap around in, wider than the frame so none pop in
const Drift = 0.03; // backdrop widths per second at depth 1
const BeatSpring = { damping: 0.45, stiffness: 200 }; // Spring.StiffnessLow, as in the app
const Palette = ["#FFB300", "#45D6E8", "#9CA6AA"]; // primary, secondary, outline roles on paper

export function createBackdrop(stage, seed = 20260930) {
  const random = rng(seed);
  const count = stage.portrait ? 11 : 16;
  const dp = stage.portrait ? 2.2 : 2.0;
  const shapes = [];
  for (let i = 0; i < count; i++) {
    const depth = 0.4 + random() * 0.6;
    const size = 0.55 + random() * 0.9; // "diversos tamaños": an extra size spread on top of depth
    shapes.push({
      radii: BackdropCatalogue[(i + Math.floor(random() * BackdropCatalogue.length)) % BackdropCatalogue.length],
      side: (40 + 120 * depth) * size * dp,
      depth,
      x: (i + random() * 0.8) / count * WrapSpan,
      y: 0.06 + random() * 0.88,
      spin: (random() < 0.5 ? 1 : -1) * (6 + random() * 14),
      phase: random() * Math.PI * 2,
      wobble: (8 + 16 * random()) * dp,
      role: i % 3,
    });
  }
  return { shapes, stage };
}

/**
 * [beats] are the scene start times; [calm] is a list of {x, y, w, h} areas (text, devices) under
 * which the shapes dim to half, so they never sit loudly behind content.
 */
export function drawBackdrop(ctx, backdrop, t, beats, calm = []) {
  const { W, H } = backdrop.stage;
  const emerge = easeOut(clamp((t - 0.15) / 2.2));
  let push = 0;
  for (const b of beats) if (t >= b && t - b < 3) push += impulse(t - b, BeatSpring, 4);
  const center = { x: W / 2, y: H / 2 };
  const diagonal = Math.hypot(W, H);
  for (const s of backdrop.shapes) {
    const along = (((s.x - t * Drift * s.depth) % WrapSpan) + WrapSpan) % WrapSpan;
    let cx = (along - (WrapSpan - 1) / 2) * W + Math.sin(t * 0.35 + s.phase) * s.wobble;
    let cy = s.y * H + Math.cos(t * 0.27 + s.phase * 1.3) * s.wobble;
    const ax = cx - center.x, ay = cy - center.y;
    const dist = Math.max(1, Math.hypot(ax, ay));
    cx += (ax / dist) * push * 40 * 2 * s.depth;
    cy += (ay / dist) * push * 40 * 2 * s.depth;
    if (emerge < 1) {
      cx = center.x + (cx - center.x) * emerge;
      cy = center.y + (cy - center.y) * emerge;
    }
    const scale = (1 + 0.15 * push) * emerge;
    if (scale <= 0) continue;
    let alpha = (0.05 + 0.06 * s.depth) * emerge;
    for (const r of calm) {
      const dx = Math.max(r.x - cx, 0, cx - (r.x + r.w));
      const dy = Math.max(r.y - cy, 0, cy - (r.y + r.h));
      const inside = 1 - clamp(Math.hypot(dx, dy) / (diagonal * 0.08));
      alpha *= 1 - 0.5 * inside;
    }
    const angle = (s.phase * 57.3 + t * s.spin) * (Math.PI / 180);
    ctx.globalAlpha = alpha;
    fillShape(ctx, s.radii, cx, cy, (s.side / 2) * scale, angle, Palette[s.role]);
  }
  ctx.globalAlpha = 1;
}
