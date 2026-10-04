#!/usr/bin/env python3
"""Generate the experimental OCLog2 to stock-look Rec.709 LUTs for Lightforge Studio.

Lightforge samples a custom LUT on linear light. It first decodes the clip with the chosen
input profile, then applies the Rec.709 OETF to the LUT output. These cubes assume the
"Standard Rec.709" input profile. Their input is therefore decode709(OCLog2 code), which they
undo before decoding OCLog2. Their output is display-referred linear Rec.709 in [0, 1].

The look is a phone-style rendering, not a normative transform:
- the source tier's grey goes to about 0.42 Rec.709 code;
- a Hable filmic curve with a soft shoulder;
- a vibrance-style saturation boost;
- BT.2020 colours outside Rec.709 are pulled toward their luma.
"""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "docs" / "color" / "lightforge"
SIZE = 65
LN51 = math.log(51.0)
BT2020_TO_BT709 = (
    (1.660491, -0.587641, -0.072850),
    (-0.124550, 1.132900, -0.008349),
    (-0.018151, -0.100579, 1.118730),
)
LUMA709 = (0.2126, 0.7152, 0.0722)
HLG_GREY = 0.38**2 / 3  # NATIVE grey reference of the HLG-derived tier (spec.json)
TIERS = {
    "hlg": ("OCLog2_HLG_to_Stock709.cube", "OCLog2 HLG10 to Stock 709"),
    "sdr": ("OCLog2_HFR-SDR_to_Stock709.cube", "OCLog2 HFR-SDR to Stock 709"),
}


def decode709(value: float) -> float:
    value = min(max(value, 0.0), 1.0)
    return value / 4.5 if value < 0.081 else ((value + 0.099) / 1.099) ** (1 / 0.45)


def encode709(value: float) -> float:
    value = min(max(value, 0.0), 1.0)
    return 4.5 * value if value < 0.018 else 1.099 * value**0.45 - 0.099


def oclog2_decode(code: float) -> float:
    return (math.exp(LN51 * (code - 0.10) / 0.80) - 1) / 50


def oclog2_encode(linear: float) -> float:
    return 0.10 + 0.80 * math.log1p(50 * min(max(linear, 0.0), 1.0)) / LN51


def luma(rgb: list[float]) -> float:
    return sum(weight * channel for weight, channel in zip(LUMA709, rgb))


def hable(x: float) -> float:
    a, b, c, d, e, f = 0.15, 0.50, 0.10, 0.20, 0.02, 0.30
    return (x * (a * x + c * b) + d * e) / (x * (a * x + b) + d * f) - e / f


def saturate(rgb: list[float], amount: float) -> list[float]:
    """Vibrance-style boost: full strength on muted colours, none on already saturated ones."""
    y = luma(rgb)
    high = max(rgb)
    purity = min(max((high - min(rgb)) / max(high, 1e-9), 0.0), 1.0)
    gain = 1 + (amount - 1) * (1 - purity) ** 2
    return [y + (channel - y) * gain for channel in rgb]


def soft_gamut(rgb: list[float]) -> list[float]:
    """Pull colours with a negative component toward their luma instead of hard-clipping."""
    low = min(rgb)
    if low >= 0:
        return rgb
    y = luma(rgb)
    t = min(max(y / max(y - low, 1e-9), 0.0), 1.0)
    return [y + (channel - y) * t for channel in rgb]


def soft_clip(value: float, knee: float = 0.8) -> float:
    """Roll a channel into 1.0 above the knee so the cube stays smooth."""
    value = max(value, 0.0)
    if value <= knee:
        return value
    return knee + (1 - knee) * math.tanh((value - knee) / (1 - knee))


def look(code: tuple[float, float, float], tier: str) -> list[float]:
    """OCLog2 code values to display-referred linear Rec.709."""
    scene = [oclog2_decode(channel) for channel in code]
    rgb = [sum(m * s for m, s in zip(row, scene)) for row in BT2020_TO_BT709]
    if tier == "hlg":
        gain = 0.18 / HLG_GREY  # HLG-tier grey 0.048 to 0.18; scene white lands 4.4 stops over grey
        rgb = soft_gamut(saturate([max(channel * gain, -1.0) for channel in rgb], 1.25))
        bias = 3.2  # puts 18% grey near 0.19 linear, about 0.42 Rec.709 code
        white = hable(bias * gain)
        rgb = [hable(bias * max(channel, 0.0)) / white for channel in rgb]
    elif tier == "sdr":
        # The HFR tier already is the ISP's Rec.709 rendering; only add the saturation.
        rgb = soft_gamut(saturate(rgb, 1.20))
    else:
        raise ValueError(f"unknown tier {tier}")
    return [soft_clip(channel) for channel in rgb]


def build(tier: str, size: int = SIZE) -> list[list[float]]:
    """Return the lattice rows in .cube order (red fastest).

    OCLog2 never goes below code 0.10, i.e. decode709 = black. The nodes below black never meet
    real data on their own. Instead of being sampled, they are extrapolated linearly, per axis,
    from the exact values at black and at the next node. That makes the first used cell exact at
    scene black.
    """
    grid = [index / (size - 1) for index in range(size)]
    black = decode709(0.10)
    first = next(index for index, value in enumerate(grid) if value >= black)
    coords = [black if index < first else value for index, value in enumerate(grid)]
    codes = [encode709(value) for value in coords]
    values = [
        [[look((codes[r], codes[g], codes[b]), tier) for r in range(size)] for g in range(size)]
        for b in range(size)
    ]

    def extrapolate(get, put) -> None:
        at_black = get(0)
        anchor = get(first)
        for index in range(first):
            t = (grid[index] - grid[first]) / (black - grid[first])
            put(index, [a + (k - a) * t for a, k in zip(anchor, at_black)])

    for b in range(size):
        for g in range(size):
            row = values[b][g]
            extrapolate(lambda i: row[i], lambda i, v: row.__setitem__(i, v))
    for b in range(size):
        for r in range(size):
            plane = values[b]
            extrapolate(lambda i: plane[i][r], lambda i, v: plane[i].__setitem__(r, v))
    for g in range(size):
        for r in range(size):
            extrapolate(lambda i: values[i][g][r], lambda i, v: values[i][g].__setitem__(r, v))
    return [
        [max(channel, 0.0) for channel in values[b][g][r]]
        for b in range(size)
        for g in range(size)
        for r in range(size)
    ]


def render(tier: str, size: int = SIZE) -> str:
    _, title = TIERS[tier]
    lines = [
        f'TITLE "{title}"',
        "# OpenCineCam OCLog2 v2 to a stock-camera Rec.709 look for Lightforge Studio. Experimental.",
        '# Lightforge input profile: "Standard Rec.709" (the cube undoes that decode).',
        "# Generated by tools/generate_lightforge_luts.py.",
        f"LUT_3D_SIZE {size}",
        "DOMAIN_MIN 0.0 0.0 0.0",
        "DOMAIN_MAX 1.0 1.0 1.0",
    ]
    lines += [" ".join(f"{channel:.5f}" for channel in row) for row in build(tier, size)]
    return "\n".join(lines) + "\n"


def parse_rows(text: str) -> list[list[float]]:
    return [
        [float(part) for part in line.split()]
        for line in text.splitlines()
        if line[:1].isdigit() or line[:1] in "-."
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="verify the committed cubes instead of writing")
    args = parser.parse_args()
    failed = False
    for tier, (name, _) in TIERS.items():
        path = args.output / name
        text = render(tier)
        if not args.check:
            path.write_text(text)
            continue
        expected = parse_rows(text)
        actual = parse_rows(path.read_text()) if path.exists() else []
        worst = max((abs(e - a) for er, ar in zip(expected, actual) for e, a in zip(er, ar)), default=math.inf)
        if len(expected) != len(actual) or worst > 2e-5:
            print(f"{name}: stale (max difference {worst})")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
