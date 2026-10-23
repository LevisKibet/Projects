"""Draws Excalibur's item sprite (32x32): a long, slim blade corner to corner, a narrow crossguard, wrapped grip and a
small pommel; plus its glow layers (white outline, blue core).     python3 tools/excalibur_sprite.py"""
import pathlib
from PIL import Image
OUT = pathlib.Path(__file__).resolve().parent.parent / 'src/main/resources/assets/ancientpeaks_lore/textures/item'
N = 32
OUTLINE = (24, 22, 26, 255)
EDGE_HI, STEEL_HI, FULLER, STEEL_LO, STEEL_DK = (226, 234, 238, 255), (185, 193, 200, 255), (146, 154, 159, 255), (111, 121, 128, 255), (82, 88, 94, 255)
GUARD_HI, GUARD, GUARD_DK = (200, 208, 216, 255), (135, 145, 157, 255), (77, 83, 93, 255)
WRAP, WRAP_DK = (184, 115, 61, 255), (120, 72, 38, 255)
C = 31                       # the blade's centre line: x + y = C
TIP_A, GUARD_A = 29, -15     # along the blade: a = x - y (tip at (30,1), guard round (7,24))
px = {}; part = {}
def at(a, d):
    """a along the blade, d across it (d = x + y)."""
    if (a + d) % 2: return None
    x, y = (a + d) // 2, (d - a) // 2
    return (x, y) if 0 <= x < N and 0 <= y < N else None
# blade: 4 pixels across (2 either side of the fuller), narrowing to a point
for a in range(GUARD_A + 2, TIP_A + 1):
    for d in range(C - 3, C + 4):
        p = at(a, d)
        if not p: continue
        k = d - C
        span = 1 if a < TIP_A - 2 else 0
        if abs(k) > span: continue
        col = {-1: EDGE_HI, 0: FULLER, 1: STEEL_LO}[k]
        px[p] = col; part[p] = 'core' if k == 0 and a < TIP_A - 1 else 'blade'
# crossguard: a slim bar across the blade
for a in (GUARD_A, GUARD_A + 1):
    for d in range(C - 6, C + 7):
        p = at(a, d)
        if not p: continue
        k = abs(d - C)
        px[p] = GUARD_HI if (d - C) < -1 else GUARD_DK if k >= 5 else GUARD
        part[p] = 'guard'
# grip: wrapped leather
for a in range(GUARD_A - 7, GUARD_A):
    for d in range(C, C + 2):
        p = at(a, d)
        if not p: continue
        px[p] = WRAP if (a // 2) % 2 else WRAP_DK; part[p] = 'grip'
# pommel
for a in range(GUARD_A - 11, GUARD_A - 7):
    for d in range(C - 2, C + 3):
        p = at(a, d)
        if not p or abs(d - C) + abs(a - (GUARD_A - 9)) > 2: continue
        px[p] = GUARD_HI if d < C else GUARD; part[p] = 'pommel'
# outline round everything (4-neighbours)
img = Image.new('RGBA', (N, N), (0, 0, 0, 0)); glow = Image.new('RGBA', (N, N), (0, 0, 0, 0)); core = Image.new('RGBA', (N, N), (0, 0, 0, 0))
for p, c in px.items(): img.putpixel(p, c)
outline = set()
for (x, y) in px:
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        q = (x + dx, y + dy)
        if 0 <= q[0] < N and 0 <= q[1] < N and q not in px: outline.add(q)
for q in outline:
    img.putpixel(q, OUTLINE)
    near = [part.get((q[0] + dx, q[1] + dy)) for dx in (-1, 0, 1) for dy in (-1, 0, 1)]
    if 'blade' in near or 'core' in near or 'guard' in near: glow.putpixel(q, (242, 250, 255, 255))
cores = sorted([p for p in px if part[p] == 'core'], key=lambda p: p[1])
for i, p in enumerate(cores):
    t = i / max(1, len(cores) - 1)
    core.putpixel(p, (int(70 + 80 * (1 - t)), int(165 + 60 * (1 - t)), 255, 255))
img.save(OUT / 'excalibur.png'); glow.save(OUT / 'excalibur_glow.png'); core.save(OUT / 'excalibur_core.png')
prev = Image.new('RGBA', (N, N), (25, 25, 35, 255)); prev.alpha_composite(img)
prev.resize((N * 12, N * 12), Image.NEAREST).save('/tmp/claude-0/exc_new.png')
p2 = prev.copy(); p2.alpha_composite(glow); p2.alpha_composite(core); p2.resize((N * 12, N * 12), Image.NEAREST).save('/tmp/claude-0/exc_new_glow.png')
print('pixels', len(px), 'outline', len(outline), 'core', len(cores))
