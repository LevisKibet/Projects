"""Draws the open compendium (gui/compendium.png, 512x360, shown at 256x180) and its toast (gui/compendium_toast.png).

A worn leather binding with gold tooling, two parchment pages the same warm colour as the chronicle pictures, a
shadowed gutter with stitching, a page block showing at the edges and a red ribbon marker.
"""
from pathlib import Path
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

TEX = Path(__file__).resolve().parent.parent / 'src/main/resources/assets/ancientpeaks_lore/textures'
rng = np.random.default_rng(7)


def noise(w, h, scale, seed):
    r = np.random.default_rng(seed)
    small = r.random((max(2, h // scale), max(2, w // scale)))
    return np.asarray(Image.fromarray((small * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC), dtype=np.float32) / 255


def paper(w, h, seed, base=(229, 214, 180)):
    n = noise(w, h, 40, seed) * 0.6 + noise(w, h, 9, seed + 1) * 0.3 + noise(w, h, 2, seed + 2) * 0.1
    a = np.zeros((h, w, 3), np.float32)
    for i, c in enumerate(base):
        a[..., i] = c + (n - 0.5) * 26
    # darker towards the edges, like handled paper
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.minimum(np.minimum(xx, w - 1 - xx), np.minimum(yy, h - 1 - yy)).astype(np.float32)
    edge = np.clip(1 - d / 14, 0, 1) ** 2
    a -= edge[..., None] * np.array([38, 44, 50])
    return a


def leather(w, h):
    n = noise(w, h, 6, 11) * 0.5 + noise(w, h, 30, 12) * 0.5
    a = np.zeros((h, w, 3), np.float32)
    for i, c in enumerate((92, 52, 30)):
        a[..., i] = c * (0.75 + 0.45 * n)
    return a


W, H = 512, 360
img = leather(W, H)
# rounded cover, darker rim
mask = Image.new('L', (W, H), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, W - 1, H - 1), 18, fill=255)
yy, xx = np.mgrid[0:H, 0:W]
d = np.minimum(np.minimum(xx, W - 1 - xx), np.minimum(yy, H - 1 - yy)).astype(np.float32)
img *= (0.55 + 0.45 * np.clip(d / 10, 0, 1))[..., None]

# page block edges peeking out under each page (outer sides and bottom)
for k in range(4, 0, -1):
    shade = 200 - k * 18
    off = k * 2
    for x0, x1 in ((28 - off, 250), (262, 484 + off)):
        img[22:342 + off, x0:x1] = np.array([shade, shade - 14, shade - 40])

# the two pages
for x0, x1, seed in ((28, 252, 3), (260, 484, 5)):
    p = paper(x1 - x0, 324, seed)
    # gutter shadow on the inner side of each page
    t = np.linspace(0, 1, x1 - x0, dtype=np.float32)
    inner = t if x0 < 256 else t[::-1]
    p *= (1 - 0.32 * np.clip((inner - 0.82) / 0.18, 0, 1) ** 1.6)[None, :, None]
    img[18:342, x0:x1] = p

img = np.clip(img, 0, 255).astype(np.uint8)
book = Image.fromarray(img).convert('RGBA')
book.putalpha(mask)
g = ImageDraw.Draw(book)

# gold tooling on the cover
GOLD, GOLD_DIM = (201, 160, 72, 255), (150, 112, 48, 255)
g.rounded_rectangle((6, 6, W - 7, H - 7), 14, outline=GOLD_DIM, width=2)
g.rounded_rectangle((10, 10, W - 11, H - 11), 12, outline=(110, 72, 40, 255), width=1)
for cx, cy in ((14, 14), (W - 15, 14), (14, H - 15), (W - 15, H - 15)):
    g.regular_polygon((cx, cy, 5), 4, rotation=45, fill=GOLD)

# gutter: shadow line and stitching
g.rectangle((252, 18, 259, 341), fill=(96, 70, 44, 255))
g.line((255, 18, 255, 341), fill=(60, 40, 24, 255), width=2)
for y in range(30, 336, 22):
    g.line((255, y, 255, y + 9), fill=(214, 196, 150, 255), width=1)

# faint ink corner brackets with a little diamond
INK = (120, 92, 58, 170)
def corner(x, y, sx, sy):
    g.line(((x, y + sy * 16), (x, y), (x + sx * 16, y)), fill=INK, width=1)
    g.line(((x + sx * 3, y + sy * 10), (x + sx * 3, y + sy * 3), (x + sx * 10, y + sy * 3)), fill=INK, width=1)
    g.regular_polygon((x + sx * 7, y + sy * 7, 2), 4, rotation=45, fill=INK)
for (x, y, sx, sy) in ((34, 24, 1, 1), (245, 24, -1, 1), (34, 335, 1, -1), (245, 335, -1, -1),
                       (267, 24, 1, 1), (477, 24, -1, 1), (267, 335, 1, -1), (477, 335, -1, -1)):
    corner(x, y, sx, sy)

# a red ribbon marker tucked in at the bottom of the gutter, hanging below the book
FULL_H = 392
full = Image.new('RGBA', (W, FULL_H), (0, 0, 0, 0))
full.paste(book, (0, 0), book)
rg = ImageDraw.Draw(full)
x0 = 440
rg.rectangle((x0, 330, x0 + 10, 380), fill=(138, 26, 22, 255))
rg.line((x0 + 2, 330, x0 + 2, 380), fill=(176, 48, 40, 255), width=2)
rg.line((x0 + 10, 330, x0 + 10, 380), fill=(96, 16, 14, 255), width=1)
rg.polygon(((x0, 380), (x0 + 10, 380), (x0 + 10, 390), (x0 + 5, 384), (x0, 390)), fill=(138, 26, 22, 255))
full.save(TEX / 'gui/compendium.png')

# ---------------------------------------------------------------- the toast (160x32, drawn at 1:1, stored at 2x)
TW, TH = 320, 64
t = paper(TW, TH, 21)
t = Image.fromarray(np.clip(t, 0, 255).astype(np.uint8)).convert('RGBA')
tg = ImageDraw.Draw(t)
tg.rounded_rectangle((0, 0, TW - 1, TH - 1), 8, outline=(92, 52, 30, 255), width=4)
tg.rounded_rectangle((6, 6, TW - 7, TH - 7), 5, outline=GOLD_DIM, width=1)
m2 = Image.new('L', (TW, TH), 0); ImageDraw.Draw(m2).rounded_rectangle((0, 0, TW - 1, TH - 1), 8, fill=255)
t.putalpha(m2)
t.save(TEX / 'gui/compendium_toast.png')
print('drew book and toast')
