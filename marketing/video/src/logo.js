/* SPDX-License-Identifier: Apache-2.0 */
/* docs/branding/opencinecam-logo.svg, drawn in pieces so each can animate. Viewport 1000 x 1000. */
import { C } from "./ui.js";

const Ring = new Path2D("M 697.99 697.99 L 768.70 768.70 A 380 380 0 1 1 768.70 231.30 L 697.99 302.01 A 280 280 0 1 0 697.99 697.99 Z");
const GreyArc = new Path2D("M 627.50 720.84 A 255 255 0 1 1 627.50 279.16");
const CyanArcs = [new Path2D("M 646.26 291.12 A 255 255 0 0 1 746.31 434.00"), new Path2D("M 746.31 566.00 A 255 255 0 0 1 646.26 708.88")];
const Blades = [
  "564.95,537.50 500.00,575.00 -149.52,950.00 564.95,1287.50",
  "500.00,575.00 435.05,537.50 -214.47,162.50 -149.52,950.00",
  "435.05,537.50 435.05,462.50 435.05,-287.50 -214.47,162.50",
  "435.05,462.50 500.00,425.00 1149.52,50.00 435.05,-287.50",
  "500.00,425.00 564.95,462.50 1214.47,837.50 1149.52,50.00",
  "564.95,462.50 564.95,537.50 564.95,1287.50 1214.47,837.50",
].map((pts) => {
  const p = new Path2D();
  pts.split(" ").forEach((xy, i) => {
    const [x, y] = xy.split(",").map(Number);
    if (i === 0) p.moveTo(x, y); else p.lineTo(x, y);
  });
  p.closePath();
  return p;
});

function wedge(ctx, from, sweep) {
  ctx.beginPath();
  ctx.moveTo(500, 500);
  ctx.arc(500, 500, 700, from, from + sweep);
  ctx.closePath();
}

/**
 * Draws the logo centred at (cx, cy), [size] px wide. Progress values run 0→1:
 * iris (scale and turn in), ring (sweep), arcs (sweep), dot (pop).
 */
export function drawLogo(ctx, cx, cy, size, { iris = 1, ring = 1, arcs = 1, dot = 1, alpha = 1 } = {}) {
  if (alpha <= 0) return;
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.translate(cx, cy);
  ctx.scale(size / 1000, size / 1000);
  ctx.translate(-500, -500);

  if (ring > 0) {
    ctx.save();
    // The ring opens at the right (the C's mouth); sweep from the mouth's lower lip round.
    wedge(ctx, Math.PI / 4, ring * (Math.PI * 1.5 + 0.01));
    ctx.clip();
    ctx.fillStyle = C.amber;
    ctx.fill(Ring);
    ctx.restore();
  }
  if (arcs > 0) {
    ctx.save();
    wedge(ctx, Math.PI * 0.32, arcs * Math.PI * 1.36);
    ctx.clip();
    ctx.lineWidth = 12;
    ctx.strokeStyle = "#9CA6AA";
    ctx.stroke(GreyArc);
    ctx.restore();
    ctx.save();
    wedge(ctx, -Math.PI * 0.32, arcs * Math.PI * 0.64);
    ctx.clip();
    ctx.lineWidth = 12;
    ctx.strokeStyle = C.cyan;
    CyanArcs.forEach((a) => ctx.stroke(a));
    ctx.restore();
  }
  if (iris > 0) {
    ctx.save();
    ctx.translate(500, 500);
    ctx.rotate((1 - iris) * -1.4);
    ctx.scale(iris, iris);
    ctx.translate(-500, -500);
    ctx.beginPath();
    ctx.arc(500, 500, 215, 0, Math.PI * 2);
    ctx.clip();
    for (const b of Blades) {
      ctx.fillStyle = C.amber;
      ctx.fill(b);
      ctx.lineWidth = 8;
      ctx.lineJoin = "miter";
      ctx.strokeStyle = C.paper; // the separators take the page colour, as the app's splash takes the theme's
      ctx.stroke(b);
    }
    ctx.restore();
  }
  if (dot > 0) {
    ctx.fillStyle = C.rec;
    ctx.beginPath();
    ctx.arc(755, 500, 16 * dot, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.restore();
}
