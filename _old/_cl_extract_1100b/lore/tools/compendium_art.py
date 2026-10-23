"""Draws the compendium's textures: the open book (gui/compendium.png), a placeholder picture for every chronicle
(gui/compendium/book_N.png, 256x192: replace these with the real pictures, same names and size) and the item icon.
    python3 tools/compendium_art.py"""
import pathlib, random, math, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont
ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX = ROOT / 'src/main/resources/assets/ancientpeaks_lore/textures'
R = random.Random(9)
sys.path.insert(0, str(ROOT / 'src/main/java'))

def parchment(w, h, seed, base=(232, 214, 176)):
    r = random.Random(seed)
    im = Image.new('RGB', (w, h), base)
    px = im.load()
    noise = Image.effect_noise((w, h), 22).filter(ImageFilter.GaussianBlur(1.2))
    blot = Image.effect_noise((w // 8 + 1, h // 8 + 1), 60).resize((w, h), Image.BICUBIC)
    for y in range(h):
        for x in range(w):
            n = noise.getpixel((x, y)) - 128; b = blot.getpixel((x, y)) - 128
            ex = min(x, w - 1 - x, y, h - 1 - y)
            edge = max(0, 18 - ex) * 2.2                      # darker, browner towards the edges
            c = [v + n * 0.18 + b * 0.10 - edge for v in base]
            c[2] -= edge * 0.6
            px[x, y] = tuple(max(0, min(255, int(v))) for v in c)
    d = ImageDraw.Draw(im)
    for _ in range(int(w * h / 9000)):                        # foxing spots
        x, y, rr = r.randrange(w), r.randrange(h), r.uniform(1, 4)
        d.ellipse((x - rr, y - rr, x + rr, y + rr), fill=(196, 160, 110))
    return im

# ---- the open book
W, H = 512, 360
book = Image.new('RGBA', (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(book)
d.rounded_rectangle((0, 6, W - 1, H - 1), 18, fill=(74, 42, 24, 255))            # leather cover
leather = Image.effect_noise((W, H), 40).convert('L')
cov = Image.new('RGBA', (W, H), (0, 0, 0, 0)); cpx = cov.load(); lp = leather.load()
for y in range(H):
    for x in range(W):
        if book.getpixel((x, y))[3]:
            v = lp[x, y] - 128
            cpx[x, y] = (max(0, 74 + v // 6), max(0, 42 + v // 8), max(0, 24 + v // 10), 255)
book = Image.alpha_composite(book, cov); d = ImageDraw.Draw(book)
d.rounded_rectangle((6, 12, W - 7, H - 7), 14, outline=(150, 112, 50, 255), width=2)  # gilt line
for i, (x0, x1) in enumerate(((20, W // 2 - 4), (W // 2 + 4, W - 20))):           # page stacks under the pages
    for k in range(4):
        d.rectangle((x0 + (0 if i else k), 16 + k, x1 - (k if i == 0 else 0), H - 16 + k), fill=(200, 182, 146, 255))
lp_ = parchment(W // 2 - 26, H - 40, 1)
rp_ = parchment(W // 2 - 26, H - 40, 2)
book.paste(lp_, (22, 14)); book.paste(rp_, (W // 2 + 4, 14))
gut = Image.new('RGBA', (60, H - 40), (0, 0, 0, 0)); gd = gut.load()
for x in range(60):
    a = int(110 * math.exp(-abs(x - 30) / 9))
    for y in range(H - 40): gd[x, y] = (60, 36, 16, a)
book.alpha_composite(gut, (W // 2 - 30, 14))
(TEX / 'gui').mkdir(parents=True, exist_ok=True)
# the open book itself is drawn by compendium_book.py now

# ---- placeholder pictures
sys.path.insert(0, str(ROOT / 'tools'))
import re
src = (ROOT / 'src/main/java/com/ronald/ancientpeaks_lore/LoreBooks.java').read_text()
titles = re.findall(r'new Book\((\d+), "((?:[^"\\]|\\.)*)"', src)
try: font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSerif-Italic.ttf', 15); big = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf', 40)
except Exception: font = big = ImageFont.load_default()
def roman(n):
    out = ''
    for v, s in ((10, 'X'), (9, 'IX'), (5, 'V'), (4, 'IV'), (1, 'I')) * 1:
        pass
    vals = [(1000, 'M'), (900, 'CM'), (500, 'D'), (400, 'CD'), (100, 'C'), (90, 'XC'), (50, 'L'), (40, 'XL'), (10, 'X'), (9, 'IX'), (5, 'V'), (4, 'IV'), (1, 'I')]
    for v, s in vals:
        while n >= v: out += s; n -= v
    return out
for n, title in titles:
    n = int(n)
    im = parchment(256, 192, 100 + n, base=(226, 206, 166))
    d = ImageDraw.Draw(im)
    d.rectangle((8, 8, 247, 183), outline=(92, 66, 40), width=2)
    d.rectangle((13, 13, 242, 178), outline=(140, 106, 66), width=1)
    rn = roman(n)
    tw = d.textlength(rn, font=big); d.text(((256 - tw) / 2, 52), rn, fill=(120, 40, 24), font=big)
    t = title.encode().decode('unicode_escape') if '\\' in title else title
    tw = d.textlength(t, font=font)
    if tw > 220: t = t[:28] + '...'; tw = d.textlength(t, font=font)
    d.text(((256 - tw) / 2, 112), t, fill=(70, 50, 30), font=font)
    s2 = 'a picture to come'; tw = d.textlength(s2, font=font); d.text(((256 - tw) / 2, 140), s2, fill=(150, 125, 90), font=font)
    out = TEX / f'gui/compendium/book_{n}.png'
    if not out.exists(): im.save(out)   # never over a real picture

# ---- the item: a small leather book with a gilt clasp
ic = Image.new('RGBA', (16, 16), (0, 0, 0, 0)); p = ic.load()
for y in range(2, 15):
    for x in range(3, 13):
        edge = x in (3, 12) or y in (2, 14)
        p[x, y] = (54, 30, 16, 255) if edge else (98 - (y % 3) * 4, 56, 30, 255)
for y in range(3, 14): p[12, y] = (224, 210, 170, 255)          # page edges
for y in range(3, 14): p[4, y] = (72, 40, 22, 255)              # spine
for (x, y) in ((7, 6), (8, 6), (7, 7), (8, 7), (6, 8), (9, 8), (7, 9), (8, 9)): p[x, y] = (214, 172, 60, 255)   # gilt emblem
for y in (7, 8, 9): p[12, y] = (190, 150, 50, 255)            # clasp
(TEX / 'item').mkdir(parents=True, exist_ok=True)
ic.save(TEX / 'item/compendium.png')
print('compendium art:', len(titles), 'placeholders')
