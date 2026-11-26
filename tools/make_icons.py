"""Builds every icon size the app needs from your own logo PNG.

Put your logo in the repository root as icon.png (any single .png in the root also works).
The black background of the logo is removed automatically, then the logo is placed with the
correct safe-zone padding for Android adaptive icons, so it looks crisp and centred.
"""
import glob, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
BG = (238, 243, 255)  # launcher background colour (matches colors.xml)

def find_source():
    p = os.path.join(ROOT, "icon.png")
    if os.path.exists(p):
        return p
    pngs = sorted(glob.glob(os.path.join(ROOT, "*.png")))
    if pngs:
        return pngs[0]
    sys.exit("ERROR: put your logo in the repository root and name it icon.png")

def cutout(src):
    """Remove the black background (flood fill from the corners), keep the dark folder symbol."""
    rgb = src.convert("RGB")
    w, h = rgb.size
    work = rgb.copy()
    for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        ImageDraw.floodfill(work, seed, (255, 0, 255), thresh=45)
    a = np.array(work)
    bg = (a[:, :, 0] == 255) & (a[:, :, 1] == 0) & (a[:, :, 2] == 255)
    mask = Image.fromarray((bg * 255).astype("uint8")).filter(ImageFilter.MaxFilter(5))
    alpha = mask.point(lambda v: 255 - v).filter(ImageFilter.GaussianBlur(1.3))
    out = rgb.convert("RGBA")
    out.putalpha(alpha)
    box = alpha.point(lambda v: 255 if v > 10 else 0).getbbox()
    return out.crop(box)

def place(art, canvas, frac):
    """Paste art centred, art width = frac of the canvas width."""
    w = int(canvas.width * frac)
    s = w / art.width
    ic = art.resize((w, max(1, int(art.height * s))), Image.LANCZOS)
    canvas.alpha_composite(ic, ((canvas.width - ic.width) // 2, (canvas.height - ic.height) // 2))
    return canvas

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)

art = cutout(Image.open(find_source()))

# Adaptive icon foreground (108dp = 432px, logo kept inside the safe zone)
fg = place(art, Image.new("RGBA", (432, 432), (0, 0, 0, 0)), 0.60)
save(fg, os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"))

# In-app logo (transparent, square, small padding)
side = int(max(art.size) * 1.08)
logo = Image.new("RGBA", (side, side), (0, 0, 0, 0))
logo.alpha_composite(art, ((side - art.width) // 2, (side - art.height) // 2))
save(logo.resize((512, 512), Image.LANCZOS), os.path.join(RES, "drawable-nodpi", "app_logo.png"))

# Legacy icons for old Android versions
for folder, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    big = px * 4
    base = place(art, Image.new("RGBA", (big, big), BG + (255,)), 0.70)
    sq = Image.new("L", (big, big), 0)
    ImageDraw.Draw(sq).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=255)
    ci = Image.new("L", (big, big), 0)
    ImageDraw.Draw(ci).ellipse([0, 0, big - 1, big - 1], fill=255)
    for name, m in [("ic_launcher.png", sq), ("ic_launcher_round.png", ci)]:
        o = base.copy(); o.putalpha(m)
        save(o.resize((px, px), Image.LANCZOS), os.path.join(RES, "mipmap-" + folder, name))

# Play Store icon (512 x 512, full square)
store = place(art, Image.new("RGBA", (512, 512), BG + (255,)), 0.66).convert("RGB")
save(store, os.path.join(ROOT, "build-extras", "play-store-icon-512.png"))
print("icons created")
