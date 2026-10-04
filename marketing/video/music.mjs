#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
/*
 * The soundtrack, synthesised from scratch (no samples): restrained, bright electronic in A minor at
 * 120 BPM, so every 2 s bar lands on a scene cut. Punchy kick and sidechained bass, plucked arp,
 * soft pad and hats. Writes out/music.wav (48 kHz, stereo) normalised to -14 LUFS / -1 dBTP.
 *
 *   node music.mjs
 */
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const SR = 48000;
const DUR = 67.5; // 66 s of picture plus the tail
const N = Math.round(SR * DUR);
const BEAT = 0.5, BAR = 2;
const L = new Float32Array(N), R = new Float32Array(N);
const revL = new Float32Array(N), revR = new Float32Array(N); // reverb send
const dlyL = new Float32Array(N), dlyR = new Float32Array(N); // delay send
const kickEnv = new Float32Array(N); // drives the sidechain

let seed = 7;
const noise = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 2147483648 - 1; };
const midi = (m) => 440 * Math.pow(2, (m - 69) / 12);
const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
const smooth = (x) => { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); };

// ── Arrangement ──────────────────────────────────────────────────────────────────────────────────
// Am9 · Fmaj7 · C/E · Em7(add11), one chord per bar: minor, unhurried, never cheerful.
const Chords = [
  { root: 45, tones: [57, 60, 64, 67, 71] },
  { root: 41, tones: [57, 60, 64, 65, 69] },
  { root: 40, tones: [55, 60, 64, 67, 72] },
  { root: 40, tones: [55, 59, 62, 64, 69] },
];
const chordAt = (t) => Chords[Math.floor(t / BAR) % 4];
const has = {
  kick: (t) => (t >= 4 && t < 56) || (t >= 60 && t < 64.01),
  bass: (t) => (t >= 4 && t < 56) || (t >= 60 && t < 64),
  hats: (t) => (t >= 10 && t < 56) || (t >= 60 && t < 63.5),
  clap: (t) => (t >= 16 && t < 56) || (t >= 60 && t < 63.5),
  arp: (t) => t >= 0.5 && t < 64.5,
};
// Arp brightness by section: opens as the film builds, darkens in the drop and the outro.
const arpCutoff = (t) => {
  if (t < 4) return 900 + 700 * (t / 4);
  if (t < 16) return 2000 + 900 * ((t - 4) / 12);
  if (t < 56) return 3000 + 1600 * smooth((t - 16) / 30);
  if (t < 60) return 1200 + 900 * smooth((t - 56) / 4);
  return 3800 - 2800 * smooth((t - 62) / 3);
};

// ── DSP helpers ──────────────────────────────────────────────────────────────────────────────────
function polyblep(t, dt) {
  if (t < dt) { t /= dt; return t + t - t * t - 1; }
  if (t > 1 - dt) { t = (t - 1) / dt; return t * t + t + t + 1; }
  return 0;
}
/** Band-limited saw state. */
const saw = () => { let p = (noise() + 1) / 2; return (f) => { const dt = f / SR; p += dt; if (p >= 1) p -= 1; return 2 * p - 1 - polyblep(p, dt); }; };
/** TPT state-variable filter; returns lowpass and bandpass. */
function svf() {
  let ic1 = 0, ic2 = 0;
  return (x, fc, q = 0.7) => {
    const g = Math.tan(Math.PI * Math.min(fc, SR * 0.45) / SR), k = 1 / q;
    const a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2;
    const v3 = x - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
    ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2;
    return { lp: v2, bp: v1, hp: x - k * v1 - v2 };
  };
}
function add(buf, i, v) { if (i >= 0 && i < N) buf[i] += v; }

// ── Instruments ──────────────────────────────────────────────────────────────────────────────────
function kick(t0, gain = 1) {
  const s0 = Math.round(t0 * SR), len = Math.round(0.55 * SR);
  let ph = 0;
  const hp = svf();
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const f = 44 + 120 * Math.exp(-t * 32) + 30 * Math.exp(-t * 300);
    ph += (2 * Math.PI * f) / SR;
    const env = Math.min(1, t / 0.002) * (t < 0.06 ? 1 : Math.exp(-(t - 0.06) * 13));
    let v = Math.sin(ph) * env;
    v = Math.tanh(v * 2.2) / Math.tanh(2.2);
    const click = hp(noise(), 3000, 0.7).hp * Math.exp(-t * 900) * 0.35;
    const out = (v + click) * 0.9 * gain;
    add(L, s0 + i, out); add(R, s0 + i, out);
    kickEnv[s0 + i] = Math.max(kickEnv[s0 + i] ?? 0, Math.exp(-t * 9));
  }
}

function bassNote(t0, dur, m, gain) {
  const s0 = Math.round(t0 * SR), len = Math.round((dur + 0.08) * SR);
  const o1 = saw(), o2 = saw();
  const lp = svf();
  let sub = 0;
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const f = midi(m);
    sub += (2 * Math.PI * f * 0.5) / SR; // the sub sits an octave under the line
    const env = Math.min(1, t / 0.004) * (t < dur ? Math.exp(-t * 5) : Math.exp(-dur * 5) * Math.exp(-(t - dur) * 60));
    const cutoff = 220 + 1300 * Math.exp(-t * 22);
    const mid = lp((o1(f) + o2(f * 1.004)) * 0.5, cutoff, 0.9).lp;
    const v = (Math.sin(sub) * 0.85 + mid * 0.55) * env * gain;
    const out = Math.tanh(v * 1.6) * 0.62;
    add(L, s0 + i, out); add(R, s0 + i, out);
  }
}

function pluck(t0, m, gain, pan) {
  const s0 = Math.round(t0 * SR), len = Math.round(0.6 * SR);
  const a = saw(), b = saw();
  const lp = svf();
  const f = midi(m), cut = arpCutoff(t0);
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const env = Math.min(1, t / 0.003) * Math.exp(-t * 7);
    const x = (a(f * 0.998) + b(f * 1.002)) * 0.5;
    const v = lp(x, 250 + cut * Math.exp(-t * 11), 1.1).lp * env * gain;
    add(L, s0 + i, v * (1 - pan)); add(R, s0 + i, v * (1 + pan));
    add(dlyL, s0 + i, v * 0.32); add(dlyR, s0 + i, v * 0.32);
    add(revL, s0 + i, v * 0.25); add(revR, s0 + i, v * 0.25);
  }
}

function pad(t0, dur, tones, gain, cutoff) {
  const s0 = Math.round(t0 * SR), len = Math.round((dur + 1.2) * SR);
  const voices = tones.flatMap((m) => [[saw(), midi(m - 12) * 0.997, -0.5], [saw(), midi(m - 12) * 1.003, 0.5]]);
  const fl = svf(), fr = svf();
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const env = smooth(t / 0.6) * (t < dur ? 1 : Math.exp(-(t - dur) * 3.5));
    let l = 0, r = 0;
    for (const [o, f, p] of voices) { const v = o(f); l += v * (1 - p); r += v * (1 + p); }
    const vl = fl(l / voices.length, cutoff, 0.6).lp * env * gain;
    const vr = fr(r / voices.length, cutoff, 0.6).lp * env * gain;
    add(L, s0 + i, vl); add(R, s0 + i, vr);
    add(revL, s0 + i, vl * 0.6); add(revR, s0 + i, vr * 0.6);
  }
}

function hat(t0, gain, open = false) {
  const s0 = Math.round(t0 * SR), len = Math.round((open ? 0.25 : 0.06) * SR);
  const f = svf();
  const pan = (noise() * 0.3);
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const v = f(noise(), 9000, 0.9).hp * Math.exp(-t * (open ? 18 : 70)) * gain;
    add(L, s0 + i, v * (1 - pan)); add(R, s0 + i, v * (1 + pan));
  }
}

function clap(t0, gain) {
  const s0 = Math.round(t0 * SR), len = Math.round(0.3 * SR);
  const f = svf();
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const bursts = [0, 0.011, 0.022].reduce((s, d) => s + (t >= d ? Math.exp(-(t - d) * 160) : 0), 0) * 0.5 + Math.exp(-t * 18) * 0.6;
    const v = f(noise(), 1500, 1.4).bp * bursts * gain;
    add(L, s0 + i, v); add(R, s0 + i, v);
    add(revL, s0 + i, v * 0.5); add(revR, s0 + i, v * 0.5);
  }
}

/** A glassy FM bell: the bright top line, sparse and quiet. */
function bell(t0, m, gain, pan) {
  const s0 = Math.round(t0 * SR), len = Math.round(1.4 * SR);
  const f = midi(m);
  let pc = 0, pm = 0;
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    pm += (2 * Math.PI * f * 3.5) / SR;
    pc += (2 * Math.PI * f) / SR;
    const v = Math.sin(pc + Math.sin(pm) * 2.2 * Math.exp(-t * 6)) * Math.min(1, t / 0.002) * Math.exp(-t * 3.2) * gain;
    add(L, s0 + i, v * (1 - pan)); add(R, s0 + i, v * (1 + pan));
    add(dlyL, s0 + i, v * 0.5); add(dlyR, s0 + i, v * 0.5);
    add(revL, s0 + i, v * 0.4); add(revR, s0 + i, v * 0.4);
  }
}

/** A filtered noise swell that rises into a cut. */
function swell(tEnd, dur, gain) {
  const s0 = Math.round((tEnd - dur) * SR), len = Math.round(dur * SR);
  const fl = svf(), fr = svf();
  for (let i = 0; i < len; i++) {
    const u = i / len;
    const fc = 300 + 5000 * u * u;
    const env = Math.pow(u, 2.2) * gain;
    add(L, s0 + i, fl(noise(), fc, 1.2).bp * env);
    add(R, s0 + i, fr(noise(), fc * 1.05, 1.2).bp * env);
  }
}

/** A soft, dark impact (filtered noise + sub) on the big cuts. */
function impact(t0, gain) {
  const s0 = Math.round(t0 * SR), len = Math.round(2.5 * SR);
  const f = svf();
  for (let i = 0; i < len; i++) {
    const t = i / SR;
    const v = f(noise(), 2500 * Math.exp(-t * 1.5) + 300, 0.7).lp * Math.exp(-t * 2.2) * gain;
    add(L, s0 + i, v); add(R, s0 + i, v);
    add(revL, s0 + i, v * 0.8); add(revR, s0 + i, v * 0.8);
  }
}

// ── Score ────────────────────────────────────────────────────────────────────────────────────────
const arpPattern = [0, 2, 1, 3, 4, 2, 3, 1]; // indices into the chord tones, 16ths
for (let bar = 0; bar * BAR < 66; bar++) {
  const t0 = bar * BAR;
  const ch = chordAt(t0);
  pad(t0, BAR, ch.tones.slice(0, 4), t0 >= 56 && t0 < 60 ? 0.3 : 0.2, t0 >= 56 && t0 < 60 ? 1800 : 1400);
  for (let b = 0; b < 4; b++) {
    const tb = t0 + b * BEAT;
    if (has.kick(tb)) kick(tb, tb >= 60 && tb < 60.01 ? 1.15 : 1);
    if (has.clap(tb) && (b === 1 || b === 3)) clap(tb, 0.55);
    // Bass: on the off-beat eighths, plus the downbeat of each bar for weight.
    if (has.bass(tb)) {
      if (b === 0) bassNote(tb + 0.06, 0.14, ch.root, 0.5);
      bassNote(tb + BEAT / 2, 0.16, ch.root, 0.72);
    }
    if (has.hats(tb)) {
      hat(tb + BEAT / 2, 0.6, b === 3 && bar % 2 === 1);
      hat(tb + BEAT * 0.75 + 0.012, 0.22);
    }
  }
  for (let s = 0; s < 16; s++) {
    const ts = t0 + s * (BEAT / 4);
    if (!has.arp(ts)) continue;
    if (t0 < 4 && s % 2 === 1) continue; // sparser under the logo
    const idx = arpPattern[s % 8];
    const octave = s >= 8 && bar % 2 === 1 ? 12 : 0;
    const accent = s % 4 === 0 ? 1 : 0.7;
    pluck(ts, ch.tones[idx] + octave, 0.55 * accent, ((s % 2) - 0.5) * 0.5);
  }
}
// Bells: a slow, falling minor line on the first and third beat, from the first build onwards.
const bellLine = [76, 74, 72, 71, 72, 71, 69, 67];
for (let bar = 5; bar * BAR < 64; bar++) {
  const t0 = bar * BAR;
  if (t0 >= 56 && t0 < 58) continue;
  bell(t0, bellLine[(bar * 2) % 8], 0.14, -0.3);
  bell(t0 + BEAT * 2.5, bellLine[(bar * 2 + 1) % 8], 0.1, 0.3);
}
for (const cut of [16, 24, 40, 46, 56]) swell(cut, 1.6, cut === 56 ? 0.22 : 0.12);
swell(60, 3.2, 0.35);
impact(60, 0.5);
kick(64, 1.1); // the last hit on the logo
impact(64, 0.35);

// ── Sidechain, effects, master ───────────────────────────────────────────────────────────────────
// Duck the musical bed (not the kick itself, which was mixed separately) under every kick.
const kickOnlyL = new Float32Array(N), kickOnlyR = new Float32Array(N);
{
  // Re-render kicks into their own buffer so the bed can be ducked without touching them.
  const saveL = L.slice(), saveR = R.slice();
  L.fill(0); R.fill(0);
  for (let tb = 4; tb < 66; tb += BEAT) if (has.kick(tb)) kick(tb, tb >= 60 && tb < 60.01 ? 1.15 : 1);
  kick(64, 1.1);
  kickOnlyL.set(L); kickOnlyR.set(R);
  for (let i = 0; i < N; i++) { L[i] = saveL[i] - kickOnlyL[i]; R[i] = saveR[i] - kickOnlyR[i]; }
}
// Stereo ping-pong delay, dotted eighth.
{
  const d = Math.round(0.375 * SR), fb = 0.38;
  const lp1 = svf(), lp2 = svf();
  for (let i = d; i < N; i++) {
    dlyL[i] += lp1(dlyR[i - d] * fb, 3500).lp;
    dlyR[i] += lp2(dlyL[i - d] * fb, 3500).lp;
  }
}
// A small FDN reverb.
{
  const lens = [1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116].map((n) => Math.round(n * 2.1));
  const bufs = lens.map((n) => new Float32Array(n));
  const idx = lens.map(() => 0);
  const damp = lens.map(() => 0);
  const fbk = 0.84;
  for (let i = 0; i < N; i++) {
    const outs = bufs.map((b, k) => b[idx[k]]);
    const sum = outs.reduce((a, b) => a + b, 0) * (2 / lens.length);
    let l = 0, r = 0;
    for (let k = 0; k < lens.length; k++) {
      const input = (k % 2 ? revR[i] : revL[i]) * 0.3;
      damp[k] = damp[k] * 0.35 + (outs[k] - sum) * 0.65;
      bufs[k][idx[k]] = input + damp[k] * fbk;
      idx[k] = (idx[k] + 1) % lens[k];
      if (k % 2) r += outs[k]; else l += outs[k];
    }
    revL[i] = l * 0.5;
    revR[i] = r * 0.5;
  }
}
const outL = new Float32Array(N), outR = new Float32Array(N);
const ducker = new Float32Array(N);
{
  let env = 0;
  for (let i = 0; i < N; i++) {
    const target = kickEnv[i] || 0;
    env = target > env ? target : env * 0.9993;
    ducker[i] = 1 - 0.8 * env;
  }
}
for (let i = 0; i < N; i++) {
  const t = i / SR;
  const fadeOut = t > 64.4 ? Math.max(0, 1 - (t - 64.4) / 1.6) : 1; // silent by the last picture frame
  const bedL = (L[i] + dlyL[i] * 0.55 + revL[i] * 0.9) * ducker[i];
  const bedR = (R[i] + dlyR[i] * 0.55 + revR[i] * 0.9) * ducker[i];
  outL[i] = Math.tanh((bedL + kickOnlyL[i]) * 0.9) * fadeOut;
  outR[i] = Math.tanh((bedR + kickOnlyR[i]) * 0.9) * fadeOut;
}

// ── Write and normalise ──────────────────────────────────────────────────────────────────────────
function wav(file, l, r) {
  const data = Buffer.alloc(l.length * 8);
  for (let i = 0; i < l.length; i++) { data.writeFloatLE(l[i], i * 8); data.writeFloatLE(r[i], i * 8 + 4); }
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(36 + data.length, 4); h.write("WAVE", 8); h.write("fmt ", 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(3, 20); h.writeUInt16LE(2, 22); h.writeUInt32LE(SR, 24);
  h.writeUInt32LE(SR * 8, 28); h.writeUInt16LE(8, 32); h.writeUInt16LE(32, 34); h.write("data", 36); h.writeUInt32LE(data.length, 40);
  writeFileSync(file, Buffer.concat([h, data]));
}
const out = join(here, "out");
mkdirSync(out, { recursive: true });
const raw = join(out, ".music-raw.wav");
wav(raw, outL, outR);
const tone = "treble=g=3:f=5000:t=s,equalizer=f=300:t=q:w=1:g=-2";
const measure = spawnSync("ffmpeg", ["-hide_banner", "-i", raw, "-af", tone + ",loudnorm=I=-14:TP=-1:LRA=11:print_format=json", "-f", "null", "-"], { encoding: "utf8" });
const m = JSON.parse(measure.stderr.slice(measure.stderr.lastIndexOf("{")));
const final = join(out, "music.wav");
const res = spawnSync("ffmpeg", ["-y", "-loglevel", "error", "-i", raw, "-af",
  `${tone},loudnorm=I=-14:TP=-1:LRA=11:measured_I=${m.input_i}:measured_TP=${m.input_tp}:measured_LRA=${m.input_lra}:measured_thresh=${m.input_thresh}:offset=${m.target_offset}:linear=true,aresample=48000`,
  "-c:a", "pcm_s24le", final], { stdio: "inherit" });
if (res.status !== 0) process.exit(res.status ?? 1);
console.log(`music written to ${final} (input ${m.input_i} LUFS, ${m.input_tp} dBTP)`);
