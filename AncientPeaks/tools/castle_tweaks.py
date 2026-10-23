"""Pre-ruin edits on the intact castle: strip the roof flags, add guard walkways along the curtain walls,
and find the corner-tower guard rooms (written to a JSON file so chests can go in after the ruin pass).

    python3 castle_tweaks.py grand_castle.nbt grand_castle_walks.nbt chests.json
"""
import sys, json, pathlib, collections, numpy as np
from scipy import ndimage
HERE = pathlib.Path(__file__).resolve().parent
_src = (HERE.parent / "gen_structures.py").read_text()
exec(_src[:_src.index("# ------------------------------------------------------------------ build")])
src, out, chest_json = sys.argv[1:4]
rng = np.random.default_rng(11)

root = read_nbt(open(src, "rb").read()).v
X, Y, Z = [t.v for t in root["size"].v[1]]
names = ["air"] + [p.v["Name"].v.split(":")[1] for p in root["palette"].v[1]]
code = {n: i for i, n in enumerate(names)}
def cid(n):
    if n not in code: code[n] = len(names); names.append(n)
    return code[n]
G = np.zeros((X, Y, Z), np.int32)
for b in root["blocks"].v[1]:
    x, y, z = [t.v for t in b.v["pos"].v[1]]; G[x, y, z] = b.v["state"].v + 1
props = {}
O = G > 0
T = np.where(O.any(1), Y - 1 - np.argmax(O[:, ::-1, :], 1), -1)

# ------------------------------------------------------------------ 1. flags: thin poles (with their little flags) standing on roof peaks
P = np.pad(O, ((1, 1), (0, 0), (1, 1)))
iso = O & ~P[2:, :, 1:-1] & ~P[:-2, :, 1:-1] & ~P[1:-1, :, 2:] & ~P[1:-1, :, :-2]
flags = 0
for x, z in zip(*np.nonzero(iso.any(1))):
    col = iso[x, :, z]; y = 0
    while y < Y:
        if not col[y]: y += 1; continue
        y0 = y
        while y < Y and col[y]: y += 1
        if y - y0 >= 2 and y - 1 >= T[x, z] - 5 and y0 >= 20:
            box = (slice(max(0, x - 5), x + 6), slice(y0, Y), slice(max(0, z - 5), z + 6))
            if O[box].sum() < 60:                    # just a pole and a scrap of cloth up there
                G[box] = 0; flags += 1
O = G > 0
T = np.where(O.any(1), Y - 1 - np.argmax(O[:, ::-1, :], 1), -1)

# ------------------------------------------------------------------ 2. guard walkways along the curtain walls
cx0, cz0 = np.argwhere(T >= 0).mean(0)
def run(x, z, dx, dz, h):
    n = 0
    while 0 <= x + dx * (n + 1) < X and 0 <= z + dz * (n + 1) < Z and abs(T[x + dx * (n + 1), z + dz * (n + 1)] - h) <= 1: n += 1
    return n
walk = {}
WALK = ["spruce_planks"] * 6 + ["dark_oak_planks"] * 2 + ["stripped_spruce_log"]
nwall = 0
for x in range(X):
    for z in range(Z):
        h = int(T[x, z])
        if not 12 <= h <= 45: continue
        rx = run(x, z, 1, 0, h) + run(x, z, -1, 0, h) + 1
        rz = run(x, z, 0, 1, h) + run(x, z, 0, -1, h) + 1
        if min(rx, rz) > 6 or max(rx, rz) < 10: continue           # not a long thin wall top
        nwall += 1
        ax, az = (0, 1) if rx >= rz else (1, 0)                    # across-wall direction
        along = x if (ax, az) == (0, 1) else z
        if rng.random() < 0.0 or (hash((along // 4, h)) % 100) < 18: continue   # broken gaps in the boardwalk
        # which side faces the castle's inner ward?
        inner = 1 if (cx0 - x) * ax + (cz0 - z) * az > 0 else -1
        e_in = run(x, z, ax * inner, az * inner, h)
        e_out = run(x, z, -ax * inner, -az * inner, h)
        # the walk: two boards wide, flush with the wall top, hanging over the ward side
        for k in (1, 2):
            px, pz = x + ax * inner * (e_in + k), z + az * inner * (e_in + k)
            if not (0 <= px < X and 0 <= pz < Z) or G[px, h, pz] != 0 or T[px, pz] > h - 3: break
            walk[(px, h, pz)] = WALK[rng.integers(len(WALK))] if rng.random() > 0.08 else None
            if k == 2 and rng.random() < 0.75: walk[(px, h + 1, pz)] = "spruce_fence"            # railing
            if k == 1 and along % 3 == 0: walk[(px, h - 1, pz)] = "spruce_fence"                 # brackets under the boards
        # parapet on the outer edge: alternating merlons
        ox, oz = x - ax * inner * e_out, z - az * inner * e_out
        if (along // 2) % 2 == 0 and 0 <= ox < X and 0 <= oz < Z and G[ox, h + 1, oz] == 0 and rng.random() < 0.85:
            walk[(ox, h + 1, oz)] = "stone_bricks" if rng.random() < 0.7 else "cracked_stone_bricks"
for (x, y, z), n in walk.items():
    if n and G[x, y, z] == 0: G[x, y, z] = cid(n)

# ------------------------------------------------------------------ 3. corner towers: their top guard rooms
O = G > 0
T = np.where(O.any(1), Y - 1 - np.argmax(O[:, ::-1, :], 1), -1)
high = T >= 26
lab, n = ndimage.label(high)
towers = []
for i in range(1, n + 1):
    cells = np.argwhere(lab == i)
    if not 6 <= len(cells) <= 600: continue
    (x0, z0), (x1, z1) = cells.min(0), cells.max(0)
    if max(x1 - x0, z1 - z0) > 30: continue
    towers.append(cells.mean(0))
corners = [(0, 0), (X - 1, 0), (0, Z - 1), (X - 1, Z - 1)]
chests = []
for cx, cz in corners:
    if not towers: break
    tx, tz = min(towers, key=lambda t: np.hypot(t[0] - cx, t[1] - cz))
    best = None
    for dx in range(-3, 4):
        for dz in range(-3, 4):
            x, z = int(round(tx)) + dx, int(round(tz)) + dz
            if not (0 <= x < X and 0 <= z < Z): continue
            for y in range(int(T[x, z]) - 1, 5, -1):            # highest enclosed floor: air with solid below and a roof above
                if G[x, y, z] == 0 and G[x, y - 1, z] != 0 and O[x, y + 1:, z].any():
                    if best is None or y > best[1]: best = (x, y, z)
                    break
    if best:
        face = "south" if cz == 0 else "north"
        chests.append({"pos": [int(v) for v in best], "facing": face})

blocks = [(int(x), int(y), int(z), "minecraft:" + names[G[x, y, z]], props.get((x, y, z), {}), None) for x, y, z in np.argwhere(G > 0)]
open(out, "wb").write(template((X, Y, Z), blocks))
json.dump(chests, open(chest_json, "w"))
print(f"flags removed: {flags}, wall-top columns: {nwall}, walkway blocks: {sum(1 for v in walk.values() if v)}, corner guard rooms: {len(chests)} {chests}")
