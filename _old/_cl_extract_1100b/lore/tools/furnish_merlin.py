"""Furnish Merlin's cabin (churches/church_merlin.nbt) from tools/base/church_merlin.nbt:
ground floor: a scholar's hall - bookshelves along the walls, barrels, chests, workstations, candles;
upper floor: his hiding place - a lectern (his journal: LootProcessor puts it there), a chest, shelves, candles.
Prints the spots LorePiece uses for Merlin (upstairs, hidden) and the raiders (ground floor).

    python3 tools/furnish_merlin.py
"""
import sys, pathlib, random, collections
sys.path.insert(0, '/home/claude/hl/tools')
from nbtedit import Template
ROOT = pathlib.Path(__file__).resolve().parent.parent
t = Template(str(ROOT / 'tools/base/church_merlin.nbt'))
sol = t.solid_mask()
R = random.Random(1307)
DIRS = {'north': (0, -1), 'south': (0, 1), 'west': (-1, 0), 'east': (1, 0)}
OPP = {'north': 'south', 'south': 'north', 'west': 'east', 'east': 'west'}

def empty(x, y, z):
    n = t.name(x, y, z)
    return n is None or n in ('air', 'cave_air')

def interior(F):
    cells = set()
    for x in range(1, t.X - 1):
        for z in range(1, t.Z - 1):
            if empty(x, F, z) or not (empty(x, F + 1, z) and empty(x, F + 2, z)): continue
            fl = t.name(x, F, z)
            if not (sol[x, F, z] or 'slab' in fl): continue
            # walled in on all four sides, and roofed
            ok = True
            for dx, dz in DIRS.values():
                hit = False
                for k in range(1, 16):
                    xx, zz = x + dx * k, z + dz * k
                    if not (0 <= xx < t.X and 0 <= zz < t.Z): break
                    if not empty(xx, F + 1, zz) and t.name(xx, F + 1, zz) not in ('vine',): hit = True; break
                if not hit: ok = False; break
            if ok and any(sol[x, y, z] or 'slab' in (t.name(x, y, z) or '') for y in range(F + 3, min(t.Y, F + 12))):
                cells.add((x, z))
    return cells

def reserved(F, cells):
    if not cells: return set()
    xs = [c[0] for c in cells]; zs = [c[1] for c in cells]
    res = set()
    for x in range(min(xs), max(xs) + 1):
        for z in range(min(zs), max(zs) + 1):
            mark = False
            for y in range(F + 1, F + 3):
                n = t.name(x, y, z) or ''
                if any(k in n for k in ('door', 'stairs', 'ladder', 'gate', 'slab', 'fence')) and 'trapdoor' not in n:
                    # (the walls' own window fences and sills stand on the wall line, outside the interior cells)
                    if wall_side_any(x, z, F): continue
                    mark = True
            if not mark and empty(x, F, z) and (x, z) not in cells:
                if any((x + a, z + b) in cells for a in (-1, 0, 1) for b in (-1, 0, 1)): mark = True   # the stairwell's rim
            if mark:
                for a in (-1, 0, 1):
                    for b in (-1, 0, 1): res.add((x + a, z + b))
    # doorways: keep the way in clear
    for x in range(t.X):
        for z in range(t.Z):
            if 'door' in (t.name(x, F + 1, z) or '') and 'trapdoor' not in t.name(x, F + 1, z):
                for a in range(-2, 3):
                    for b in range(-2, 3): res.add((x + a, z + b))
    return res

def wall_side_any(x, z, F):
    """On a wall line: a full block in the same column at body or head height counts as wall."""
    return any(sol[x, y, z] for y in (F + 1, F + 2, F + 3)) and (x, z) not in INTERIOR_CACHE.get(F, set())

INTERIOR_CACHE = {}

def wall_side(x, F, z, full_only=True):
    """The side of (x,z) with a wall: full blocks at body and head height (or, unless full_only, any wall - a window)."""
    for d, (dx, dz) in DIRS.items():
        if sol[x + dx, F + 1, z + dz] and sol[x + dx, F + 2, z + dz]: return d
    if full_only: return None
    for d, (dx, dz) in DIRS.items():
        n = t.name(x + dx, F + 1, z + dz) or ''
        if (x + dx, z + dz) not in INTERIOR_CACHE.get(F, set()) and n and n not in ('air', 'cave_air') and 'door' not in n \
                and (sol[x + dx, F + 1, z + dz] or any(k in n for k in ('pane', 'glass', 'fence', 'log', 'wall'))):
            return d
    return None

def furnish(F, plan, label):
    cells = interior(F); INTERIOR_CACHE[F] = cells; res = reserved(F, cells)
    cand = [(x, z, wall_side(x, F, z, False), wall_side(x, F, z) is not None) for (x, z) in sorted(cells) if (x, z) not in res and wall_side(x, F, z, False)]
    print(label, 'interior', len(cells), 'reserved', len(cells & res), 'wall spots', len(cand))
    # walk the walls in runs, leaving every fourth spot open
    placed = []; i = 0
    cand.sort(key=lambda c: (c[2], c[0] + c[1] * 0.01 if c[2] in ('north', 'south') else c[1] + c[0] * 0.01))
    queue = list(plan)
    taken = set()
    for k, (x, z, d, full) in enumerate(cand):
        if not queue: break
        if k % 4 == 3: continue
        if any((x + a, z + b) in taken for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))) and k % 2: pass
        what = queue.pop(0)
        if what == 'bookshelf2' and not full: what = 'bookshelf'
        taken.add((x, z))
        face = OPP[d]                                   # turned to face the room
        if what == 'bookshelf2':
            t.set(x, F + 1, z, 'bookshelf'); t.set(x, F + 2, z, 'bookshelf')
            if R.random() < 0.4: t.set(x, F + 3, z, 'candle', candles=R.choice([1, 2, 3]), lit='true', waterlogged='false')
        elif what == 'bookshelf':
            t.set(x, F + 1, z, 'bookshelf')
            t.set(x, F + 2, z, R.choice(['candle', 'flower_pot', 'potted_fern', 'potted_red_mushroom']), **({'candles': 2, 'lit': 'true', 'waterlogged': 'false'} if True else {}))
        elif what == 'barrel':
            t.set(x, F + 1, z, 'barrel', facing='up', open='false')
            if R.random() < 0.5: t.set(x, F + 2, z, 'candle', candles=R.choice([1, 2, 3, 4]), lit='true', waterlogged='false')
        elif what == 'chest':
            t.set(x, F + 1, z, 'chest', facing=face, type='single', waterlogged='false')
        elif what == 'lectern':
            t.set(x, F + 1, z, 'lectern', facing=face, has_book='true', powered='false')
        elif what in ('brewing_stand',):
            t.set(x, F + 1, z, 'brewing_stand', has_bottle_0='true', has_bottle_1='false', has_bottle_2='true')
        elif what == 'cauldron':
            t.set(x, F + 1, z, 'water_cauldron', level=2)
        elif what in ('cartography_table', 'crafting_table', 'smithing_table', 'fletching_table', 'enchanting_table', 'loom'):
            t.set(x, F + 1, z, what, **({'facing': face} if what == 'loom' else {}))
        elif what == 'grindstone':
            t.set(x, F + 1, z, 'grindstone', face='floor', facing=face)
        placed.append((what, x, z, d))
    return cells, res, placed

# fix candle/pot props (pots take none)
_set = t.set
def set2(x, y, z, name, nbt=None, **p):
    if name.startswith('potted_') or name == 'flower_pot': p = {}
    return _set(x, y, z, name, nbt, **p)
t.set = set2

GROUND = ['bookshelf2', 'bookshelf2', 'barrel', 'chest', 'bookshelf2', 'crafting_table', 'barrel', 'bookshelf2', 'cartography_table',
          'brewing_stand', 'bookshelf2', 'barrel', 'cauldron', 'bookshelf', 'chest', 'bookshelf2', 'fletching_table', 'barrel',
          'bookshelf2', 'smithing_table', 'bookshelf', 'barrel', 'bookshelf2', 'grindstone', 'bookshelf2', 'barrel', 'chest', 'bookshelf2']
UP = ['lectern', 'bookshelf2', 'chest', 'barrel', 'bookshelf2', 'brewing_stand', 'bookshelf', 'barrel', 'enchanting_table', 'bookshelf2', 'barrel']
g_cells, g_res, g = furnish(2, GROUND, 'ground')
u_cells = interior(7); INTERIOR_CACHE[7] = u_cells; u_res = reserved(7, u_cells)
# Merlin: upstairs, the free wall spot farthest from the stairwell (hidden behind the partition)
def holes(F, cells):
    xs = [c[0] for c in cells]; zs = [c[1] for c in cells]
    return [(x, z) for x in range(min(xs), max(xs) + 1) for z in range(min(zs), max(zs) + 1) if empty(x, F, z) and (x, z) not in cells]
hole = holes(7, u_cells) or [(t.X // 2, t.Z // 2)]
best = None
for (x, z) in u_cells:
    if (x, z) in u_res or not empty(x, 8, z) or not empty(x, 9, z): continue
    d = wall_side(x, 7, z)
    if not d: continue
    dx, dz = DIRS[OPP[d]]
    if not empty(x + dx, 8, z + dz): continue
    dist = min(abs(x - a) + abs(z - b) for a, b in hole)
    if best is None or dist > best[0]: best = (dist, x, z, d)
print('MERLIN', best)
_, mx, mz, md = best
KEEP = {(mx + a, mz + b) for a in (-1, 0, 1) for b in (-1, 0, 1)}
# his journal on a lectern at his side, along the wall
for px, pz in ((DIRS[md][1], DIRS[md][0]), (-DIRS[md][1], -DIRS[md][0])):
    lx, lz = mx + px, mz + pz
    if (lx, lz) in u_cells and empty(lx, 8, lz):
        t.set(lx, 8, lz, 'lectern', facing=OPP[md], has_book='true', powered='false'); print('JOURNAL', lx, lz); break
_interior = interior
def interior(F, _i=_interior):
    c = _i(F)
    return c - KEEP if F == 7 else c
u_cells, u_res, u = furnish(7, UP[1:], 'upper')
print('ground:', collections.Counter(w for w, *_ in g)); print('upper:', collections.Counter(w for w, *_ in u))

# raiders: open ground-floor cells away from the walls, spread out
open_g = [(x, z) for (x, z) in g_cells if z <= 15 and (x, z) not in g_res and empty(x, 3, z) and empty(x, 4, z) and not wall_side(x, 2, z)]
R.shuffle(open_g); raiders = []
for c in open_g:
    if all(abs(c[0] - r[0]) + abs(c[1] - r[1]) >= 5 for r in raiders): raiders.append(c)
    if len(raiders) == 4: break
print('RAIDERS', raiders)
t.save(str(ROOT / 'src/main/resources/data/ancientpeaks_lore/structures/churches/church_merlin.nbt'))
