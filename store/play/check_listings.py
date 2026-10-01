#!/usr/bin/env python3
"""Validate Google Play listing text limits for every locale under listings/.

Exit code is 1 when any locale violates a limit or misses a required file.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "listings"
LOCALES = ["en-US", "es-419", "es-ES", "pt-BR", "pt-PT", "fr-FR", "de-DE", "it-IT"]
LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
CAPTION_LINES, CAPTION_MAX = 8, 40
STATUS_WORDS = re.compile(
    r"\b(beta|bêta|early access|acceso anticipado|acesso antecipado|accès anticipé|accesso anticipato|"
    r"new|nuevo|nueva|novo|nova|nouveau|nouvelle|neu|nuovo|best|mejor|melhor|meilleur|beste|migliore|"
    r"free|gratis|gratuito|gratuit|kostenlos|grátis)\b|#\s?1",
    re.I,
)
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿⬀-⯿]")
PRICE = re.compile(r"(US\$|USD|ARS|\$\s?\d|\d\s?\$|€\s?\d|\d\s?€)")


def read(path: Path) -> str | None:
    return path.read_text(encoding="utf-8").rstrip("\n") if path.exists() else None


def main() -> int:
    errors: list[str] = []
    for locale in LOCALES:
        folder = ROOT / locale
        counts: list[str] = []
        for name, limit in LIMITS.items():
            text = read(folder / name)
            if text is None:
                errors.append(f"{locale}/{name}: missing")
                continue
            counts.append(f"{name.removesuffix('.txt')}={len(text)}/{limit}")
            if not text.strip():
                errors.append(f"{locale}/{name}: empty")
            if len(text) > limit:
                errors.append(f"{locale}/{name}: {len(text)} > {limit}")
            if EMOJI.search(text):
                errors.append(f"{locale}/{name}: contains emoji")
            if PRICE.search(text):
                errors.append(f"{locale}/{name}: mentions a price")
        for name in ("title.txt", "short_description.txt"):
            text = read(folder / name) or ""
            if STATUS_WORDS.search(text):
                errors.append(f"{locale}/{name}: must NOT use store-status words (beta, early access, new, best, #1, free; Play metadata policy)")
        caps = read(folder / "screenshot_captions.txt")
        if caps is None:
            errors.append(f"{locale}/screenshot_captions.txt: missing")
        else:
            lines = caps.split("\n")
            if len(lines) != CAPTION_LINES:
                errors.append(f"{locale}/screenshot_captions.txt: {len(lines)} lines, expected {CAPTION_LINES}")
            longest = max((len(line) for line in lines), default=0)
            counts.append(f"captions={len(lines)} lines, max {longest}/{CAPTION_MAX}")
            for number, line in enumerate(lines, 1):
                if not line.strip() or len(line) > CAPTION_MAX:
                    errors.append(f"{locale}/screenshot_captions.txt:{number}: {len(line)} chars (max {CAPTION_MAX}, non-empty)")
        print(f"{locale}: " + ", ".join(counts))
    for extra in sorted(p.name for p in ROOT.iterdir() if p.is_dir() and p.name not in LOCALES):
        errors.append(f"unexpected locale folder: {extra}")
    if errors:
        print("\nFAILED:", *errors, sep="\n  ", file=sys.stderr)
        return 1
    print("\nAll listings within limits.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
