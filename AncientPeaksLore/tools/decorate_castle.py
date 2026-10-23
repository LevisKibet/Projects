"""Furnishes Camelot's rooms (castle/grand_castle_ruined.nbt), each for what it was, then lets the years have them:
moss over the carpets, cobwebs in the corners, chairs knocked over, candles long out.

    the throne hall      stepped hall in the keep: checkered floor, red and gold runner up the steps to the throne,
                         banners and knights' statues down the walls, candle chandeliers
    the round table      a ring of oak with twelve seats, every knight's banner on the walls
    the library          shelves floor to ceiling, reading tables and lecterns
    the king's chamber   a great red bed, a desk and lectern, wardrobes, banners of red and gold
    Mordred's chamber    black and grey, soul light, a desk with his letters
    the princess's tower pink and white, flowers, a loom and a vanity
    the treasury         gold heaped in the corners, three chests (castle_treasure)
    guard rooms          the four corner towers and the gatehouse: beds, armour, weapons on the walls, an armoury chest
    servants' quarters   the two halls in the outer ward: a kitchen hall, and a dormitory
    the archery range    the north ward: four lanes of targets on hay, a shooting line, a practice dummy

    python3 tools/decorate_castle.py      (reads tools/base/grand_castle_fixed.nbt, writes the shipped template)
"""
import sys, pathlib, random, math, numpy as np
sys.path.insert(0, '/home/claude/hl/tools')
from nbtedit import Template, C, S, I, L, Tag, ints
from scipy import ndimage

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / 'tools/base/grand_castle_fixed.nbt'
OUT = ROOT / 'src/main/resources/data/ancientpeaks_lore/structures/castle/grand_castle_ruined.nbt'
R = random.Random(1491)
t = Template(str(SRC))
X, Y, Z = t.X, t.Y, t.Z
sol = t.solid_mask(); occ = t.occupied()
names = np.array(t.names + ['?'])
SOFT = ['moss_carpet', 'vine', 'cobweb', 'candle', 'lantern', 'chain', 'soul_lantern', 'end_rod']
free = ~occ | (np.isin(names, SOFT)[t.G] & (t.G >= 0))
walk = np.zeros_like(free); walk[:, 1:-2, :] = free[:, 1:-2, :] & free[:, 2:-1, :] & sol[:, :-3, :]
roof = np.zeros_like(free); d = np.full((X, Z), 99)
for y in range(Y - 1, -1, -1):
    d = np.where(sol[:, y, :], 0, np.minimum(d + 1, 99)); roof[:, y, :] = (d >= 2) & (d <= 20)
lab, _ = ndimage.label(walk & roof, structure=np.ones((3, 3, 3)))
DIRS = {'north': (0, -1), 'south': (0, 1), 'west': (-1, 0), 'east': (1, 0)}
OPP = {'north': 'south', 'south': 'north', 'west': 'east', 'east': 'west'}
FACING_BYTE = {'north': 2, 'south': 3, 'west': 4, 'east': 5}
YAW = {'south': 0.0, 'west': 90.0, 'north': 180.0, 'east': 270.0}      # an entity looking that way
stats = {}
def count(k, n=1): stats[k] = stats.get(k, 0) + n

# ------------------------------------------------------------------ editing
def name(x, y, z): return t.name(x, y, z)
def empty(x, y, z):
    n = name(x, y, z); return n is None or n in ('air', 'cave_air', 'moss_carpet', 'cobweb')
def put(x, y, z, block, nbt=None, **props):
    """Overwrites whatever is there (for floors and clearing)."""
    if not (0 <= x < X and 0 <= y < Y and 0 <= z < Z): return
    s = t.state(block, **props)
    e = {"pos": ints(x, y, z), "state": I(s)}
    if nbt is not None: e["nbt"] = nbt
    if (x, y, z) in t.index: t.blocks[t.index[(x, y, z)]] = C(e)
    else: t.index[(x, y, z)] = len(t.blocks); t.blocks.append(C(e))
    t.G[x, y, z] = s
def place(x, y, z, block, nbt=None, **props):
    """Only into air (or soft clutter: moss carpet, cobweb, a candle)."""
    n = name(x, y, z)
    if n is not None and n not in ('air', 'cave_air', 'moss_carpet', 'cobweb'): return False
    put(x, y, z, block, nbt, **props); return True
def item(i, n=1, tag=None):
    d = {"id": S("minecraft:" + i), "Count": Tag(1, n)}
    if tag: d["tag"] = tag
    return C(d)
def worn(i): return item(i, 1, C({"Damage": I(R.randint(30, 160))}))
def lore_book(n):
    """A lectern that always holds lore book n (LootProcessor reads LoreBook)."""
    return C({"id": S("minecraft:lectern"), "LoreBook": I(n)})
def chest(x, y, z, facing, table=None):
    nbt = C({"id": S("minecraft:chest"), "LootTable": S("ancientpeaks_lore:chests/" + table)}) if table else None
    if place(x, y, z, 'chest', nbt, facing=facing, type='single', waterlogged='false'): count('chest')
def barrel(x, y, z, table=None):
    nbt = C({"id": S("minecraft:barrel"), "LootTable": S("ancientpeaks_lore:chests/" + table)}) if table else None
    if place(x, y, z, 'barrel', nbt, facing=R.choice(['up', 'up', 'north', 'east']), open='false'): count('barrel')
def candle(x, y, z, colour='', n=None, lit=None):
    lit = (R.random() < 0.3) if lit is None else lit
    if place(x, y, z, (colour + '_candle') if colour else 'candle', candles=str(n or R.randint(1, 4)), lit=str(lit).lower(), waterlogged='false'): count('candle')
def stand(x, y, z, facing, armour=('iron', 'iron', 'iron', 'iron'), hand=None):
    slots = ['boots', 'leggings', 'chestplate', 'helmet']
    arm = [worn(f'{m}_{s}') if m and R.random() < 0.85 else C({}) for m, s in zip(armour, slots)]
    t.add_entity(x, y, z, C({"id": S("minecraft:armor_stand"), "ShowArms": Tag(1, 1), "NoBasePlate": Tag(1, 0),
                             "Rotation": L(5, [Tag(5, YAW[facing] + R.uniform(-12, 12)), Tag(5, 0.0)]),
                             "ArmorItems": L(10, arm), "HandItems": L(10, [worn(hand) if hand else C({}), C({})])}))
    count('armour stand')
def frame(x, y, z, facing, it):
    """An item frame on the wall behind (x,y,z), facing out into the room."""
    if not empty(x, y, z): return
    t.add_entity(x, y, z, C({"id": S("minecraft:item_frame"), "Facing": Tag(1, FACING_BYTE[facing]), "Item": worn(it) if 'sword' in it or 'bow' in it or 'axe' in it else item(it),
                             "TileX": I(x), "TileY": I(y), "TileZ": I(z), "ItemRotation": Tag(1, R.choice([0, 1, 7]))}))
    count('frame')
def bed(x, y, z, facing, colour):
    """Foot at (x,y,z), head one step towards facing."""
    dx, dz = DIRS[facing]
    if not (empty(x, y, z) and empty(x + dx, y, z + dz)): return False
    put(x, y, z, colour + '_bed', facing=facing, part='foot', occupied='false')
    put(x + dx, y, z + dz, colour + '_bed', facing=facing, part='head', occupied='false')
    count('bed'); return True
def banner(x, y, z, facing, colour):
    if place(x, y, z, colour + '_wall_banner', facing=facing): count('banner')
def chandelier(x, y0, z, ceil, kind='candle', colour=''):
    """Hangs from the ceiling at (x, ceil, z): chain down, then a cross of fence with candles on its arms (or a lantern)."""
    low = max(y0 + 3, ceil - 3)
    if low >= ceil: return
    for y in range(low + 1, ceil): place(x, y, z, 'chain', axis='y', waterlogged='false')
    if kind == 'lantern' or ceil - y0 < 5:
        place(x, low, z, 'lantern', hanging='true', waterlogged='false'); count('chandelier'); return
    arms = {d: empty(x + v[0], low, z + v[1]) and empty(x + v[0], low + 1, z + v[1]) for d, v in DIRS.items()}
    place(x, low, z, 'spruce_fence', waterlogged='false', **{d: str(a).lower() for d, a in arms.items()})
    for dname, (dx, dz) in DIRS.items():
        if not arms[dname]: continue
        place(x + dx, low, z + dz, 'spruce_fence', waterlogged='false', **{k: str(k == OPP[dname]).lower() for k in DIRS})
        if R.random() < 0.85: candle(x + dx, low + 1, z + dz, colour, n=R.randint(1, 3), lit=R.random() < 0.45)
    place(x, low - 1, z, 'lantern', hanging='true', waterlogged='false')
    count('chandelier')

# ------------------------------------------------------------------ rooms
DONE = set()
class Room:
    def __init__(self, at, title, xr=None, zr=None, rid=0):
        x, y, z = at if at else (0, 0, 0)
        for r in range(0, 4 if not rid else 0):
            for dy in (0, -1, 1, -2, 2, -3, 3):
                for dx in range(-r, r + 1):
                    for dz in range(-r, r + 1):
                        if 0 <= x + dx < X and 0 <= y + dy < Y and 0 <= z + dz < Z and lab[x + dx, y + dy, z + dz]:
                            rid = lab[x + dx, y + dy, z + dz]; break
                    if rid: break
                if rid: break
            if rid: break
        assert rid, f'no room at {at} ({title})'
        m = lab == rid
        DONE.add(int(rid)); self.rid = int(rid)
        self.title = title
        self.floor = {}
        for (cx, cy, cz) in zip(*np.nonzero(m)):
            if xr and not (xr[0] <= cx <= xr[1]): continue
            if zr and not (zr[0] <= cz <= zr[1]): continue
            k = (int(cx), int(cz))
            if k not in self.floor or cy < self.floor[k]: self.floor[k] = int(cy)
        xs = [k[0] for k in self.floor]; zs = [k[1] for k in self.floor]
        self.x0, self.x1, self.z0, self.z1 = min(xs), max(xs), min(zs), max(zs)
        self.cx, self.cz = (self.x0 + self.x1) / 2, (self.z0 + self.z1) / 2
        self.used = set()
        print(f'{title}: {len(self.floor)} cells, x{self.x0}-{self.x1} z{self.z0}-{self.z1}, floor y{min(self.floor.values())}-{max(self.floor.values())}')

    def y(self, x, z): return self.floor.get((x, z))
    def ceil(self, x, z):
        y = self.floor[(x, z)]
        h = 0
        while y + h < Y and not sol[x, y + h, z]: h += 1
        return y + h
    def wall(self, x, z, d):
        """Is there wall on side d of this cell (at foot and head height)?"""
        y = self.floor[(x, z)]; dx, dz = DIRS[d]
        if not (0 <= x + dx < X and 0 <= z + dz < Z): return False
        return sol[x + dx, y, z + dz] and sol[x + dx, y + 1, z + dz]
    def walls(self, x, z): return [d for d in DIRS if self.wall(x, z, d)]
    def doorway(self, x, z):
        """Next to an opening out of the room (keep it clear)."""
        y = self.floor[(x, z)]
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                k = (x + dx, z + dz)
                if k in self.floor: continue
                if 0 <= x + dx < X and 0 <= z + dz < Z and walk[x + dx, y, z + dz] and not sol[x + dx, y, z + dz]: return True
        return False
    def edge(self):
        """Cells along a wall, not by a door: (x, z, the wall's side)."""
        out = []
        for (x, z) in self.floor:
            ws = self.walls(x, z)
            if ws and not self.doorway(x, z): out.append((x, z, ws[0]))
        return out
    def inner(self, margin=1):
        return [(x, z) for (x, z) in self.floor if all((x + dx, z + dz) in self.floor for dx in range(-margin, margin + 1) for dz in range(-margin, margin + 1))]
    def take(self, x, z): self.used.add((x, z))
    def free(self, x, z): return (x, z) in self.floor and (x, z) not in self.used and empty(x, self.floor[(x, z)], z)

    def clear(self):
        """Takes out the old scattered furniture (the rooms are furnished properly below)."""
        junk = ('bookshelf', 'barrel', 'chest', 'candle', 'lantern', 'soul_lantern', 'cobweb', 'spruce_fence', 'end_rod', 'chain',
                'spruce_planks', 'oak_planks', 'spruce_pressure_plate', 'spruce_stairs', 'dark_oak_fence')
        for (x, z), y in self.floor.items():
            for yy in range(y, min(Y, y + 12)):
                n = name(x, yy, z)
                if n in junk: put(x, yy, z, 'air')
                elif n is not None and sol[x, yy, z]: break
        ents = t.root['entities'].v[1]; keep = []
        for e in ents:
            bx, by, bz = [v.v for v in e.v['blockPos'].v[1]]
            if (bx, bz) in self.floor and abs(by - self.floor[(bx, bz)]) < 6 and e.v['nbt'].v['id'].v == 'minecraft:armor_stand': continue
            keep.append(e)
        t.root['entities'].v = (t.root['entities'].v[0], keep)

    def ruin(self, moss=0.10, webs=0.5, leaves=1):
        """The years: moss and leaf litter on the floor, cobwebs up in the corners, now and then a clump of azalea."""
        for (x, z), y in self.floor.items():
            if empty(x, y, z) and R.random() < moss: place(x, y, z, 'moss_carpet'); count('moss')
            n = name(x, y, z)
            if n and n.endswith('_carpet') and n != 'moss_carpet' and R.random() < 0.18: put(x, y, z, 'moss_carpet'); count('moss')
        for (x, z, d) in self.edge():
            c = self.ceil(x, z) - 1
            if len(self.walls(x, z)) >= 2 and empty(x, c, z) and R.random() < webs: place(x, c, z, 'cobweb'); count('cobweb')
        corners = [(x, z) for (x, z, d) in self.edge() if len(self.walls(x, z)) >= 2]
        R.shuffle(corners)
        for (x, z) in corners[:leaves]:
            y = self.floor[(x, z)]
            for (dx, dy, dz) in ((0, 0, 0), (0, 1, 0), (1, 0, 0), (0, 0, 1)):
                if (x + dx, z + dz) in self.floor and R.random() < 0.8: place(x + dx, y + dy, z + dz, 'azalea_leaves', persistent='true', distance='1', waterlogged='false')
            count('leaf clump')

def wall_runs(room, n_per=1):
    return room.edge()

# ------------------------------------------------------------------ the throne hall
def throne_hall():
    r = Room((85, 57, 70), 'throne hall', xr=(80, 90))
    r.clear()
    cx = 85
    # checkered floor (black and white, a few cracked), a red runner edged in gold up the steps
    for (x, z), y in r.floor.items():
        below = name(x, y - 1, z)
        if below and sol[x, y - 1, z] and not any(k in below for k in ('stairs', 'slab')):
            tile = 'polished_blackstone' if (x + z) % 2 else 'calcite'
            if R.random() < 0.08: tile = 'cracked_polished_blackstone_bricks' if tile == 'polished_blackstone' else 'cracked_stone_bricks'
            if R.random() < 0.06: tile = 'mossy_cobblestone'
            put(x, y - 1, z, tile)
    top = min(z for (x, z) in r.floor if x == cx)
    for (x, z), y in r.floor.items():
        if z <= top + 1: continue
        if abs(x - cx) <= 1 and R.random() > 0.06: place(x, y, z, 'red_carpet'); r.take(x, z)
        elif abs(x - cx) == 2 and R.random() > 0.15: place(x, y, z, 'yellow_carpet'); r.take(x, z)
    # the throne on the dais
    ty = r.y(cx, top)
    put(cx, ty - 1, top, 'gold_block')
    place(cx, ty, top, 'polished_blackstone_stairs', facing='north', half='bottom', shape='straight', waterlogged='false')
    for dx in (-1, 1):
        place(cx + dx, ty, top, 'polished_blackstone_wall', up='true', north='none', south='none', east='none', west='none', waterlogged='false')
        place(cx + dx, ty, top + 1, 'red_carpet')
    for dx in (-2, 0, 2):
        banner(cx + dx, ty + 3, top, 'south', 'red' if dx == 0 else 'yellow')
    r.take(cx, top); r.take(cx - 1, top); r.take(cx + 1, top)
    # knights' statues and banners down both walls, every few blocks
    side = {}
    for (x, z, d) in r.edge():
        if d in ('west', 'east'): side.setdefault(d, []).append((x, z))
    for d, cells in side.items():
        cells.sort(key=lambda c: c[1])
        for i, (x, z) in enumerate(cells):
            if z <= top + 1: continue
            y = r.y(x, z)
            if i % 4 == 0 and r.free(x, z):
                stand(x, y, z, OPP[d], armour=('chainmail', 'iron', 'iron', 'iron'), hand=R.choice(['iron_sword', 'iron_sword', None]))
                r.take(x, z)
            elif i % 4 == 2:
                banner(x, y + 2, z, OPP[d], R.choice(['red', 'red', 'yellow']))
                if r.free(x, z) and R.random() < 0.5: candle(x, y, z)
    # candle chandeliers over the runner
    for z in range(top + 3, r.z1, 5):
        if (cx, z) in r.floor: chandelier(cx, r.y(cx, z), z, r.ceil(cx, z), colour='')
    top_up(r)
    r.ruin(moss=0.06, webs=0.6, leaves=2)

# ------------------------------------------------------------------ the round table
def round_table():
    r = Room((75, 50, 61), 'round table')
    r.clear()
    inner = r.inner(1)
    cx = round(sum(x for x, z in inner) / len(inner)); cz = round(sum(z for x, z in inner) / len(inner))
    y = r.y(cx, cz)
    rad = min(3.4, (min(r.x1 - r.x0, r.z1 - r.z0) - 4) / 2)
    seats = 0
    for (x, z) in list(r.floor):
        dd = math.hypot(x - cx, z - cz)
        if rad - 1.1 <= dd <= rad and r.y(x, z) == y:
            place(x, y, z, 'dark_oak_slab', type='top', waterlogged='false'); r.take(x, z)
            if R.random() < 0.2: candle(x, y + 1, z, 'white')
    for (x, z) in list(r.floor):
        dd = math.hypot(x - cx, z - cz)
        if rad < dd <= rad + 1.05 and r.free(x, z) and r.y(x, z) == y:
            ax, az = x - cx, z - cz
            out = ('east' if ax > 0 else 'west') if abs(ax) >= abs(az) else ('south' if az > 0 else 'north')
            if R.random() < 0.85:
                place(x, y, z, 'spruce_stairs', facing=out, half='bottom', shape='straight', waterlogged='false'); seats += 1
            r.take(x, z)
    place(cx, y, cz, 'lectern', lore_book(29), facing='south', has_book='false', powered='false')        # the King's own account of the ship in the fog
    # every knight's banner on the walls
    colours = ['red', 'blue', 'yellow', 'green', 'white', 'purple', 'cyan', 'orange', 'black', 'light_blue', 'lime', 'brown']
    for i, (x, z, d) in enumerate(r.edge()):
        if i % 3 == 0: banner(x, r.y(x, z) + 2, z, OPP[d], colours[(i // 3) % len(colours)])
    chandelier(cx, y, cz, r.ceil(cx, cz), kind='lantern')
    stats['round table seats'] = seats
    top_up(r)
    r.ruin(moss=0.08, webs=0.5, leaves=1)

# ------------------------------------------------------------------ the library
def library():
    r = Room((75, 85, 42), 'library')
    r.clear()
    print('   edge', len(r.edge()), 'cells with a wall', sum(1 for c in r.floor if r.walls(*c)), 'doorways', sum(1 for c in r.floor if r.doorway(*c)))
    for (x, z, d) in r.edge():
        y = r.y(x, z); c = r.ceil(x, z)
        if R.random() < 0.12: continue                                  # a gap where a shelf has fallen
        for yy in range(y, min(c - 1, y + 3)):
            place(x, yy, z, 'chiseled_bookshelf' if R.random() < 0.15 else 'bookshelf', **({'facing': OPP[d], 'slot_0_occupied': 'true', 'slot_1_occupied': 'false', 'slot_2_occupied': 'true', 'slot_3_occupied': 'true', 'slot_4_occupied': 'false', 'slot_5_occupied': 'true'} if False else {}))
        r.take(x, z)
    inner = [c for c in r.inner(1) if r.free(*c)]
    R.shuffle(inner)
    tables = 0
    for (x, z) in inner:
        if tables >= 3: break
        y = r.y(x, z)
        if not all(r.free(x + dx, z) for dx in (-1, 0, 1)): continue
        place(x, y, z, 'spruce_slab', type='top', waterlogged='false')
        candle(x, y + 1, z)
        for dx, f in ((-1, 'west'), (1, 'east')):
            if R.random() < 0.8: place(x + dx, y, z, 'spruce_stairs', facing=f, half='bottom', shape='straight', waterlogged='false')
        for dx in (-1, 0, 1): r.take(x + dx, z)
        tables += 1
    for (x, z) in inner:
        if r.free(x, z) and R.random() < 0.08: place(x, r.y(x, z), z, 'lectern', facing=R.choice(list(DIRS)), has_book='false', powered='false'); r.take(x, z)
    for (x, z) in inner:
        if r.free(x, z): chest(x, r.y(x, z), z, 'south', 'castle_library'); r.take(x, z); break
    cx, cz = round(r.cx), round(r.cz)
    if (cx, cz) in r.floor: chandelier(cx, r.y(cx, cz), cz, r.ceil(cx, cz), kind='lantern')
    top_up(r)
    r.ruin(moss=0.10, webs=0.8, leaves=1)

# ------------------------------------------------------------------ chambers
def chamber(at, title, bed_colour, carpet, banner_colour, light, extras, desk_book=None):
    r = Room(at, title)
    r.clear()
    # the bed against the wall furthest from the door, head to the wall
    best = None
    for (x, z, d) in r.edge():
        dx, dz = DIRS[OPP[d]]
        if (x + dx, z + dz) in r.floor and not r.doorway(x, z):
            score = -sum(1 for c in r.floor if r.doorway(*c) and abs(c[0] - x) + abs(c[1] - z) < 4)
            if best is None or score > best[0]: best = (score, x, z, d)
    if best:
        _, x, z, d = best
        dx, dz = DIRS[OPP[d]]
        hx, hz = x, z                                                     # head against the wall
        fx, fz = x + dx, z + dz
        sx, sz = (1, 0) if d in ('north', 'south') else (0, 1)            # a double bed: two side by side
        made = 0
        for k in (0, 1):
            if bed(fx + sx * k, r.y(fx, fz), fz + sz * k, d, bed_colour):
                r.take(hx + sx * k, hz + sz * k); r.take(fx + sx * k, fz + sz * k); made += 1
        # rug round the bed, a chest at its foot
        cfx, cfz = fx + dx, fz + dz
        if made and r.free(cfx, cfz): chest(cfx, r.y(cfx, cfz), cfz, OPP[d]); r.take(cfx, cfz)
        print('   bed', made, 'at', (x, z), d)
        for ddx in range(-2, 3):
            for ddz in range(-2, 3):
                cx, cz = fx + ddx, fz + ddz
                if r.free(cx, cz) and R.random() < 0.85: place(cx, r.y(cx, cz), cz, carpet + '_carpet')
    # along the walls: desk with a lectern, wardrobes, banners, candles
    edge = [e for e in r.edge() if r.free(e[0], e[1])]
    R.shuffle(edge)
    placed = {'desk': 0, 'wardrobe': 0}
    for (x, z, d) in edge:
        y = r.y(x, z)
        if not r.free(x, z): continue
        if placed['desk'] == 0:
            place(x, y, z, 'lectern', lore_book(desk_book) if desk_book else None, facing=OPP[d], has_book='false', powered='false'); r.take(x, z)
            dx, dz = DIRS[OPP[d]]
            if r.free(x + dx, z + dz): place(x + dx, y, z + dz, 'dark_oak_stairs', facing=d, half='bottom', shape='straight', waterlogged='false'); r.take(x + dx, z + dz)
            placed['desk'] = 1; continue
        if placed['wardrobe'] < 2:
            barrel(x, y, z); place(x, y + 1, z, 'spruce_trapdoor', facing=OPP[d], half='bottom', open='true', powered='false', waterlogged='false')
            r.take(x, z); placed['wardrobe'] += 1; continue
        if R.random() < 0.35: banner(x, y + 2, z, OPP[d], banner_colour)
        if R.random() < 0.25:
            extras(r, x, y, z, d); r.take(x, z)
    cx, cz = round(r.cx), round(r.cz)
    if (cx, cz) in r.floor: chandelier(cx, r.y(cx, cz), cz, r.ceil(cx, cz), kind='lantern' if light == 'lantern' else 'candle', colour=light if light not in ('lantern', '') else '')
    if light == 'soul':
        for (x, z, d) in edge[:3]:
            if r.free(x, z): place(x, r.y(x, z), z, 'soul_lantern', hanging='false', waterlogged='false'); r.take(x, z)
    top_up(r)
    r.ruin(moss=0.08, webs=0.7, leaves=1)
    return r

def king_extras(r, x, y, z, d):
    R.choice([lambda: candle(x, y, z, 'yellow'), lambda: place(x, y, z, 'potted_red_tulip'),
              lambda: stand(x, y, z, OPP[d], armour=('golden', 'iron', 'golden', 'golden'), hand='golden_sword')])()
def mordred_extras(r, x, y, z, d):
    R.choice([lambda: candle(x, y, z, 'black'), lambda: place(x, y, z, 'skeleton_skull', rotation=str(R.randint(0, 15))),
              lambda: place(x, y, z, 'brewing_stand', has_bottle_0='false', has_bottle_1='false', has_bottle_2='false'),
              lambda: candle(x, y, z, 'gray')])()
def princess_extras(r, x, y, z, d):
    R.choice([lambda: candle(x, y, z, 'pink'), lambda: candle(x, y, z, 'white'), lambda: place(x, y, z, 'potted_allium'),
              lambda: place(x, y, z, 'potted_azure_bluet'), lambda: place(x, y, z, 'potted_pink_tulip'),
              lambda: place(x, y, z, 'loom', facing=OPP[d]), lambda: place(x, y, z, 'potted_flowering_azalea_bush')])()

# ------------------------------------------------------------------ the treasury
def treasury():
    r = Room((135, 27, 72), 'treasury')
    r.clear()
    corners = [(x, z) for (x, z, d) in r.edge() if len(r.walls(x, z)) >= 2]
    for (x, z) in corners:
        y = r.y(x, z)
        for (dx, dz) in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, z + dz) in r.floor and r.free(x + dx, z + dz) and R.random() < 0.7:
                place(x + dx, y, z + dz, R.choice(['gold_block', 'raw_gold_block', 'raw_gold_block', 'gold_block']) if (dx, dz) == (0, 0) else 'light_weighted_pressure_plate', power='0')
                r.take(x + dx, z + dz); count('gold')
        if (x, z) in r.floor and name(x, y, z) in ('gold_block', 'raw_gold_block') and R.random() < 0.6: place(x, y + 1, z, 'gold_block' if R.random() < 0.3 else 'raw_gold_block')
    edge = [e for e in r.edge() if r.free(e[0], e[1])]
    R.shuffle(edge)
    for (x, z, d) in edge[:3]: chest(x, r.y(x, z), z, OPP[d], 'castle_treasure'); r.take(x, z)
    for (x, z, d) in edge[3:6]: barrel(x, r.y(x, z), z); r.take(x, z)
    for (x, z, d) in edge[6:9]:
        if r.free(x, z): stand(x, r.y(x, z), z, OPP[d], armour=('golden', 'golden', 'golden', 'golden'), hand=None); r.take(x, z)
    cx, cz = round(r.cx), round(r.cz)
    if (cx, cz) in r.floor: chandelier(cx, r.y(cx, cz), cz, r.ceil(cx, cz), kind='lantern')
    top_up(r)
    r.ruin(moss=0.05, webs=0.9, leaves=0)

# ------------------------------------------------------------------ guard rooms
def guard_room(at, title):
    r = Room(at, title)
    r.clear()
    edge = r.edge(); R.shuffle(edge)
    beds = stands = frames = 0
    for (x, z, d) in edge:
        y = r.y(x, z)
        if not r.free(x, z): continue
        dx, dz = DIRS[OPP[d]]
        if beds < 3 and r.free(x + dx, z + dz) and bed(x + dx, y, z + dz, d, R.choice(['red', 'brown', 'gray'])):
            r.take(x, z); r.take(x + dx, z + dz); beds += 1; continue
        if stands < 2:
            stand(x, y, z, OPP[d], armour=('chainmail', 'chainmail', 'iron', 'iron'), hand=R.choice(['iron_sword', 'iron_axe', 'bow'])); r.take(x, z); stands += 1; continue
        if frames < 4:
            frame(x, y + 1, z, OPP[d], R.choice(['iron_sword', 'bow', 'iron_axe', 'shield', 'crossbow'])); frames += 1
    kit = [lambda x, y, z, d: place(x, y, z, 'lectern', lore_book(31), facing=OPP[d], has_book='false', powered='false'),
           lambda x, y, z, d: chest(x, y, z, OPP[d], 'castle_armoury'), lambda x, y, z, d: barrel(x, y, z),
           lambda x, y, z, d: place(x, y, z, 'grindstone', face='floor', facing=OPP[d]),
           lambda x, y, z, d: place(x, y, z, 'fletching_table'), lambda x, y, z, d: barrel(x, y, z)]
    for (x, z, d) in edge:
        if not kit: break
        if r.free(x, z): kit.pop(0)(x, r.y(x, z), z, d); r.take(x, z)
    inner = [c for c in r.inner(1) if r.free(*c)]
    if inner:
        x, z = inner[len(inner) // 2]; y = r.y(x, z)
        place(x, y, z, 'spruce_fence', waterlogged='false', north='false', south='false', east='false', west='false')
        place(x, y + 1, z, 'spruce_pressure_plate', powered='false'); candle(x, y + 2, z) if False else None
        for f, (dx, dz) in DIRS.items():
            if r.free(x + dx, z + dz) and R.random() < 0.6: place(x + dx, y, z + dz, 'spruce_stairs', facing=f, half='bottom', shape='straight', waterlogged='false')
    cx, cz = round(r.cx), round(r.cz)
    if (cx, cz) in r.floor: chandelier(cx, r.y(cx, cz), cz, r.ceil(cx, cz), kind='lantern')
    top_up(r)
    r.ruin(moss=0.10, webs=0.6, leaves=1)

# ------------------------------------------------------------------ servants' quarters
def kitchen():
    r = Room((45, 2, 108), 'servants\' hall (kitchen)')
    r.clear()
    edge = r.edge(); R.shuffle(edge)
    kit = ['smoker', 'furnace', 'crafting_table', 'cauldron', 'barrel', 'barrel', 'chest', 'barrel', 'hay_block', 'composter', 'barrel']
    for (x, z, d) in edge:
        if not kit: break
        if not r.free(x, z): continue
        k = kit.pop(0); y = r.y(x, z)
        if k in ('smoker', 'furnace'): place(x, y, z, k, facing=OPP[d], lit='false')
        elif k == 'barrel': barrel(x, y, z)
        elif k == 'chest': chest(x, y, z, OPP[d], 'castle_quarters')
        elif k == 'cauldron': place(x, y, z, 'cauldron')
        elif k == 'composter': place(x, y, z, 'composter', level=str(R.randint(0, 6)))
        else: place(x, y, z, k)
        r.take(x, z)
    # the long table down the middle with benches
    zs = sorted({z for (x, z) in r.floor}); xs = sorted({x for (x, z) in r.floor})
    mz = zs[len(zs) // 2]
    for x in xs[2:-2]:
        if not r.free(x, mz): continue
        y = r.y(x, mz)
        place(x, y, mz, 'spruce_slab', type='top', waterlogged='false'); r.take(x, mz)
        if R.random() < 0.15: candle(x, y + 1, mz)
        for dz, f in ((-1, 'north'), (1, 'south')):
            if r.free(x, mz + dz) and R.random() < 0.75: place(x, y, mz + dz, 'spruce_slab', type='bottom', waterlogged='false'); r.take(x, mz + dz)
    top_up(r)
    r.ruin(moss=0.12, webs=0.6, leaves=1)

def dormitory():
    r = Room((32, 2, 108), 'servants\' dormitory')
    r.clear()
    edge = r.edge(); R.shuffle(edge)
    beds = 0
    for (x, z, d) in edge:
        y = r.y(x, z); dx, dz = DIRS[OPP[d]]
        if beds < 6 and r.free(x, z) and r.free(x + dx, z + dz) and bed(x + dx, y, z + dz, d, R.choice(['white', 'brown', 'light_gray'])):
            r.take(x, z); r.take(x + dx, z + dz); beds += 1
            sx, sz = (1, 0) if d in ('north', 'south') else (0, 1)
            if r.free(x + sx, z + sz): chest(x + sx, y, z + sz, OPP[d], 'castle_quarters') if R.random() < 0.3 else barrel(x + sx, y, z + sz); r.take(x + sx, z + sz)
    for (x, z) in r.inner(1):
        if r.free(x, z) and R.random() < 0.05: candle(x, r.y(x, z), z)
    top_up(r)
    r.ruin(moss=0.12, webs=0.6, leaves=1)

# ------------------------------------------------------------------ the archery range (the north ward, on the ground)
def archery():
    G = 2                                                                 # template layer 1 is the ground
    lanes = [58, 65, 72, 79, 86]
    def clear_col(x, z): return all(not occ[x, y, z] for y in range(1, 7))
    n = 0
    for lx in lanes:
        tz = 15
        if not all(clear_col(lx + dx, tz) for dx in (-1, 0, 1)): continue
        broken = R.random() < 0.25
        place(lx, G, tz, 'hay_block', axis='y')
        if broken: place(lx + 1, G, tz + 1, 'target', power='0')        # fallen off its stand
        else: place(lx, G + 1, tz, 'target', power='0')
        for dx in (-1, 1):
            if R.random() < 0.8: place(lx + dx, G, tz, 'hay_block', axis=R.choice(['x', 'y']))
            if R.random() < 0.4: place(lx + dx, G + 1, tz, 'hay_block', axis='y')
        for dz in range(1, 14, 2):                                       # lane markers, half sunk in the grass
            if clear_col(lx + 3, tz + dz) and R.random() < 0.5: place(lx + 3, G, tz + dz, 'cobblestone_slab', type='bottom', waterlogged='false')
        n += 1
    # the shooting line: a broken fence with gaps at each lane, barrels of arrows, a bow on a stand
    lz = 29
    for x in range(lanes[0] - 3, lanes[-1] + 4):
        if not clear_col(x, lz): continue
        if any(abs(x - lx) <= 1 for lx in lanes): continue
        if R.random() < 0.75: place(x, G, lz, 'spruce_fence', waterlogged='false', east=str(R.random() < 0.7).lower(), west=str(R.random() < 0.7).lower(), north='false', south='false')
    for x in (lanes[0] - 3, lanes[2] + 3, lanes[-1] + 3):
        if clear_col(x, lz + 2): barrel(x, G, lz + 2, 'castle_armoury' if x == lanes[2] + 3 else None)
    if clear_col(lanes[-1] + 5, lz + 1): stand(lanes[-1] + 5, G, lz + 1, 'north', armour=(None, 'leather', 'leather', None), hand='bow')
    # a practice dummy: an old stand in straw, off to one side
    if clear_col(lanes[0] - 6, 20):
        place(lanes[0] - 6, G, 20, 'hay_block', axis='y')
        stand(lanes[0] - 6, G + 1, 20, 'south', armour=(None, None, 'leather', 'leather'))
    # grass and weeds have the ward
    for x in range(lanes[0] - 7, lanes[-1] + 7):
        for z in range(13, 33):
            if clear_col(x, z) and empty(x, G, z) and R.random() < 0.12: place(x, G, z, R.choice(['grass', 'grass', 'fern', 'moss_carpet', 'dandelion']))
    stats['archery lanes'] = n


# ------------------------------------------------------------------ every other room: furnished for what it most likely was
def candelabra(x, y, z):
    if place(x, y, z, 'spruce_fence', waterlogged='false', north='false', south='false', east='false', west='false'):
        candle(x, y + 1, z, n=R.randint(2, 4)); return True
    return False
def table_set(r, x, z, slab='spruce_slab', chair='spruce_stairs', candles=0.4, chairs=0.8):
    y = r.y(x, z)
    if not r.free(x, z): return False
    place(x, y, z, slab, type='top', waterlogged='false'); r.take(x, z)
    if R.random() < candles: candle(x, y + 1, z)
    for f, (dx, dz) in DIRS.items():
        if r.free(x + dx, z + dz) and r.y(x + dx, z + dz) == y and R.random() < chairs:
            if R.random() < 0.15:                                         # knocked over
                place(x + dx, y, z + dz, chair, facing=R.choice(list(DIRS)), half='top', shape='straight', waterlogged='false')
            else:
                place(x + dx, y, z + dz, chair, facing=f, half='bottom', shape='straight', waterlogged='false')
            r.take(x + dx, z + dz)
    return True
def long_table(r, axis_x=True, cand=0.2):
    """A long table down the middle with benches either side."""
    xs = sorted({x for (x, z) in r.floor}); zs = sorted({z for (x, z) in r.floor})
    if axis_x:
        mz = zs[len(zs) // 2]; line = [(x, mz) for x in xs[2:-2]]; side = ((0, -1), (0, 1))
    else:
        mx = xs[len(xs) // 2]; line = [(mx, z) for z in zs[2:-2]]; side = ((-1, 0), (1, 0))
    for (x, z) in line:
        if not r.free(x, z): continue
        y = r.y(x, z)
        place(x, y, z, 'spruce_slab', type='top', waterlogged='false'); r.take(x, z)
        if R.random() < cand: candle(x, y + 1, z)
        elif R.random() < 0.1: place(x, y + 1, z, 'flower_pot')
        for (dx, dz) in side:
            if r.free(x + dx, z + dz) and R.random() < 0.8: place(x + dx, y, z + dz, 'spruce_slab', type='bottom', waterlogged='false'); r.take(x + dx, z + dz)

def wall_fill(r, kit, density=0.75):
    """Line the walls: every free spot along a wall gets something from the kit (weighted), most of them."""
    edge = [e for e in r.edge() if r.free(e[0], e[1])]
    R.shuffle(edge)
    for (x, z, d) in edge:
        if not r.free(x, z) or R.random() > density: continue
        total = sum(w for w, _ in kit); k = R.random() * total
        for w, f in kit:
            k -= w
            if k <= 0:
                if f(r, x, r.y(x, z), z, d) is not False: r.take(x, z)
                break

def k_barrel(r, x, y, z, d): barrel(x, y, z)
def k_barrels(r, x, y, z, d):
    barrel(x, y, z)
    if R.random() < 0.4 and r.ceil(x, z) - y > 2: barrel(x, y + 1, z)
def k_chest(table):
    return lambda r, x, y, z, d: chest(x, y, z, OPP[d], table)
def k_hay(r, x, y, z, d):
    place(x, y, z, 'hay_block', axis=R.choice(['x', 'y', 'z']))
    if R.random() < 0.3: place(x, y + 1, z, 'hay_block', axis='y')
def k_pot(r, x, y, z, d): place(x, y, z, 'decorated_pot', facing=OPP[d], waterlogged='false', cracked=str(R.random() < 0.4).lower())
def k_shelf(r, x, y, z, d):
    for yy in range(y, min(r.ceil(x, z) - 1, y + 3)): place(x, yy, z, 'bookshelf')
def k_lectern(r, x, y, z, d): place(x, y, z, 'lectern', facing=OPP[d], has_book='false', powered='false')
def k_candle(r, x, y, z, d): candle(x, y, z, n=R.randint(1, 4))
def k_candelabra(r, x, y, z, d): candelabra(x, y, z)
def k_banner(colours):
    def f(r, x, y, z, d):
        banner(x, y + 2, z, OPP[d], R.choice(colours)); return False      # the floor spot stays free
    return f
def k_stand(armour, hands):
    return lambda r, x, y, z, d: stand(x, y, z, OPP[d], armour=armour, hand=R.choice(hands))
def k_frame(items):
    def f(r, x, y, z, d):
        frame(x, y + 1, z, OPP[d], R.choice(items)); return False
    return f
def k_bed(colours):
    def f(r, x, y, z, d):
        dx, dz = DIRS[OPP[d]]
        if r.free(x + dx, z + dz) and r.y(x + dx, z + dz) == y and bed(x + dx, y, z + dz, d, R.choice(colours)):
            r.take(x + dx, z + dz); return True
        return False
    return f
def k_block(name, **props): return lambda r, x, y, z, d: place(x, y, z, name, **props)
def k_facing(name, **props): return lambda r, x, y, z, d: place(x, y, z, name, facing=OPP[d], **props)
def k_plant(r, x, y, z, d): place(x, y, z, R.choice(['potted_fern', 'potted_azalea_bush', 'potted_dead_bush', 'potted_red_tulip', 'potted_spruce_sapling', 'flower_pot']))
def k_anvil(r, x, y, z, d): place(x, y, z, R.choice(['anvil', 'chipped_anvil', 'damaged_anvil']), facing=R.choice(list(DIRS)))
def k_web(r, x, y, z, d): place(x, y, z, 'cobweb'); return False
def k_skull(r, x, y, z, d): place(x, y, z, 'skeleton_skull', rotation=str(R.randint(0, 15)))

KITS = {
    'storeroom': [(5, k_barrels), (3, k_hay), (2, k_pot), (0.4, k_chest('castle_quarters')), (1, k_block('cauldron')), (1, k_web), (1, k_block('composter', level='3'))],
    'barracks': [(4, k_bed(['red', 'brown', 'gray'])), (1, k_stand(('chainmail', 'chainmail', 'iron', 'iron'), ['iron_sword', 'iron_axe', 'bow', None])),
                 (2, k_frame(['iron_sword', 'bow', 'shield', 'crossbow', 'iron_axe'])), (2, k_barrel), (0.4, k_chest('castle_armoury')), (1, k_candle)],
    'study': [(5, k_shelf), (1, k_lectern), (1, k_candelabra), (1, k_plant), (0.4, k_chest('castle_library')), (1, k_banner(['blue', 'light_blue', 'white']))],
    'chapel': [(3, k_candelabra), (2, k_banner(['white', 'yellow'])), (1, k_plant), (1, k_candle)],
    'mess': [(3, k_barrels), (1, k_facing('smoker', lit='false')), (1, k_block('crafting_table')), (0.4, k_chest('castle_quarters')), (1, k_candle), (1, k_pot)],
    'smithy': [(2, k_anvil), (1, k_facing('blast_furnace', lit='false')), (1, k_block('smithing_table')), (1, k_facing('grindstone', face='floor')),
               (2, k_barrel), (1, k_chest('castle_armoury')), (1, k_block('cauldron')), (1, k_frame(['iron_sword', 'iron_axe', 'shield']))],
    'guest': [(2, k_bed(['white', 'light_blue', 'blue', 'green'])), (2, k_barrel), (0.5, k_chest(None)), (1, k_plant), (1, k_candle), (1, k_banner(['blue', 'green', 'white'])), (1, k_shelf)],
    'corridor': [(3, k_banner(['red', 'yellow', 'blue'])), (1, k_candelabra), (1, k_barrel), (0.3, k_stand(('chainmail', 'iron', 'iron', 'iron'), [None, 'iron_sword'])), (2, k_web), (1, k_plant)],
    'lookout': [(2, k_barrel), (1, k_stand((None, 'leather', 'chainmail', 'iron'), ['bow', 'crossbow'])), (1, k_hay), (1, k_frame(['bow', 'crossbow', 'spyglass']))],
    'crypt': [(2, k_skull), (2, k_candle), (1, k_pot), (1, k_web), (1, k_barrel)],
}

CHAPELS = [0]
def furnish_generic(rid):
    r = Room(None, f'room {rid}', rid=rid)
    if len(r.floor) < 10: return
    w = min(r.x1 - r.x0, r.z1 - r.z0) + 1; area = len(r.floor)
    ys = list(r.floor.values()); y = sorted(ys)[len(ys) // 2]
    ceil = sorted(r.ceil(x, z) - r.floor[(x, z)] for (x, z) in r.floor)[len(r.floor) // 2]
    if w <= 3: theme = 'corridor'
    elif y <= 4: theme = R.choice(['storeroom', 'storeroom', 'mess', 'smithy', 'crypt'])
    elif ceil <= 3: theme = R.choice(['storeroom', 'lookout'])
    elif y >= 60: theme = R.choice(['guest', 'study', 'guest', 'chapel' if CHAPELS[0] < 2 else 'study'])
    elif y >= 40: theme = R.choice(['study', 'guest', 'barracks', 'chapel' if CHAPELS[0] < 2 else 'mess'])
    else: theme = R.choice(['barracks', 'storeroom', 'smithy', 'mess', 'barracks'])
    if theme == 'chapel': CHAPELS[0] += 1
    r.title += f' ({theme})'
    print(f'   -> {theme}')
    r.clear()
    wall_fill(r, KITS[theme], 0.45 if theme == 'corridor' else 0.8)
    inner = [c for c in r.inner(1) if r.free(*c)]
    R.shuffle(inner)
    if theme == 'chapel':
        xs = sorted({x for (x, z) in r.floor})
        alt = xs[0] + 1
        for (x, z) in sorted(r.floor):
            if x == alt and r.free(x, z) and abs(z - r.cz) <= 1:
                place(x, r.y(x, z), z, 'chiseled_stone_bricks'); place(x, r.y(x, z) + 1, z, 'white_carpet'); candle(x, r.y(x, z) + 2, z, 'white'); r.take(x, z)
            elif x > alt + 1 and (x - alt) % 2 == 0 and r.free(x, z) and abs(z - r.cz) >= 1 and R.random() < 0.85:
                place(x, r.y(x, z), z, 'spruce_stairs', facing='east', half='bottom', shape='straight', waterlogged='false'); r.take(x, z)
    elif theme == 'mess' and area >= 30:
        long_table(r, axis_x=(r.x1 - r.x0) >= (r.z1 - r.z0))
    elif theme in ('study', 'guest', 'barracks', 'smithy') and inner:
        if theme in ('study', 'guest'): rug(r, R.choice(['red', 'blue', 'green', 'brown', 'cyan']))
        n = max(1, area // 30)
        for (x, z) in inner[:n]: table_set(r, x, z, candles=0.6)
    elif theme == 'storeroom':
        for (x, z) in inner[: max(1, area // 18)]:
            y0 = r.y(x, z)
            for (dx, dz) in ((0, 0), (1, 0), (0, 1)):
                if r.free(x + dx, z + dz) and R.random() < 0.8: (barrel if R.random() < 0.6 else (lambda a, b, c: place(a, b, c, 'hay_block', axis='y')))(x + dx, y0, z + dz); r.take(x + dx, z + dz)
    if theme != 'corridor' and area >= 20:
        cx, cz = round(r.cx), round(r.cz)
        if (cx, cz) in r.floor and r.free(cx, cz): chandelier(cx, r.y(cx, cz), cz, r.ceil(cx, cz), kind='lantern' if R.random() < 0.6 else 'candle')
    elif theme == 'corridor':
        cells = sorted(r.floor)
        for i, (x, z) in enumerate(cells):
            if i % 9 == 4 and r.free(x, z): chandelier(x, r.y(x, z), z, r.ceil(x, z), kind='lantern')
    r.ruin(moss=0.10, webs=0.6, leaves=1 if area > 30 else 0)

def k_gold(r, x, y, z, d):
    place(x, y, z, R.choice(['gold_block', 'raw_gold_block', 'raw_gold_block']))
    if R.random() < 0.4: place(x, y + 1, z, 'raw_gold_block')
TOPUP = {
    'throne hall': ([(3, k_candelabra), (1, k_plant), (2, k_banner(['red', 'yellow']))], 0.55),
    'round table': ([(2, k_stand(('chainmail', 'iron', 'iron', 'iron'), [None, 'iron_sword'])), (2, k_candelabra), (1, k_chest(None)), (1, k_plant)], 0.6),
    'library': ([(1, k_lectern), (2, k_candelabra), (1, k_plant)], 0.6),
    "the king's chamber": ([(2, k_shelf), (2, k_candelabra), (1, k_plant), (1, k_barrel), (1, k_stand(('golden', 'iron', 'golden', 'golden'), [None]))], 0.75),
    "Mordred's chamber": ([(2, k_shelf), (2, k_candle), (1, k_skull), (1, k_barrel), (1, k_web)], 0.75),
    "the princess's tower": ([(3, k_plant), (2, k_candle), (1, k_block('loom')), (1, k_shelf), (1, k_barrel)], 0.8),
    'treasury': ([(3, k_gold), (1, k_barrel), (1, k_pot)], 0.7),
    'servants\' hall (kitchen)': (KITS['mess'], 0.8),
    'servants\' dormitory': (KITS['storeroom'], 0.6),
}
def rug(r, colour, size=None):
    """A rug in the middle of the room, with a darker border; worn through here and there."""
    cx, cz = round(r.cx), round(r.cz)
    hx = size or max(1, min(3, (r.x1 - r.x0) // 4)); hz = size or max(1, min(3, (r.z1 - r.z0) // 4))
    for x in range(cx - hx, cx + hx + 1):
        for z in range(cz - hz, cz + hz + 1):
            if r.free(x, z) and r.y(x, z) == r.y(cx, cz) if (cx, cz) in r.floor else False:
                if R.random() < 0.88: place(x, r.y(x, z), z, (colour if abs(x - cx) < hx and abs(z - cz) < hz else 'brown') + '_carpet')

def top_up(r):
    """More in the named rooms: the walls of each filled with things that belong there."""
    kit, dens = TOPUP.get(r.title, (KITS['barracks'], 0.7) if 'guard' in r.title else (None, 0))
    if kit: wall_fill(r, kit, dens)
    if r.title in ("the king's chamber", "Mordred's chamber", "the princess's tower"):
        inner = [c for c in r.inner(1) if r.free(*c)]
        R.shuffle(inner)
        for (x, z) in inner[:1]: table_set(r, x, z, slab='dark_oak_slab', chair='dark_oak_stairs', candles=0.9)
        for (x, z) in inner[1:3]:
            if r.free(x, z): place(x, r.y(x, z), z, R.choice(['potted_azalea_bush', 'flower_pot', 'potted_fern'])); r.take(x, z)
    if r.title == 'throne hall':                                          # benches either side of the runner, facing it
        for (x, z), y in sorted(r.floor.items()):
            if abs(x - 85) in (4, 5) and z % 2 == 0 and z > 70 and r.free(x, z) and R.random() < 0.85:
                place(x, y, z, 'dark_oak_stairs', facing='west' if x > 85 else 'east', half='bottom', shape='straight', waterlogged='false'); r.take(x, z)
    if r.title == 'throne hall':                                          # candelabras along the runner
        for (x, z), y in sorted(r.floor.items()):
            if abs(x - 85) == 3 and z % 3 == 0 and r.free(x, z): candelabra(x, y, z); r.take(x, z)

# ------------------------------------------------------------------
throne_hall()
round_table()
library()
chamber((86, 72, 75), "the king's chamber", 'red', 'red', 'yellow', 'yellow', king_extras, desk_book=30)
chamber((86, 78, 75), "Mordred's chamber", 'black', 'gray', 'black', 'soul', mordred_extras)
chamber((86, 88, 75), "the princess's tower", 'pink', 'pink', 'pink', 'pink', princess_extras)
treasury()
for at, title in (((165, 33, 32), 'guard room NE'), ((10, 33, 32), 'guard room NW'), ((166, 33, 122), 'guard room SE'),
                  ((11, 33, 120), 'guard room SW'), ((90, 28, 142), 'gatehouse guard room')):
    guard_room(at, title)
kitchen()
dormitory()
archery()
sizes = ndimage.sum(lab > 0, lab, range(1, lab.max() + 1))
for rid in range(1, lab.max() + 1):
    if rid in DONE or sizes[rid - 1] < 12: continue
    furnish_generic(rid)
t.save(str(OUT))
print(stats)
