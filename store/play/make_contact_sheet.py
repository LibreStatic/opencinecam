#!/usr/bin/env python3
"""Build store/play/raw/contact-sheet.png (gitignored): phone rows and tablet rows for every locale + feature graphics."""
import pathlib
from PIL import Image, ImageDraw
ROOT = pathlib.Path(__file__).resolve().parent; IMG = ROOT / "images"
LOCALES = ["en-US", "es-419", "es-ES", "pt-BR", "pt-PT", "fr-FR", "de-DE", "it-IT"]
PW, PH, TW, TH, LAB = 120, 240, 288, 180, 70
W = LAB + 8 * (TW + 6); rows = []
for loc in LOCALES: rows += [(loc, "phone", PH), (loc, "tablet", TH)]
H = sum(r[2] + 6 for r in rows) + len(LOCALES) * 90
S = Image.new("RGB", (W, H), "#222"); d = ImageDraw.Draw(S); y = 0
for loc in LOCALES:
    fg = Image.open(IMG / loc / "featureGraphic.png").resize((180, 88)); S.paste(fg, (0, y)); d.text((200, y + 40), loc + "  feature graphic", fill="white"); y += 90
    for kind, sub, w, h in (("phone", "phoneScreenshots", PW, PH), ("tablet", "tabletScreenshots", TW, TH)):
        d.text((4, y + h // 2), f"{loc}\n{kind}", fill="white")
        for i in range(8):
            im = Image.open(IMG / loc / sub / f"{i+1:02d}.png").convert("RGB").resize((w, h))
            S.paste(im, (LAB + i * (TW + 6), y))
        y += h + 6
(ROOT / "raw").mkdir(exist_ok=True); S.save(ROOT / "raw" / "contact-sheet.png"); print("contact sheet", S.size)
