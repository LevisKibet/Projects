"""Draws Chroma's textures and writes its models, blockstates, recipes and loot tables."""
import json, math, os, random
from pathlib import Path
from PIL import Image, ImageDraw

R = Path(__file__).resolve().parent.parent / 'src/main/resources'
A = R / 'assets/chroma'; D = R / 'data/chroma'
def save(img, rel): p = A / 'textures' / rel; p.parent.mkdir(parents=True, exist_ok=True); img.save(p)
def js(path, obj): path.parent.mkdir(parents=True, exist_ok=True); path.write_text(json.dumps(obj, indent=2))
random.seed(3)

# ---------------------------------------------------------------- textures used along the lines (tinted in code)
im = Image.new('RGBA', (16, 16))
for x in range(16):
    d = abs(x - 7.5) / 7.5
    v = int(255 * (1 - 0.35 * d ** 3))
    for y in range(16):
        bead = 1.0 if (y % 4) in (1, 2) else 0.93       # LEDs show as faint beads along the strip
        im.putpixel((x, y), (int(v * bead), int(v * bead), int(v * bead), 255))
save(im, 'misc/strip.png')
im = Image.new('RGBA', (16, 16))
for x in range(16):
    d = abs(x - 7.5) / 7.5
    a = int(255 * max(0, 1 - d) ** 2.2)
    for y in range(16): im.putpixel((x, y), (255, 255, 255, a))
save(im, 'misc/halo_line.png')
im = Image.new('RGBA', (32, 32))
for x in range(32):
    for y in range(32):
        d = math.hypot(x - 15.5, y - 15.5) / 15.5
        im.putpixel((x, y), (255, 255, 255, int(255 * max(0, 1 - d) ** 2)))
save(im, 'misc/glow.png')
im = Image.new('RGBA', (16, 16))
for x in range(16):
    for y in range(16):
        v = 200 + random.randint(-25, 25) - (40 if (x + y) % 5 == 0 else 0)   # twisted wire
        im.putpixel((x, y), (v, v, v, 255))
save(im, 'misc/wire.png')
im = Image.new('RGBA', (16, 16), (235, 235, 235, 255))
dr = ImageDraw.Draw(im); dr.rectangle((3, 2, 6, 9), fill=(255, 255, 255, 255)); dr.rectangle((10, 4, 13, 14), fill=(215, 215, 215, 255))
save(im, 'misc/bulb.png')

# ---------------------------------------------------------------- the anchor clip
im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
dr = ImageDraw.Draw(im)
dr.rectangle((0, 0, 15, 15), fill=(118, 122, 128, 255))
dr.rectangle((1, 1, 14, 14), fill=(150, 155, 162, 255))
dr.rectangle((5, 5, 10, 10), fill=(232, 238, 244, 255))
dr.point([(2, 2), (13, 2), (2, 13), (13, 13)], fill=(90, 92, 96, 255))
save(im, 'block/line_anchor.png')

# ---------------------------------------------------------------- item icons
def icon_strip():
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.ellipse((1, 1, 14, 14), fill=(60, 64, 70, 255))                     # reel
    d.ellipse((2, 2, 13, 13), fill=(236, 240, 246, 255))                  # wound strip
    d.ellipse((4, 4, 11, 11), fill=(190, 196, 206, 255))
    d.ellipse((5, 5, 10, 10), fill=(250, 252, 255, 255))
    d.ellipse((6, 6, 9, 9), fill=(70, 74, 80, 255))                       # hub
    for i, c in enumerate([(255, 70, 70), (80, 255, 120), (90, 140, 255)]):   # glowing LEDs on the loose end
        d.point((11 + i, 13 - i), fill=c + (255,))
    d.line((10, 14, 15, 9), fill=(236, 240, 246, 255))
    return im
def icon_bulbs():
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    pts = [(0, 5), (3, 7), (6, 8), (9, 8), (12, 7), (15, 5)]
    d.line(pts, fill=(30, 70, 36, 255), width=1)
    cols = [(255, 50, 40), (60, 230, 80), (60, 110, 255), (255, 200, 60), (255, 120, 30)]
    for i, (x, y) in enumerate(pts[:5]):
        c = cols[i % 5]; bx = x + 1
        d.point((bx, y + 1), fill=(38, 80, 44, 255))
        d.rectangle((bx - 1, y + 2, bx + 1, y + 4), fill=c + (255,))
        d.point((bx, y + 5), fill=tuple(int(v * 0.8) for v in c) + (255,))
        d.point((bx - 1, y + 2), fill=(255, 255, 255, 255))
    return im
def icon_shaper():
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.line((2, 14, 7, 9), fill=(30, 170, 170, 255), width=2)            # handles
    d.line((5, 15, 9, 10), fill=(24, 140, 150, 255), width=2)
    d.line((7, 9, 12, 3), fill=(170, 176, 184, 255), width=2)           # jaws
    d.line((9, 10, 14, 5), fill=(140, 146, 154, 255), width=1)
    d.point((8, 9), fill=(230, 235, 240, 255))                         # pivot
    d.point((13, 2), fill=(255, 255, 255, 255))
    return im
save(icon_strip(), 'item/led_strip.png'); save(icon_bulbs(), 'item/string_lights.png'); save(icon_shaper(), 'item/light_shaper.png')

# ---------------------------------------------------------------- models and blockstates
js(A / 'models/block/line_anchor.json', {
    "parent": "block/block", "textures": {"all": "chroma:block/line_anchor", "particle": "chroma:block/line_anchor"},
    "elements": [
        {"from": [5, 0, 5], "to": [11, 1, 11], "faces": {f: {"uv": [0, 0, 16, 16] if f in ('up', 'down') else [0, 0, 16, 2], "texture": "#all"} for f in ('north', 'south', 'east', 'west', 'up', 'down')}},
        {"from": [6.5, 1, 6.5], "to": [9.5, 1.5, 9.5], "faces": {f: {"uv": [5, 5, 11, 11], "texture": "#all"} for f in ('north', 'south', 'east', 'west', 'up')}}]})
rot = {"up": {}, "down": {"x": 180}, "north": {"x": 90}, "south": {"x": 90, "y": 180}, "west": {"x": 90, "y": 270}, "east": {"x": 90, "y": 90}}
js(A / 'blockstates/line_anchor.json', {"variants": {f"facing={f}": dict({"model": "chroma:block/line_anchor"}, **r) for f, r in rot.items()}})
# one glow block per dye: its particle texture is a swatch of its colour, which is how coloured lighting that doesn't
# know Chroma (Chronicles of Logres' copy) still lights it the right colour
DYES = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black']
VIVID = [0xFFF4E6, 0xFF8A20, 0xFF40E0, 0x60C8FF, 0xFFE040, 0x80FF40, 0xFF90C0, 0xA0A0A8, 0xD0D0D8, 0x20E8E0, 0xA040FF, 0x3060FF, 0xC88040, 0x30D050, 0xFF2020, 0x30204A]
SHADES = [(0, ''), (0.35, '_soft'), (0.65, '_pale')]     # each colour full, softened and pale (for blended lines)
lang = json.loads((A / 'lang/en_us.json').read_text())
for d, c in zip(DYES, VIVID):
    for white, suf in SHADES:
        rgb = tuple(round(((c >> sh) & 255) * (1 - white) + 255 * white) for sh in (16, 8, 0))
        n = f'glow_{d}{suf}'
        save(Image.new('RGBA', (16, 16), rgb + (255,)), f'block/{n}.png')
        js(A / f'models/block/{n}.json', {"textures": {"particle": f"chroma:block/{n}"}})
        js(A / f'blockstates/{n}.json', {"variants": {"": {"model": f"chroma:block/{n}"}}})
        lang[f'block.chroma.{n}'] = (suf[1:].title() + ' ' if suf else '') + d.replace('_', ' ').title() + ' Strip Glow'
(A / 'lang/en_us.json').write_text(json.dumps(lang, indent=2))
for n in ('led_strip', 'string_lights', 'light_shaper'):
    js(A / f'models/item/{n}.json', {"parent": "item/handheld" if n == 'light_shaper' else "item/generated", "textures": {"layer0": f"chroma:item/{n}"}})

# ---------------------------------------------------------------- recipes and loot
js(D / 'recipes/led_strip.json', {"type": "minecraft:crafting_shaped", "pattern": ["GGG", "RDR", "PPP"],
    "key": {"G": {"item": "minecraft:glass_pane"}, "R": {"item": "minecraft:redstone"}, "D": {"item": "minecraft:glowstone_dust"}, "P": {"item": "minecraft:paper"}},
    "result": {"item": "chroma:led_strip", "count": 4}})
js(D / 'recipes/string_lights.json', {"type": "minecraft:crafting_shaped", "pattern": ["SSS", "GDG", "SSS"],
    "key": {"S": {"item": "minecraft:string"}, "G": {"item": "minecraft:glass_pane"}, "D": {"item": "minecraft:glowstone_dust"}},
    "result": {"item": "chroma:string_lights", "count": 4}})
js(D / 'recipes/light_shaper.json', {"type": "minecraft:crafting_shaped", "pattern": ["I I", " C ", "D D"],
    "key": {"I": {"item": "minecraft:iron_ingot"}, "C": {"item": "minecraft:copper_ingot"}, "D": {"item": "minecraft:cyan_dye"}},
    "result": {"item": "chroma:light_shaper"}})
print('assets written')
