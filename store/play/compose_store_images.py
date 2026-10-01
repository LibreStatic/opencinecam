#!/usr/bin/env python3
"""Compose Google Play store graphics for OpenCineCam: framed screenshots with localized captions,
1024x500 feature graphics and the 512x512 icon (rendered from the launcher icon resources).

Usage: python3 store/play/compose_store_images.py [--only locale ...]
Inputs : store/play/raw/<locale>/{phone,tablet}/NN.png  (real app screens, see raw/MEDIA_LICENSES.md for the CC0 photos composited into them)
         store/play/listings/<locale>/{screenshot_captions,title,short_description}.txt
         app/src/main/res/{drawable/ic_launcher_foreground.xml,values/icon.xml}
Outputs: store/play/images/<locale>/{phoneScreenshots,tabletScreenshots}/NN.png, featureGraphic.png
         store/play/images/{icon,featureGraphic}.png
Rendering uses headless Chrome through Playwright. Outputs are RGB (no alpha).
"""
import argparse, base64, html, io, pathlib, re
from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent.parent
LISTINGS, RAW, OUT = ROOT / "listings", ROOT / "raw", ROOT / "images"
RES = REPO / "app/src/main/res"
LOCALES = ["en-US", "es-419", "es-ES", "pt-BR", "pt-PT", "fr-FR", "de-DE", "it-IT"]
RAW_ALIAS = {"es-ES": "es-419", "pt-PT": "pt-BR"}     # reuse captures taken in the sibling locale
CHROME = "/usr/bin/google-chrome-stable"
# Brand: launcher amber on the launcher's near-black
BG, BG2, FG, SUB, AMBER = "#0c0e10", "#161a1d", "#F6F2EA", "#B9C0C4", "#FFB300"
PHONE, TABLET = (1080, 1920), (2560, 1440)   # Play requires exactly 16:9 / 9:16

def b64(path, fmt="JPEG", quality=95, max_w=None):
    im = Image.open(path).convert("RGB")
    if max_w and im.width > max_w:
        im = im.resize((max_w, round(im.height * max_w / im.width)), Image.LANCZOS)
    buf = io.BytesIO(); im.save(buf, fmt, quality=quality)
    return f"data:image/{fmt.lower()};base64," + base64.b64encode(buf.getvalue()).decode()

def read(loc, name):
    p = LISTINGS / loc / name
    return p.read_text(encoding="utf-8").strip() if p.exists() else ""

BASE_CSS = f"""
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{background:{BG};overflow:hidden}}
body{{font-family:'Open Sans','Noto Sans','Adwaita Sans',sans-serif;color:{FG};-webkit-font-smoothing:antialiased}}
.glow{{position:absolute;border-radius:50%;filter:blur(130px);background:{AMBER}}}
.bar{{height:8px;border-radius:4px;background:{AMBER}}}
"""

def screenshot_html(w, h, caption, shot, kind, shot_ratio):
    if kind == "phone":
        fs, top_h, max_w, bez, radius = 62, 250, 920, 12, 56
    else:
        fs, top_h, max_w, bez, radius = 84, 230, 2230, 16, 44
    # Fit the framed capture into the space under the caption (shot_ratio = width / height).
    dev_w = min(max_w, round((h - top_h - 40) * shot_ratio) + 2 * bez)
    dev_x = (w - dev_w) // 2
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{BASE_CSS}
html,body{{width:{w}px;height:{h}px;background:radial-gradient(120% 70% at 50% 100%,{BG2},{BG})}}
.glow{{width:{w*0.6}px;height:{w*0.6}px;left:{w*0.2}px;top:{-w*0.42}px;opacity:.16}}
#cap{{position:absolute;left:70px;right:70px;top:30px;height:{top_h-50}px;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center}}
#cap .bar{{width:96px;margin-bottom:22px}}
#cap h1{{font-size:{fs}px;line-height:1.1;font-weight:800;letter-spacing:-1px;text-wrap:balance}}
#dev{{position:absolute;left:{dev_x}px;top:{top_h-12}px;width:{dev_w}px;padding:{bez}px;border-radius:{radius}px;background:linear-gradient(145deg,#2b3034,#111416);
  box-shadow:0 0 0 2px #4a5258 inset,0 0 0 1px #000,0 40px 120px rgba(0,0,0,.7),0 0 160px rgba(255,179,0,.10)}}
#dev img{{display:block;width:100%;border-radius:{radius-bez}px}}
</style></head><body><div class="glow"></div>
<div id="cap"><div class="bar"></div><h1>{html.escape(caption)}</h1></div>
<div id="dev"><img src="{shot}"></div></body></html>"""

def feature_html(title, tagline, icon, shot):
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{BASE_CSS}
html,body{{width:1024px;height:500px;background:linear-gradient(120deg,{BG} 40%,#1a1f23)}}
.glow{{width:560px;height:560px;left:560px;top:-120px;opacity:.20}}
#icon{{position:absolute;left:60px;top:80px;width:128px;height:128px;border-radius:30px;box-shadow:0 12px 40px rgba(0,0,0,.6)}}
#t{{position:absolute;left:60px;top:228px;width:560px}}
#t .bar{{width:72px;margin-bottom:18px}}
#t h1{{font-size:62px;line-height:1.02;font-weight:800;letter-spacing:-1.5px}}
#t p{{margin-top:16px;font-size:25px;line-height:1.3;color:{SUB}}}
#dev{{position:absolute;left:700px;top:46px;width:270px;padding:6px;border-radius:30px;background:linear-gradient(145deg,#2b3034,#111416);
  box-shadow:0 0 0 1px #4a5258 inset,0 20px 60px rgba(0,0,0,.7)}}
#dev img{{display:block;width:100%;border-radius:24px}}
</style></head><body><div class="glow"></div><img id="icon" src="{icon}">
<div id="t"><div class="bar"></div><h1>{html.escape(title)}</h1><p>{html.escape(tagline)}</p></div>
<div id="dev"><img src="{shot}"></div></body></html>"""

def icon_svg():
    """Convert the launcher foreground vector drawable to SVG on the launcher background colour."""
    xml = (RES / "drawable/ic_launcher_foreground.xml").read_text()
    bg = re.search(r'name="launcher_icon_background">(#\w+)<', (RES / "values/icon.xml").read_text()).group(1)
    body, clip_open = [], False
    for tag in re.findall(r"<(?:path|clip-path)\b[^>]*/>", xml):
        a = dict(re.findall(r'android:(\w+)="([^"]*)"', tag))
        d = a["pathData"]
        if tag.startswith("<clip-path"):
            body.append(f'<clipPath id="c"><path d="{d}"/></clipPath><g clip-path="url(#c)">'); clip_open = True; continue
        fill = a.get("fillColor", "none"); fill = "none" if "transparent" in fill else fill
        stroke = f' stroke="{a["strokeColor"]}" stroke-width="{a["strokeWidth"]}" stroke-linejoin="{a.get("strokeLineJoin", "miter")}"' if "strokeColor" in a else ""
        cap = f' stroke-linecap="{a["strokeLineCap"]}"' if "strokeLineCap" in a else ""
        body.append(f'<path d="{d}" fill="{fill}"{stroke}{cap}/>')
    if clip_open: body.append("</g>")
    return bg, f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000" width="512" height="512"><rect width="1000" height="1000" fill="{bg}"/>{"".join(body)}</svg>'

def render(page, html_str, w, h, out):
    page.set_viewport_size({"width": w, "height": h})
    page.set_content(html_str, wait_until="load")
    page.wait_for_timeout(200)
    out.parent.mkdir(parents=True, exist_ok=True)
    png = page.screenshot(type="png", clip={"x": 0, "y": 0, "width": w, "height": h})
    Image.open(io.BytesIO(png)).convert("RGB").save(out, "PNG", optimize=True)

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--only", nargs="*"); a = ap.parse_args()
    locs = a.only or LOCALES
    OUT.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        br = p.chromium.launch(executable_path=CHROME, args=["--no-sandbox"])
        page = br.new_page()
        _, svg = icon_svg()
        render(page, f"<!doctype html><html><body style='margin:0;background:#000'>{svg}</body></html>", 512, 512, OUT / "icon.png")
        icon_b64 = b64(OUT / "icon.png", "PNG")
        for loc in locs:
            caps = read(loc, "screenshot_captions.txt").splitlines()
            if len(caps) < 8:
                print(f"[{loc}] WARNING: captions missing, falling back to en-US"); caps = read("en-US", "screenshot_captions.txt").splitlines()
            raw_loc = RAW_ALIAS.get(loc, loc)
            for kind, (w, h), sub in (("phone", PHONE, "phoneScreenshots"), ("tablet", TABLET, "tabletScreenshots")):
                for i in range(8):
                    src = RAW / raw_loc / kind / f"{i+1:02d}.png"
                    if not src.exists():
                        print(f"[{loc}] missing raw {src}"); continue
                    with Image.open(src) as im: ratio = im.width / im.height
                    shot = b64(src, "JPEG", 95, max_w=1100 if kind == "phone" else 2300)
                    render(page, screenshot_html(w, h, caps[i], shot, kind, ratio), w, h, OUT / loc / sub / f"{i+1:02d}.png")
            title = read(loc, "title.txt").split(":")[0].strip() or "OpenCineCam"
            tag = read(loc, "short_description.txt")
            hero = RAW / raw_loc / "phone" / "01.png"
            render(page, feature_html(title, tag, icon_b64, b64(hero, "JPEG", 90, max_w=560)), 1024, 500, OUT / loc / "featureGraphic.png")
            print(f"[{loc}] done")
        en = RAW / RAW_ALIAS.get("en-US", "en-US") / "phone" / "01.png"
        render(page, feature_html(read("en-US", "title.txt").split(":")[0].strip(), read("en-US", "short_description.txt"), icon_b64, b64(en, "JPEG", 90, max_w=560)), 1024, 500, OUT / "featureGraphic.png")
        br.close()

if __name__ == "__main__":
    main()
