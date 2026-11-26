"""Builds every icon size the app needs from icon.png (the new sky-blue / purple icon).

The icon already has its own dark navy background, so it is used as a whole (no cut-out).
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = Image.open(os.path.join(ROOT, "icon.png")).convert("RGB")

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)

def sq(px):
    return SRC.resize((px, px), Image.LANCZOS).convert("RGBA")

def rounded(img, frac):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, img.width - 1, img.height - 1], radius=int(img.width * frac), fill=255)
    o = img.copy(); o.putalpha(m); return o

def circle(img):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, img.width - 1, img.height - 1], fill=255)
    o = img.copy(); o.putalpha(m); return o

# Adaptive icon foreground: the whole icon (artwork sits well inside the safe zone)
save(sq(432), os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"))

# In-app logo (header + welcome screen): rounded tile
save(rounded(sq(512), 0.22), os.path.join(RES, "drawable-nodpi", "app_logo.png"))

# Legacy icons for old Android versions
for folder, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    big = sq(px * 4)
    save(rounded(big, 0.22).resize((px, px), Image.LANCZOS), os.path.join(RES, "mipmap-" + folder, "ic_launcher.png"))
    save(circle(big).resize((px, px), Image.LANCZOS), os.path.join(RES, "mipmap-" + folder, "ic_launcher_round.png"))

# Play Store icon
save(sq(512).convert("RGB"), os.path.join(ROOT, "build-extras", "play-store-icon-512.png"))
print("icons created")
