/* SPDX-License-Identifier: Apache-2.0 */
/* Springs and easings. Every value is a pure function of time, so any frame renders on its own. */

export const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
export const lerp = (a, b, t) => a + (b - a) * t;
export const mix = (a, b, t) => a.map((v, i) => lerp(v, b[i], t));
/** 0 before [a], 1 after [b], linear in between. */
export const phase = (t, a, b) => clamp((t - a) / (b - a));

/** M3 Expressive spring tokens (dampingRatio, stiffness), plus slower ones for large, calm moves. */
export const Spring = {
  fast: { damping: 0.6, stiffness: 800 },
  spatial: { damping: 0.8, stiffness: 380 },
  slow: { damping: 0.82, stiffness: 140 },
  gentle: { damping: 0.9, stiffness: 70 },
  effects: { damping: 1, stiffness: 300 },
  calm: { damping: 1, stiffness: 90 },
};

/** Step response of a unit-mass spring released at t = 0 from 0 towards 1. */
export function spring(t, { damping, stiffness } = Spring.spatial) {
  if (t <= 0) return 0;
  const w = Math.sqrt(stiffness);
  if (damping < 1) {
    const wd = w * Math.sqrt(1 - damping * damping);
    return 1 - Math.exp(-damping * w * t) * (Math.cos(wd * t) + ((damping * w) / wd) * Math.sin(wd * t));
  }
  return 1 - Math.exp(-w * t) * (1 + w * t);
}

/** An impulse from rest that swells, overshoots a little and settles (the backdrop's beat). */
export function impulse(t, { damping, stiffness }, velocity) {
  if (t <= 0) return 0;
  const w = Math.sqrt(stiffness);
  const wd = w * Math.sqrt(1 - damping * damping);
  return (velocity / wd) * Math.exp(-damping * w * t) * Math.sin(wd * t);
}

export const easeOut = (t) => 1 - Math.pow(1 - clamp(t), 3);
export const easeIn = (t) => Math.pow(clamp(t), 3);
export const easeInOut = (t) => {
  t = clamp(t);
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
};
/** Material emphasized-ish decelerate. */
export const emphasized = (t) => 1 - Math.pow(1 - clamp(t), 4);

/** Deterministic PRNG. */
export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
