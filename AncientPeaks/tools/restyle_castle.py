"""Scales a voxel-shell castle and paints it in a medieval palette (white walls, red roofs, grey rock hill).
Hidden/interior blocks stay plain stone so they can be textured by hand."""
import sys, numpy as np, pathlib, collections
HERE = pathlib.Path(__file__).resolve().parent
_src = (HERE.parent / "gen_structures.py").read_text()
exec(_src[:_src.index("# ------------------------------------------------------------------ build")])

src, out, target_h = sys.argv[1], sys.argv[2], int(sys.argv[3])
r = read_nbt(open(src, "rb").read()).v
W, H, LEN = r["Width"].v, r["Height"].v, r["Length"].v
ids = np.frombuffer(bytes(r["Blocks"].v), np.uint8).reshape(H, LEN, W)          # y, z, x
occ_src = ids > 0
s = target_h / H
ys, zs, xs = np.nonzero(occ_src)
ty, tz, tx = (ys * s).astype(int), (zs * s).astype(int), (xs * s).astype(int)
dims = (int(W * s) + 1, int(H * s) + 1, int(LEN * s) + 1)                      # x, y, z
occ = np.zeros(dims, bool); occ[tx, ty, tz] = True
X, Y, Z = occ.shape

def hsh(*a):                                       # stable per-block noise in [0,1)
    v = np.uint64(0x9E3779B97F4A7C15)
    for i, k in enumerate(a): v = (v ^ (np.asarray(k, np.uint64) * np.uint64(0x100000001B3 + 2 * i))) * np.uint64(0xBF58476D1CE4E5B9)
    return ((v >> np.uint64(11)) % np.uint64(10000)).astype(np.float64) / 10000.0

def blobs(x, z, scale, seed):                      # soft low-frequency patches
    g = lambda a, b: hsh(a, b, seed)
    fx, fz = x / scale, z / scale; ix, iz = np.floor(fx).astype(np.int64), np.floor(fz).astype(np.int64); ux, uz = fx - ix, fz - iz
    a, b, c, d = g(ix, iz), g(ix + 1, iz), g(ix, iz + 1), g(ix + 1, iz + 1)
    sx, sz = ux * ux * (3 - 2 * ux), uz * uz * (3 - 2 * uz)
    return (a * (1 - sx) + b * sx) * (1 - sz) + (c * (1 - sx) + d * sx) * sz

def pad(a): return np.pad(a, 1)
P = pad(occ)
air = lambda dx, dy, dz: ~P[1 + dx:1 + dx + X, 1 + dy:1 + dy + Y, 1 + dz:1 + dz + Z]
up, down = air(0, 1, 0), air(0, -1, 0)
ex, wx, sz_, nz = air(1, 0, 0), air(-1, 0, 0), air(0, 0, 1), air(0, 0, -1)

# visible from outside along the 4 horizontal axes and from the sky
vis = np.zeros_like(occ)
for axis in (0, 2):
    c = np.cumsum(occ, axis=axis); first = occ & (c == 1); vis |= first
    cr = np.flip(np.cumsum(np.flip(occ, axis), axis=axis), axis); vis |= occ & (cr == 1)
cy = np.flip(np.cumsum(np.flip(occ, 1), axis=1), 1); vis |= occ & (cy == 1)
vis |= occ & (up | ex | wx | sz_ | nz) & ~(np.cumsum(occ, 1) == 0)   # walls behind windows/courtyards still count as outside faces
vis &= occ & (up | down | ex | wx | sz_ | nz)

# ground level: the lowest layer with a real footprint (no hill in this model - it is all castle)
foot = occ.sum(axis=(0, 2))
hill_top = int(np.argmax(foot > foot.max() * 0.05))
# roofs: where the castle's top surface slopes like a pitched roof or a spire (not flat, not a sheer wall, not crenels)
any_col = occ.any(axis=1)
T = np.where(any_col, Y - 1 - np.argmax(occ[:, ::-1, :], axis=1), -1).astype(float)
Tp = np.pad(T, 1, mode="edge")
gx = (Tp[2:, 1:-1] - Tp[:-2, 1:-1]) / 2; gz = (Tp[1:-1, 2:] - Tp[1:-1, :-2]) / 2
grad = np.hypot(gx, gz)
from scipy.ndimage import uniform_filter
grad_s = uniform_filter(grad, 3)
roofcol = any_col & (T > hill_top + 12) & (grad_s > 0.45) & (grad_s < 2.6)
# fill small gaps so whole roof faces read as one surface
roofcol = uniform_filter(roofcol.astype(float), 3) > 0.45
roofcol &= any_col & (T > hill_top + 12)
yy = np.arange(Y)[None, :, None]
roof = occ & roofcol[:, None, :] & (yy >= T[:, None, :] - 2)
xi, yi, zi = np.nonzero(occ)
h = hsh(xi, yi, zi)
name = np.full(len(xi), "stone", dtype=object)                       # hidden/interior: plain stone, blank for hand texturing
V = vis[xi, yi, zi]; U = up[xi, yi, zi]; D = down[xi, yi, zi]; R = roof[xi, yi, zi]
side_x = ex[xi, yi, zi] | wx[xi, yi, zi]; side_z = sz_[xi, yi, zi] | nz[xi, yi, zi]
corner = side_x & side_z
hill = yi < hill_top
green = blobs(xi, zi, 9.0, 7)

# hill: stepped grey rock, mossy and grassy where it faces the sky
rock = np.where(h < .32, "stone", np.where(h < .55, "andesite", np.where(h < .75, "cobblestone", np.where(h < .88, "tuff", "polished_andesite"))))
top = np.where(green > .55, np.where(h < .6, "moss_block", "grass_block"), np.where(green > .42, np.where(h < .5, "coarse_dirt", "mossy_cobblestone"), rock))
name = np.where(V & hill, np.where(U, top, rock), name)
# castle walls: white stone with grey quoins, banding and a cobble plinth
cast = V & ~hill
wall = np.where(h < .42, "calcite", np.where(h < .72, "polished_diorite", np.where(h < .88, "diorite", "smooth_quartz")))
band = ((yi - hill_top) % 14 == 0) & (yi > hill_top + 6)
plinth = yi < hill_top + 5
wall = np.where(corner | band, np.where(h < .7, "stone_bricks", "polished_andesite"), wall)
wall = np.where(plinth, np.where(h < .45, "cobblestone", np.where(h < .8, "stone_bricks", "mossy_stone_bricks")), wall)
name = np.where(cast, wall, name)
# flat tops (wall walks, crenellations, ledges) and undersides: grey stone brick
flat = cast & U & ~R
name = np.where(flat, np.where(h < .65, "stone_bricks", np.where(h < .85, "polished_andesite", "cracked_stone_bricks")), name)
name = np.where(cast & D & ~U & ~side_x & ~side_z, "stone_bricks", name)
# roofs: red tile
name = np.where(cast & R, np.where(h < .5, "bricks", np.where(h < .8, "polished_granite", "granite")), name)
# a little ivy on the lower walls, like the screenshots
ivy = cast & ~U & (yi < hill_top + 25) & (blobs(xi + yi * 0.3, zi, 6.0, 11) > .72) & (h < .55)
name = np.where(ivy, "mossy_stone_bricks", name)

blocks = [(int(a), int(b), int(c), "minecraft:" + n, {}, None) for a, b, c, n in zip(xi, yi, zi, name)]
open(out, "wb").write(template((X, Y, Z), blocks))
hidden = int((~V).sum()); nroof = int((cast & R).sum())
print("hidden (blank) blocks:", hidden, " roof blocks:", nroof, " castle wall blocks:", int((cast & ~R).sum()))
print(f"{X}x{Y}x{Z} (w x h x d), hill up to y={hill_top}, {len(blocks)} blocks:",
      ", ".join(f"{k} {v}" for k, v in collections.Counter(name.tolist()).most_common(14)))
