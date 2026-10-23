"""Turns an intact structure .nbt into a battle-scarred ruin, long overgrown.

    python3 ruin_castle.py in.nbt out.nbt [--craters 30] [--seed 7]

- blast craters through walls, towers and roofs (noisy, irregular), rubble heaped below them
- anything left floating after the blasts falls away
- moss and cracks spread around the damage, more moss low down
- persistent leaf clumps, moss carpet on ledges and rubble, vines hanging down the walls (attached properly)
"""
import argparse, pathlib, collections, numpy as np
from scipy import ndimage
HERE = pathlib.Path(__file__).resolve().parent
_src = (HERE.parent / "gen_structures.py").read_text()
exec(_src[:_src.index("# ------------------------------------------------------------------ build")])

ap = argparse.ArgumentParser(); ap.add_argument("src"); ap.add_argument("out")
ap.add_argument("--craters", type=int, default=30); ap.add_argument("--seed", type=int, default=7)
a = ap.parse_args()
rng = np.random.default_rng(a.seed)

root = read_nbt(open(a.src, "rb").read()).v
X, Y, Z = [t.v for t in root["size"].v[1]]
pal = [p.v["Name"].v for p in root["palette"].v[1]]
names = ["air"] + [n.split(":")[1] for n in pal]
code = {n: i for i, n in enumerate(names)}
def cid(n):
    if n not in code: code[n] = len(names); names.append(n)
    return code[n]
G = np.zeros((X, Y, Z), np.int32)
for b in root["blocks"].v[1]:
    x, y, z = [t.v for t in b.v["pos"].v[1]]; G[x, y, z] = b.v["state"].v + 1
solid = lambda: G > 0
props = {}                                         # (x,y,z) -> properties for vines / leaves

# ------------------------------------------------------------------ blast craters
SOL = solid()
outside = np.zeros_like(SOL)
for ax in (0, 2):                                   # surface blocks seen from the 4 sides
    outside |= SOL & (np.cumsum(SOL, ax) == 1); outside |= SOL & (np.flip(np.cumsum(np.flip(SOL, ax), ax), ax) == 1)
cand = np.argwhere(outside & (np.arange(Y)[None, :, None] > 6))
wts = cand[:, 1].astype(float) ** 0.8; wts /= wts.sum()
removed = np.zeros_like(SOL)
gx, gy, gz = np.ogrid[:X, :Y, :Z]
crater_list = []
for _ in range(a.craters):
    cx, cy, cz = cand[rng.choice(len(cand), p=wts)]
    r = rng.uniform(4, 10) * (1.3 if cy > Y * 0.5 else 1.0)
    sx, sy, sz = rng.uniform(0.75, 1.3, 3)
    x0, x1 = max(0, int(cx - r * 1.5)), min(X, int(cx + r * 1.5) + 1)
    y0, y1 = max(0, int(cy - r * 1.5)), min(Y, int(cy + r * 1.5) + 1)
    z0, z1 = max(0, int(cz - r * 1.5)), min(Z, int(cz + r * 1.5) + 1)
    lx, ly, lz = np.ogrid[x0:x1, y0:y1, z0:z1]
    d = np.sqrt(((lx - cx) / sx) ** 2 + ((ly - cy) / sy) ** 2 + ((lz - cz) / sz) ** 2)
    noise = rng.uniform(-0.25, 0.25, d.shape) * r
    hole = d + noise < r
    removed[x0:x1, y0:y1, z0:z1] |= hole & SOL[x0:x1, y0:y1, z0:z1]
    crater_list.append((cx, cy, cz, r))
# roofs and spire tips: a few collapse inward
top = np.where(SOL.any(1), Y - 1 - np.argmax(SOL[:, ::-1, :], 1), -1)
for _ in range(a.craters // 4):
    cx, cz = rng.integers(0, X), rng.integers(0, Z)
    if top[cx, cz] < Y * 0.35: continue
    r = rng.uniform(3, 7); cy = top[cx, cz]
    m = ((gx - cx) ** 2 + (gz - cz) ** 2 < r * r) & (gy > cy - r * 1.6)
    removed |= m & SOL
    crater_list.append((cx, cy, cz, r))
lost = removed.sum()
G[removed] = 0

# anything no longer connected to the ground falls away (and adds to the rubble)
SOL = solid()
lab, n = ndimage.label(SOL)
grounded = set(np.unique(lab[:, :3, :])) - {0}
floating = SOL & ~np.isin(lab, list(grounded))
lost += floating.sum(); G[floating] = 0

# ------------------------------------------------------------------ rubble heaps under the craters
SOL = solid()
height = np.where(SOL.any(1), Y - 1 - np.argmax(SOL[:, ::-1, :], 1), 0)
RUBBLE = ["cobblestone"] * 4 + ["mossy_cobblestone"] * 3 + ["stone_bricks", "cracked_stone_bricks", "andesite", "tuff", "calcite"]
budget = int(lost * 0.18)
for cx, cy, cz, r in crater_list:
    if budget <= 0: break
    pr = r * rng.uniform(0.9, 1.5); ph = max(2, r * rng.uniform(0.35, 0.6))
    px, pz = int(np.clip(cx + rng.normal(0, r * .3), 0, X - 1)), int(np.clip(cz + rng.normal(0, r * .3), 0, Z - 1))
    for x in range(max(0, int(px - pr)), min(X, int(px + pr) + 1)):
        for z in range(max(0, int(pz - pr)), min(Z, int(pz + pr) + 1)):
            dd = np.hypot(x - px, z - pz) / pr
            if dd >= 1: continue
            h = int(ph * (1 - dd) ** 1.3 + rng.uniform(0, 1.2))
            # find the floor under this column near the crater height (inside courtyards too)
            y = min(int(cy), Y - 1)
            while y > 0 and G[x, y, z] == 0: y -= 1
            for k in range(1, h + 1):
                if y + k >= Y or G[x, y + k, z] != 0: break
                G[x, y + k, z] = cid(RUBBLE[rng.integers(len(RUBBLE))]); budget -= 1

# ------------------------------------------------------------------ weathering: cracks and moss, strongest near the damage
SOL = solid()
dmg = ndimage.distance_transform_edt(~removed)                       # distance to the nearest blasted block
xi, yi, zi = np.nonzero(SOL)
near = np.clip(1 - dmg[xi, yi, zi] / 9.0, 0, 1)
low = np.clip(1 - yi / (Y * 0.45), 0, 1)
u = rng.random(len(xi))
SWAP_MOSS = {"stone_bricks": "mossy_stone_bricks", "cobblestone": "mossy_cobblestone", "cracked_stone_bricks": "mossy_stone_bricks",
             "calcite": "mossy_cobblestone", "polished_diorite": "mossy_stone_bricks", "diorite": "mossy_cobblestone",
             "smooth_quartz": "mossy_stone_bricks", "polished_andesite": "mossy_cobblestone", "andesite": "mossy_cobblestone"}   # plain "stone" = blank interior, left alone
SWAP_CRACK = {"stone_bricks": "cracked_stone_bricks", "calcite": "cobblestone", "polished_diorite": "andesite", "diorite": "cobblestone",
              "smooth_quartz": "cracked_stone_bricks", "polished_andesite": "cobblestone", "bricks": "granite", "polished_granite": "granite"}
cur = np.array(names, dtype=object)[G[xi, yi, zi]]
p_moss = 0.05 + 0.35 * near + 0.25 * low
p_crack = 0.04 + 0.45 * near
for i in np.nonzero(u < p_moss)[0]:
    s = SWAP_MOSS.get(cur[i]);  G[xi[i], yi[i], zi[i]] = cid(s) if s else G[xi[i], yi[i], zi[i]]
u2 = rng.random(len(xi))
for i in np.nonzero((u2 < p_crack) & (u >= p_moss))[0]:
    s = SWAP_CRACK.get(cur[i]); G[xi[i], yi[i], zi[i]] = cid(s) if s else G[xi[i], yi[i], zi[i]]

# ------------------------------------------------------------------ overgrowth
SOL = solid()
airm = ~SOL
above_solid = airm & np.pad(SOL, ((0, 0), (1, 0), (0, 0)))[:, :-1, :]  # air cell with a solid block below
sky = np.flip(np.cumsum(np.flip(SOL, 1), 1), 1) == 0                       # nothing overhead
above_solid &= sky | (dmg < 5)                                              # rooms stay blank, except right by a breach
# moss carpet on ledges, wall walks and rubble
ax_, ay_, az_ = np.nonzero(above_solid)
nearA = np.clip(1 - dmg[ax_, ay_, az_] / 10.0, 0, 1)
pick = rng.random(len(ax_)) < 0.08 + 0.35 * nearA
for x, y, z in zip(ax_[pick], ay_[pick], az_[pick]): G[x, y, z] = cid("moss_carpet")
# leaf clumps: where the castle meets the ground, on ledges and spilling from the breaches
LEAVES = ["oak_leaves", "dark_oak_leaves", "azalea_leaves", "flowering_azalea_leaves", "spruce_leaves"]
spots = np.argwhere(above_solid & ((np.arange(Y)[None, :, None] < Y * 0.25) | (dmg < 6)))
for k in rng.choice(len(spots), size=min(len(spots), 260), replace=False):
    cx, cy, cz = spots[k]; r = rng.uniform(1.2, 2.8); leaf = LEAVES[rng.integers(len(LEAVES))]
    for x in range(max(0, int(cx - r)), min(X, int(cx + r) + 1)):
        for y in range(max(0, int(cy - 1)), min(Y, int(cy + r) + 1)):
            for z in range(max(0, int(cz - r)), min(Z, int(cz + r) + 1)):
                if G[x, y, z] in (0, code.get("moss_carpet", -1)) and (x - cx) ** 2 + ((y - cy) * 1.4) ** 2 + (z - cz) ** 2 < r * r and rng.random() < 0.85:
                    G[x, y, z] = cid(leaf); props[(x, y, z)] = {"persistent": "true", "distance": "1", "waterlogged": "false"}
# vines: hang from the tops of exterior walls, attached to the wall face behind them
SOL = solid()
DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
wallfaces = []
for face, (dx, dy, dz) in DIRS.items():
    # air cell whose neighbour in direction `face` is a solid wall block
    sh = np.zeros_like(SOL)
    sx_ = slice(max(0, dx), X + min(0, dx)); sz_ = slice(max(0, dz), Z + min(0, dz))
    tx_ = slice(max(0, -dx), X + min(0, -dx)); tz_ = slice(max(0, -dz), Z + min(0, -dz))
    sh[tx_, :, tz_] = SOL[sx_, :, sz_]
    cells = np.argwhere(~SOL & sh & (np.arange(Y)[None, :, None] > 3))
    wallfaces.append((face, cells))
nvines = 0
for face, cells in wallfaces:
    if not len(cells): continue
    dx, _, dz = DIRS[face]
    for k in rng.choice(len(cells), size=min(len(cells), 220), replace=False):
        x, y, z = cells[k]
        if dmg[x, y, z] > 14 and rng.random() < 0.6: continue               # favour the scarred parts
        length = int(rng.integers(3, 18))
        for j in range(length):
            yy = y - j
            if yy < 0 or G[x, yy, z] != 0: break
            wx, wz = x + dx, z + dz
            if not (0 <= wx < X and 0 <= wz < Z) or G[wx, yy, wz] == 0: break      # wall behind has ended
            G[x, yy, z] = cid("vine")
            props[(x, yy, z)] = {f: ("true" if f == face else "false") for f in DIRS} | {"up": "false"}
            nvines += 1

# ------------------------------------------------------------------ write
out = []
for x, y, z in np.argwhere(G > 0):
    n = names[G[x, y, z]]
    out.append((int(x), int(y), int(z), "minecraft:" + n, props.get((x, y, z), {}), None))
open(a.out, "wb").write(template((X, Y, Z), out))
c = collections.Counter(b[3].split(":")[1] for b in out)
print(f"{X}x{Y}x{Z}: {len(out)} blocks, {lost} blasted away, {nvines} vine blocks")
print(", ".join(f"{k} {v}" for k, v in c.most_common(22)))
