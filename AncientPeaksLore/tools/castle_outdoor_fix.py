"""Takes the furniture that ended up outdoors off the castle (castle/grand_castle_ruined.nbt): bookshelves (with any
candle on them) and armour stands that stand anywhere but in a room - a room being air that can't be reached from the
sky or the template's edges once every wall is a block thicker (doorways and windows closed up).     python3 tools/castle_outdoor_fix.py"""
import sys, pathlib, numpy as np
sys.path.insert(0, '/home/claude/hl/tools')
from nbtedit import Template
ROOT = pathlib.Path(__file__).resolve().parent.parent
P = ROOT / 'src/main/resources/data/ancientpeaks_lore/structures/castle/grand_castle_ruined.nbt'
BASE = ROOT / 'tools/base/grand_castle_ruined.nbt'
t = Template(str(BASE)); sol = t.solid_mask()
X, Y, Z = t.X, t.Y, t.Z
# cells that count as wall/roof: full blocks, but not the furniture itself
furn = np.array([n in ('bookshelf', 'barrel', 'chest') for n in t.names] + [False])[t.G]
wall = sol & ~furn
# Outdoors = the open air reachable from the sky or the template's edges, with every wall thickened by a block so a
# doorway or window doesn't let the outside in. Furniture stands outdoors if open outdoor air is right beside it.
from scipy import ndimage
thick = ndimage.binary_dilation(wall, structure=np.array([[[0, 1, 0], [0, 1, 0], [0, 1, 0]], [[1, 1, 1], [1, 1, 1], [1, 1, 1]], [[0, 1, 0], [0, 1, 0], [0, 1, 0]]], bool).transpose(0, 1, 2) if False else
                                np.ones((3, 1, 3), bool))
lab, n = ndimage.label(~thick)
edge = set(np.unique(np.concatenate([lab[0].ravel(), lab[-1].ravel(), lab[:, -1].ravel(), lab[:, :, 0].ravel(), lab[:, :, -1].ravel()])))
edge.discard(0)
outdoor = np.isin(lab, list(edge))
def inside(x, y, z):
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            xx, zz = x + dx, z + dz
            if 0 <= xx < X and 0 <= zz < Z and (outdoor[xx, y, zz] or (y + 1 < Y and outdoor[xx, y + 1, zz])): return False
    return True
AIR = t.state('air')
def clear(x, y, z):
    i = t.index.get((x, y, z))
    if i is None: return
    e = t.blocks[i].v; e['state'].v = AIR; e.pop('nbt', None); t.G[x, y, z] = AIR
removed = 0
for (x, y, z) in list(t.index):
    if t.name(x, y, z) != 'bookshelf': continue
    if t.name(x, y - 1, z) == 'bookshelf': continue                    # judge a stack by its foot
    if inside(x, y, z): continue
    yy = y
    while t.name(x, yy, z) == 'bookshelf': clear(x, yy, z); yy += 1; removed += 1
    if 'candle' in (t.name(x, yy, z) or ''): clear(x, yy, z)
ents = t.root['entities'].v[1]; keep = []; stands = 0
for e in ents:
    if e.v['nbt'].v['id'].v == 'minecraft:armor_stand':
        x, y, z = [v.v for v in e.v['blockPos'].v[1]]
        if not inside(x, y, z): stands += 1; continue
    keep.append(e)
t.root['entities'].v = (t.root['entities'].v[0], keep)
print('removed', removed, 'bookshelves and', stands, 'armour stands from outdoors; kept', len(keep), 'stands')
t.save(str(P))
