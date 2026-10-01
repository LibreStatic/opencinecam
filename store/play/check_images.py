#!/usr/bin/env python3
"""Validate Google Play graphics under store/play/images/ (dimensions, size, alpha, counts)."""
import pathlib, sys
from PIL import Image

IMG = pathlib.Path(__file__).resolve().parent / "images"
LOCALES = ["en-US", "es-419", "es-ES", "pt-BR", "pt-PT", "fr-FR", "de-DE", "it-IT"]
MAX_BYTES = 8 * 1024 * 1024
errors = []

def err(p, msg): errors.append(f"{p.relative_to(IMG.parent)}: {msg}")

def has_alpha(im):
    if im.mode in ("RGBA", "LA", "PA"):
        a = im.getchannel("A") if im.mode != "PA" else im.convert("RGBA").getchannel("A")
        return a.getextrema()[0] < 255          # only real transparency counts
    return "transparency" in im.info

def check_shot(p, landscape):
    if not p.exists(): return err(p, "missing")
    if p.stat().st_size > MAX_BYTES: err(p, f"{p.stat().st_size} bytes > 8MB")
    im = Image.open(p); w, h = im.size
    if im.format not in ("PNG", "JPEG"): err(p, f"format {im.format}")
    if not (320 <= w <= 3840 and 320 <= h <= 3840): err(p, f"side out of 320-3840: {w}x{h}")
    if max(w, h) * 9 != min(w, h) * 16: err(p, f"aspect ratio not 16:9: {w}x{h}")
    if min(w, h) < 1080: err(p, f"min side < 1080: {w}x{h}")
    if not (w, h) in ((1080, 1920), (2560, 1440)): err(p, f"unexpected size {w}x{h}")
    if has_alpha(im): err(p, "has transparency")
    if landscape and w <= h: err(p, "expected landscape")
    if not landscape and w >= h: err(p, "expected portrait")

def check_feature(p):
    if not p.exists(): return err(p, "missing")
    im = Image.open(p)
    if im.size != (1024, 500): err(p, f"size {im.size} != 1024x500")
    if im.format not in ("PNG", "JPEG"): err(p, f"format {im.format}")
    if has_alpha(im) or im.mode != "RGB": err(p, f"alpha channel present or not RGB (mode {im.mode})")
    if p.stat().st_size > 15 * 1024 * 1024: err(p, "file too large")

icon = IMG / "icon.png"
if not icon.exists(): err(icon, "missing")
else:
    im = Image.open(icon)
    if im.size != (512, 512): err(icon, f"size {im.size} != 512x512")
    if im.format != "PNG": err(icon, "not PNG")
    if has_alpha(im) or im.mode != "RGB": err(icon, f"mode {im.mode}, expected RGB without alpha")
    if icon.stat().st_size > 1024 * 1024: err(icon, "larger than 1MB")
check_feature(IMG / "featureGraphic.png")
n = 0
for loc in LOCALES:
    check_feature(IMG / loc / "featureGraphic.png")
    for i in range(1, 9):
        check_shot(IMG / loc / "phoneScreenshots" / f"{i:02d}.png", False); n += 1
        check_shot(IMG / loc / "tabletScreenshots" / f"{i:02d}.png", True); n += 1
if errors:
    print("FAILED:"); [print(" -", e) for e in errors]; sys.exit(1)
print(f"OK: {n} screenshots, {len(LOCALES)+1} feature graphics, icon validated")
