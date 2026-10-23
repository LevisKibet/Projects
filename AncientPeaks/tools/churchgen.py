"""Procedural churches for Ancient Peaks (1.20.1 structure .nbt), interiors included.

    python3 churchgen.py OUTDIR      -> church_parish.nbt, church_ruined_abbey.nbt, church_besieged_chapel.nbt
"""
import sys, json, math, pathlib, collections, random
HERE = pathlib.Path(__file__).resolve().parent
_src = (HERE.parent / "gen_structures.py").read_text()
exec(_src[:_src.index("# ------------------------------------------------------------------ build")])
def B_(v): return Tag(1, v)

# ------------------------------------------------------------------ tiny voxel builder
class Build:
    def __init__(s, seed):
        s.b = {}; s.r = random.Random(seed)
    def set(s, x, y, z, n, nbt=None, **p):
        s.b[(x, y, z)] = (n, {k: str(v).lower() for k, v in p.items()}, nbt)
    def get(s, x, y, z):
        e = s.b.get((x, y, z)); return e[0] if e else None
    def fill(s, x0, y0, z0, x1, y1, z1, n, **p):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    s.set(x, y, z, n(x, y, z) if callable(n) else n, **p)
    def air(s, x0, y0, z0, x1, y1, z1): s.fill(x0, y0, z0, x1, y1, z1, "air")
    def pick(s, table):
        t = s.r.random() * sum(w for _, w in table)
        for n, w in table:
            t -= w
            if t <= 0: return n
        return table[-1][0]

SOLIDLESS = ("air", "stairs", "slab", "fence", "pane", "iron_bars", "wall", "carpet", "candle", "lantern", "torch", "door", "trapdoor",
             "vine", "chain", "ladder", "bell", "lectern", "bed", "pot", "skull", "cobweb", "button", "lever", "rod", "glass_pane", "water", "fern", "grass", "bush", "flower")
def full(n):
    if n is None: return False
    if n.endswith(("_block", "planks", "bricks", "_log", "_wood", "leaves", "tiles")) and "slab" not in n and "stairs" not in n: return True
    return not any(k in n for k in SOLIDLESS)
def connects(n, kind):
    if n is None: return False
    if full(n): return True
    if kind == "fence": return n.endswith("fence") or n.endswith("fence_gate")
    if kind == "wall": return n.endswith("_wall") or n == "iron_bars" or n.endswith("pane")
    if kind == "pane": return n.endswith("pane") or n == "iron_bars" or n.endswith("_wall")
    return False

def finish(bd):
    """Connect fences, walls, panes and bars to their neighbours (templates are placed without shape updates)."""
    D = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
    for (x, y, z), (n, p, nbt) in list(bd.b.items()):
        kind = "fence" if n.endswith("fence") else "wall" if n.endswith("_wall") else "pane" if (n.endswith("pane") or n == "iron_bars") else None
        if not kind: continue
        q = dict(p)
        con = {d: connects(bd.get(x + dx, y, z + dz), kind) for d, (dx, _, dz) in D.items()}
        if kind == "wall":
            above = bd.get(x, y + 1, z)
            for d, c in con.items(): q[d] = ("tall" if above and full(above) else "low") if c else "none"
            straight = (con["north"] and con["south"] and not con["east"] and not con["west"]) or (con["east"] and con["west"] and not con["north"] and not con["south"])
            q["up"] = "false" if straight and not (above and not full(above) and above != "air") else "true"
        else:
            for d, c in con.items(): q[d] = "true" if c else "false"
        q["waterlogged"] = "false"
        bd.b[(x, y, z)] = (n, q, nbt)

def save(bd, path):
    finish(bd)
    xs = [k[0] for k in bd.b]; ys = [k[1] for k in bd.b]; zs = [k[2] for k in bd.b]
    mx, my, mz = min(xs), min(ys), min(zs)
    size = (max(xs) - mx + 1, max(ys) - my + 1, max(zs) - mz + 1)
    blocks = [(x - mx, y - my, z - mz, "minecraft:" + n, p, nbt) for (x, y, z), (n, p, nbt) in sorted(bd.b.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0]))]
    open(path, "wb").write(template(size, blocks))
    c = collections.Counter(n for n, _, _ in bd.b.values())
    print(f"{pathlib.Path(path).name}: {size[0]}x{size[1]}x{size[2]} (w x h x d), {sum(v for k, v in c.items() if k != 'air')} blocks, ground floor at y={-my}")
    return -my

# ------------------------------------------------------------------ palettes and shared pieces
# rustic grey fieldstone: mostly rough stone and cobble with some dressed brick, never white
WALL = [("stone_bricks", 24), ("cobblestone", 22), ("cracked_stone_bricks", 10), ("mossy_cobblestone", 8), ("mossy_stone_bricks", 8),
        ("andesite", 10), ("tuff", 10), ("stone", 8)]
DARK = [("deepslate_bricks", 55), ("cracked_deepslate_bricks", 15), ("cobbled_deepslate", 12), ("deepslate_tiles", 8), ("polished_blackstone_bricks", 6)]
TRIM = [("polished_andesite", 1)]
GLASS = ["light_blue_stained_glass_pane", "blue_stained_glass_pane", "red_stained_glass_pane", "yellow_stained_glass_pane",
         "purple_stained_glass_pane", "white_stained_glass_pane", "lime_stained_glass_pane", "orange_stained_glass_pane"]

def chest(bd, x, y, z, facing, loot="minecraft:chests/simple_dungeon"):
    bd.set(x, y, z, "chest", nbt=C({"id": S("minecraft:chest"), "LootTable": S(loot)}), facing=facing, type="single", waterlogged="false")

def book_nbt(title, pages):
    return C({"id": S("minecraft:written_book"), "Count": B_(1), "tag": C({
        "title": S(title), "author": S("Unknown Hand"), "resolved": B_(1),
        "pages": L(8, [S(json.dumps({"text": p})) for p in pages])})})

def lectern(bd, x, y, z, facing, title, pages):
    bd.set(x, y, z, "lectern", nbt=C({"id": S("minecraft:lectern"), "Book": book_nbt(title, pages), "Page": Tag(3, 0)}),
           facing=facing, has_book="true", powered="false")

def lancet(bd, axis, fixed, c, y0, h, glass_choice, broken=0.0, depth=(0,), sill=None):
    """A 3-wide pointed window in a wall. axis='x': wall runs along x at z=fixed; c = window centre along the wall."""
    for d in depth:
        for k in (-1, 0, 1):
            for y in range(y0, y0 + h):
                if k != 0 and y == y0 + h - 1: continue                     # pointed top: only the middle on the last row
                pos = (c + k, y, fixed + d) if axis == "x" else (fixed + d, y, c + k)
                if d == depth[0]:
                    bd.set(*pos, "air" if bd.r.random() < broken else glass_choice)
                else:
                    bd.set(*pos, "air")
    if sill:
        out, face = sill
        for k in (-1, 0, 1):
            pos = (c + k, y0 - 1, fixed + out) if axis == "x" else (fixed + out, y0 - 1, c + k)
            bd.set(*pos, "stone_brick_stairs", facing=face, half="top", shape="straight", waterlogged="false")

def gable_roof(bd, x0, x1, z0, z1, eave_y, mat="deepslate_tile", ridge_block="deepslate_tiles", holes=0.0, gable_fill=None):
    """Roof along x: slopes rise from both long sides (z0-1 and z1+1) to the ridge."""
    zc = (z0 + z1) / 2
    half = int(math.ceil((z1 - z0 + 2) / 2))
    for x in range(x0 - 1, x1 + 2):
        for d in range(half + 1):
            y = eave_y + d
            zn, zs = z0 - 1 + d, z1 + 1 - d
            if zn > zs: break
            if zn == zs:
                if bd.r.random() >= holes: bd.set(x, y, zn, ridge_block)
                bd.set(x, y + 1, zn, mat + "_slab", type="bottom", waterlogged="false") if bd.r.random() >= holes else None
                break
            for zz, face in ((zn, "south"), (zs, "north")):
                if bd.r.random() < holes: continue
                bd.set(x, y, zz, mat + "_stairs", facing=face, half="bottom", shape="straight", waterlogged="false")
            if zs - zn == 1:                                                  # even width: ridge cap of slabs
                bd.set(x, y + 1, zn, mat + "_slab", type="bottom", waterlogged="false"); bd.set(x, y + 1, zs, mat + "_slab", type="bottom", waterlogged="false")
                break
    if gable_fill:
        for x, inner in gable_fill:                                           # triangles closing the ends
            for d in range(1, half + 1):
                y = eave_y + d
                for z in range(z0 - 1 + d + 1, z1 + 1 - d):
                    bd.set(x, y, z, bd.pick(WALL) if inner is None else inner)

def buttress(bd, x, z_wall, out, top_y, face, mat=WALL):
    """Stepped buttress against a wall: two blocks deep, capped with stairs."""
    for y in range(0, top_y + 1): bd.set(x, y, z_wall + out, bd.pick(mat))
    for y in range(0, top_y - 4): bd.set(x, y, z_wall + 2 * out, bd.pick(mat))
    bd.set(x, top_y - 4, z_wall + 2 * out, "stone_brick_stairs", facing=face, half="bottom", shape="straight", waterlogged="false")
    bd.set(x, top_y + 1, z_wall + out, "stone_brick_stairs", facing=face, half="bottom", shape="straight", waterlogged="false")

def overgrow(bd, vines=0.25, moss=0.12, leaves=12, cobwebs=10, interior_box=None):
    r = bd.r
    D = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
    # weathering swaps
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "stone_bricks" and r.random() < moss: bd.b[k] = ("mossy_stone_bricks", p, nbt)
        elif n == "cobblestone" and r.random() < moss: bd.b[k] = ("mossy_cobblestone", p, nbt)
        elif n == "stone_brick_stairs" and r.random() < moss * 0.8: bd.b[k] = ("mossy_stone_brick_stairs", p, nbt)
    # vines down outer wall faces
    walls = [(k, n) for k, (n, p, nbt) in bd.b.items() if full(n)]
    for (x, y, z), n in walls:
        if r.random() > vines * 0.08: continue
        for face, (dx, _, dz) in D.items():
            vx, vz = x - dx, z - dz                                    # the air cell in front of this face
            if bd.get(vx, y, vz) is not None: continue
            if interior_box and interior_box(vx, y, vz): continue
            for j in range(r.randint(2, 9)):
                yy = y - j
                if yy < 1 or bd.get(vx, yy, vz) is not None or not full(bd.get(x, yy, z)): break
                bd.set(vx, yy, vz, "vine", **{f: ("true" if f == face else "false") for f in D}, up="false")
            break
    # leaf bushes at the foot of the walls
    base = [(x, z) for (x, y, z), (n, _, _) in bd.b.items() if y == 0 and full(n)]
    for _ in range(leaves):
        x, z = r.choice(base)
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                for dy in (1, 2):
                    if bd.get(x + dx, dy, z + dz) is None and r.random() < (0.8 if dy == 1 else 0.35):
                        bd.set(x + dx, dy, z + dz, r.choice(["oak_leaves", "azalea_leaves", "dark_oak_leaves"]), persistent="true", distance="1", waterlogged="false")

def cobweb_corners(bd, pts):
    for p in pts:
        if bd.get(*p) in (None, "air"): bd.set(*p, "cobweb")

# ------------------------------------------------------------------ 1. the parish church (the original, rebuilt in detail)
def parish(seed=1):
    bd = Build(seed); r = bd.r
    W = lambda x, y, z: bd.pick(WALL)
    NX0, NX1, NZ0, NZ1, WH = 9, 36, 0, 14, 12                               # nave
    # foundation and floor
    bd.fill(-1, -1, -2, 44, -1, 16, lambda x, y, z: bd.pick([("cobblestone", 3), ("stone", 2), ("mossy_cobblestone", 1)]))
    # nave walls
    for x in range(NX0, NX1 + 1):
        for y in range(0, WH + 1):
            bd.set(x, y, NZ0, W(x, y, NZ0)); bd.set(x, y, NZ1, W(x, y, NZ1))
    for z in range(NZ0, NZ1 + 1):
        for y in range(0, WH + 1): bd.set(NX1, y, z, W(NX1, y, z)); bd.set(NX0, y, z, W(NX0, y, z))
    bd.air(NX0 + 1, 1, NZ0 + 1, NX1 - 1, WH + 8, NZ1 - 1)
    # floor: polished andesite with a stone brick border and a red runner up the aisle
    for x in range(NX0 + 1, NX1):
        for z in range(NZ0 + 1, NZ1):
            bd.set(x, 0, z, "stone_bricks" if z in (NZ0 + 1, NZ1 - 1) else ("polished_andesite" if (x + z) % 2 else "andesite"))
    for x in range(NX0 + 1, NX1 - 3): bd.set(x, 1, 7, "red_carpet")
    # string course and plinth
    for x in range(NX0, NX1 + 1):
        for z in (NZ0 - 1, NZ1 + 1):
            bd.set(x, 0, z, "stone_brick_stairs", facing="south" if z < 7 else "north", half="bottom", shape="straight", waterlogged="false")
    # buttresses and pointed windows
    for bx in (10, 16, 22, 28, 34):
        buttress(bd, bx, NZ0, -1, 9, "south"); buttress(bd, bx, NZ1, 1, 9, "north")
    for wx in (13, 19, 25, 31):
        g = r.choice(GLASS)
        lancet(bd, "x", NZ0, wx, 4, 7, g, broken=0.2, sill=(-1, "north"))
        lancet(bd, "x", NZ1, wx, 4, 7, r.choice(GLASS), broken=0.2, sill=(1, "south"))
    # roof with timber trusses, a few slipped slates
    gable_roof(bd, NX0, NX1, NZ0, NZ1, WH + 1, holes=0.05, gable_fill=[(NX1, None), (NX0, None)])
    for x in range(NX0 + 3, NX1, 6):
        for z in range(NZ0 + 1, NZ1): bd.set(x, WH, z, "stripped_dark_oak_log", axis="z")
        bd.set(x, WH + 1, 7, "dark_oak_fence"); bd.set(x, WH + 2, 7, "dark_oak_fence")
    # rose window in the east gable, above the chancel roof
    for dz in range(-2, 3):
        for dy in range(-2, 3):
            if dz * dz + dy * dy <= 4: bd.set(NX1, 18 + dy, 7 + dz, "red_stained_glass_pane" if dz == 0 and dy == 0 else ("yellow_stained_glass_pane" if abs(dz) + abs(dy) == 1 else "blue_stained_glass_pane"))
    # pews (spruce stairs facing the altar), with end panels
    for x in range(12, 32, 2):
        for z in list(range(2, 6)) + list(range(9, 13)):
            if r.random() < 0.08: continue                                   # a few missing
            bd.set(x, 1, z, "spruce_stairs", facing="west", half="bottom", shape="straight", waterlogged="false")
        for z in (1, 6, 8, 13):
            bd.set(x, 1, z, "spruce_trapdoor", facing="north" if z in (1, 8) else "south", half="bottom", open="true", powered="false", waterlogged="false")
    # chandeliers on chains from the ridge
    for x in (15, 23, 31):
        for y in range(15, 21): bd.set(x, y, 7, "chain", axis="y", waterlogged="false")
        bd.set(x, 14, 7, "lantern", hanging="true", waterlogged="false")
    # baptismal font and a dusty shelf by the door
    bd.set(11, 1, 3, "water_cauldron", level="3"); bd.set(11, 1, 11, "bookshelf"); bd.set(11, 2, 11, "bookshelf")
    bd.set(11, 1, 12, "barrel", facing="up", open="false")
    # ---- chancel (narrower, lower) with the altar
    CX0, CX1, CZ0, CZ1, CH = 37, 43, 3, 11, 9
    for x in range(CX0, CX1 + 1):
        for y in range(0, CH + 1): bd.set(x, y, CZ0, W(x, y, CZ0)); bd.set(x, y, CZ1, W(x, y, CZ1))
    for z in range(CZ0, CZ1 + 1):
        for y in range(0, CH + 1): bd.set(CX1, y, z, W(CX1, y, z))
    bd.air(CX0, 1, CZ0 + 1, CX1 - 1, CH + 5, CZ1 - 1)
    for x in range(CX0 - 1, CX1):                                           # raised floor
        for z in range(CZ0 + 1, CZ1): bd.set(x, 1, z, "polished_deepslate" if (x + z) % 2 else "deepslate_tiles")
    for z in range(CZ0 + 1, CZ1): bd.set(CX0 - 2, 1, z, "polished_andesite_stairs", facing="east", half="bottom", shape="straight", waterlogged="false")
    gable_roof(bd, CX0 + 1, CX1, CZ0, CZ1, CH + 1, holes=0.04, gable_fill=[(CX1, None)])
    # chancel arch through the nave's east wall
    for z in range(4, 11):
        for y in range(1, 10):
            if y == 9 and z not in (6, 7, 8): continue
            if y == 8 and z in (4, 10): continue
            bd.set(NX1, y, z, "air" if y > 1 else "polished_deepslate")
    # triple lancet east window
    for c, g in ((5, "blue_stained_glass_pane"), (7, "red_stained_glass_pane"), (9, "blue_stained_glass_pane")):
        for y in range(3, 9 if c == 7 else 8): bd.set(CX1, y, c, g)
    # altar, candles, lectern, the vestry chest
    for z in (6, 7, 8): bd.set(41, 2, z, "chiseled_stone_bricks"); bd.set(41, 3, z, "white_carpet")
    bd.set(41, 4, 6, "candle", candles="3", lit="false", waterlogged="false"); bd.set(41, 4, 8, "candle", candles="3", lit="false", waterlogged="false")
    bd.set(41, 4, 7, "candle", candles="1", lit="false", waterlogged="false")
    bd.set(42, 2, 7, "gold_block"); bd.set(42, 3, 7, "lightning_rod", facing="up", powered="false", waterlogged="false")  # a gilded cross-staff
    lectern(bd, 38, 2, 9, "west", "Of the Island Beyond the Grey Sea",
            ["They say that west of the last coast, past the grey water, there lies a land of high stone and old kings.",
             "The pilgrims who sailed for it carried a compass that pointed nowhere on this earth.",
             "None came back. Only the hermit who keeps the chapel on the hill still speaks of them."])
    chest(bd, 42, 2, 4, "south")
    bd.set(42, 2, 10, "barrel", facing="up", open="false"); bd.set(39, 2, 4, "candle", candles="2", lit="false", waterlogged="false")
    # ---- west tower with belfry and spire
    TX0, TX1, TZ0, TZ1, TH = 0, 8, 3, 11, 30
    for x in range(TX0, TX1 + 1):
        for z in range(TZ0, TZ1 + 1):
            edge = x in (TX0, TX1) or z in (TZ0, TZ1)
            for y in range(0, TH + 1):
                if edge:
                    quoin = (x in (TX0, TX1) and z in (TZ0, TZ1))
                    bd.set(x, y, z, "polished_andesite" if quoin and y % 4 else ("deepslate_bricks" if 21 <= y <= 22 else W(x, y, z)))
                else:
                    bd.set(x, y, z, "air")
            bd.set(x, -1, z, "stone_bricks")
    for x in range(TX0 + 1, TX1):
        for z in range(TZ0 + 1, TZ1): bd.set(x, 0, z, "stone_bricks" if (x + z) % 3 else "chiseled_stone_bricks")
    for y in (10, 20):                                                      # wooden floors with a ladder well
        for x in range(TX0 + 1, TX1):
            for z in range(TZ0 + 1, TZ1): bd.set(x, y, z, "spruce_planks")
    for y in range(1, 21): bd.set(TX1 - 1, y, TZ0 + 1, "ladder", facing="south", waterlogged="false")
    for y in (10, 20): bd.set(TX1 - 1, y, TZ0 + 1, "ladder", facing="south", waterlogged="false")
    # west door (an open pointed arch) and the tower arch into the nave
    for z in range(6, 9):
        for y in range(1, 6):
            if y == 5 and z != 7: continue
            bd.set(TX0, y, z, "air"); bd.set(TX1, y, z, "air"); bd.set(NX0, y, z, "air")
    bd.set(TX0 - 1, 0, 6, "stone_brick_stairs", facing="east", half="bottom", shape="straight", waterlogged="false")
    bd.set(TX0 - 1, 0, 7, "stone_brick_stairs", facing="east", half="bottom", shape="straight", waterlogged="false")
    bd.set(TX0 - 1, 0, 8, "stone_brick_stairs", facing="east", half="bottom", shape="straight", waterlogged="false")
    for z in (5, 9): bd.set(TX0 - 1, 4, z, "lantern", hanging="false", waterlogged="false")
    # belfry openings on every face, a bell under a beam
    for c in (4, 7):
        pass
    for y in range(23, 28):
        for k in (-1, 0, 1):
            if y == 27 and k != 0: continue
            bd.set(TX0, y, 7 + k, "air"); bd.set(TX1, y, 7 + k, "air"); bd.set(4 + k, y, TZ0, "air"); bd.set(4 + k, y, TZ1, "air")
    for z in (TZ0, TZ1):
        for k in (-1, 0, 1): bd.set(4 + k, 22, z, "stone_brick_slab", type="top", waterlogged="false")
    for x in range(TX0 + 1, TX1): bd.set(x, 28, 7, "dark_oak_log", axis="x")
    bd.set(4, 27, 7, "bell", attachment="ceiling", facing="north", powered="false")
    # parapet with corner pinnacles, then the spire
    for x in range(TX0, TX1 + 1):
        for z in range(TZ0, TZ1 + 1):
            if x in (TX0, TX1) or z in (TZ0, TZ1):
                if (x + z) % 2 == 0: bd.set(x, TH + 1, z, "stone_brick_wall")
    for x, z in ((TX0, TZ0), (TX0, TZ1), (TX1, TZ0), (TX1, TZ1)):
        bd.set(x, TH + 1, z, "stone_bricks"); bd.set(x, TH + 2, z, "stone_brick_wall"); bd.set(x, TH + 3, z, "stone_brick_wall")
    lo, hi, y = 1, 7, TH + 1
    while hi - lo >= 0:
        for x in range(lo, hi + 1):
            for zz in range(TZ0 + lo, TZ0 + hi + 1):
                edge = x in (lo, hi) or zz in (TZ0 + lo, TZ0 + hi)
                if not edge: bd.set(x, y, zz, "deepslate_tiles"); continue
                face = "east" if x == lo else "west" if x == hi else "south" if zz == TZ0 + lo else "north"
                corner = x in (lo, hi) and zz in (TZ0 + lo, TZ0 + hi)
                bd.set(x, y, zz, "deepslate_tiles" if corner else "deepslate_tile_stairs", **({} if corner else dict(facing=face, half="bottom", shape="straight", waterlogged="false")))
        for x in range(lo, hi + 1):
            for zz in range(TZ0 + lo, TZ0 + hi + 1): bd.set(x, y + 1, zz, "deepslate_tiles")
        y += 2; lo += 1; hi -= 1
    bd.set(4, y, 7, "deepslate_tile_wall"); bd.set(4, y + 1, 7, "lightning_rod", facing="up", powered="false", waterlogged="false")
    bd.set(4, 21, 7, "chest", nbt=C({"id": S("minecraft:chest"), "LootTable": S("minecraft:chests/simple_dungeon")}), facing="south", type="single", waterlogged="false")
    cobweb_corners(bd, [(1, 19, 4), (7, 19, 10), (1, 29, 10), (10, 11, 1), (35, 11, 13), (38, 8, 4)])
    overgrow(bd, vines=0.35, moss=0.10, leaves=10,
             interior_box=lambda x, y, z: (NX0 < x < NX1 and NZ0 < z < NZ1) or (TX0 < x < TX1 and TZ0 < z < TZ1) or (CX0 <= x < CX1 and CZ0 < z < CZ1))
    return bd

# ------------------------------------------------------------------ 2. the ruined abbey: roofless arcades, a cloister and a crypt
def abbey(seed=2):
    bd = Build(seed); r = bd.r
    W = lambda x, y, z: bd.pick(WALL)
    X0, X1, Z0, Z1 = 0, 44, 0, 20                                        # nave with side aisles
    TR0, TR1 = 30, 38                                                     # transept (x range), arms out to z=-8 and z=28
    def crumble(base, x, z, amp=6):                                       # ruined wall top
        return base - int(abs(math.sin(x * 0.37 + z * 0.23) * amp + r.random() * 3))
    # ground: flagstones, cracked and overgrown
    for x in range(-2, 47):
        for z in range(-10, 46):
            bd.set(x, -1, z, bd.pick([("stone_bricks", 4), ("cracked_stone_bricks", 2), ("mossy_stone_bricks", 3), ("cobblestone", 1), ("moss_block", 2), ("coarse_dirt", 1)]))
    # outer walls (nave + transept) with crumbling tops and tall window gaps
    def wall_line(pts, top):
        for (x, z) in pts:
            h = crumble(top, x, z)
            for y in range(0, max(2, h)): bd.set(x, y, z, W(x, y, z))
    north = [(x, Z0) for x in range(X0, X1 + 1) if not TR0 < x < TR1]
    south = [(x, Z1) for x in range(X0, X1 + 1) if not TR0 < x < TR1]
    wall_line(north + south, 16)
    wall_line([(X0, z) for z in range(Z0, Z1 + 1)], 20)                  # west front stands tallest
    wall_line([(X1, z) for z in range(Z0, Z1 + 1)], 18)
    wall_line([(x, -8) for x in range(TR0, TR1 + 1)] + [(x, 28) for x in range(TR0, TR1 + 1)], 15)
    wall_line([(TR0, z) for z in list(range(-8, Z0)) + list(range(Z1, 29))] + [(TR1, z) for z in list(range(-8, Z0)) + list(range(Z1, 29))], 14)
    # buttresses on the long walls
    for bx in range(4, X1, 6):
        if TR0 - 1 <= bx <= TR1 + 1: continue
        buttress(bd, bx, Z0, -1, 11, "south"); buttress(bd, bx, Z1, 1, 11, "north")
    # tall pointed window openings (glass long gone, a few shards remain)
    for wx in range(7, X1 - 2, 6):
        if TR0 - 2 <= wx <= TR1 + 2: continue
        for z, out, face in ((Z0, -1, "north"), (Z1, 1, "south")):
            lancet(bd, "x", z, wx, 3, 9, r.choice(GLASS), broken=0.85, sill=(out, face))
    # great west door and west window
    for z in range(8, 13):
        for y in range(0, 8):
            if y >= 6 and z in (8, 12): continue
            if y == 7 and z != 10: continue
            bd.set(X0, y, z, "air")
    for y in range(10, 17):
        for k in (-1, 0, 1):
            if y == 16 and k: continue
            bd.set(X0, y, 10 + k, "air")
    # interior arcades: pillars and pointed arches between nave and aisles
    for z in (6, 14):
        for x in range(3, X1 - 2, 5):
            if TR0 - 1 <= x <= TR1 + 1: continue
            h = crumble(13, x, z, 5)
            for y in range(0, h): bd.set(x, y, z, "stone_bricks" if y % 5 else "chiseled_stone_bricks")
            if h >= 9:                                                   # arch springing to the next pillar
                for dx, dy in ((1, 8), (2, 9), (3, 9), (4, 8)):
                    if x + dx < X1 - 1 and r.random() < 0.75:
                        bd.set(x + dx, dy, z, "stone_bricks")
                bd.set(x + 1, 7, z, "stone_brick_stairs", facing="west", half="top", shape="straight", waterlogged="false")
                bd.set(x + 4, 7, z, "stone_brick_stairs", facing="east", half="top", shape="straight", waterlogged="false")
    # nave floor details: fallen pews, a toppled column, rubble
    for x in range(4, 28, 3):
        for z in (8, 9, 11, 12):
            if r.random() < 0.45: bd.set(x, 0, z, "spruce_stairs", facing=r.choice(["west", "north", "south"]), half="bottom", shape="straight", waterlogged="false")
            elif r.random() < 0.2: bd.set(x, 0, z, "spruce_slab", type="bottom", waterlogged="false")
    for x in range(18, 25): bd.set(x, 0, 16, "stone_bricks" if x % 3 else "chiseled_stone_bricks")      # a fallen pillar
    for _ in range(40):
        x, z = r.randint(2, X1 - 2), r.randint(1, Z1 - 1)
        bd.set(x, 0, z, r.choice(["cobblestone", "mossy_cobblestone", "stone_brick_slab", "cobblestone_slab", "moss_carpet", "mossy_stone_brick_stairs"]),
               **({"type": "bottom", "waterlogged": "false"} if False else {}))
    for k, (n, p, nbt) in list(bd.b.items()):                            # slabs/stairs dropped as rubble need their states
        if n.endswith("_slab") and not p: bd.b[k] = (n, {"type": "bottom", "waterlogged": "false"}, nbt)
        if n.endswith("_stairs") and not p: bd.b[k] = (n, {"facing": "north", "half": "bottom", "shape": "straight", "waterlogged": "false"}, nbt)
    # high altar at the east end, open to the sky
    for z in range(8, 13): bd.set(42, 0, z, "polished_deepslate_stairs", facing="west", half="bottom", shape="straight", waterlogged="false")
    for z in (9, 10, 11): bd.set(43, 0, z, "polished_deepslate"); bd.set(43, 1, z, "chiseled_stone_bricks")
    bd.set(43, 2, 9, "candle", candles="2", lit="false", waterlogged="false"); bd.set(43, 2, 11, "candle", candles="4", lit="false", waterlogged="false")
    bd.set(43, 2, 10, "skeleton_skull", rotation="12")
    lectern(bd, 41, 0, 13, "west", "Abbey Chronicle, last leaf",
            ["The ships did not return in the spring.", "Brother Aldwin swears the light on the western sea is a gate, not a star.",
             "The old hermit at the chapel keeps the knights' map. He will not say where it leads."])
    # cloister to the south: arcaded walks round a garth with a well
    CX0, CX1, CZ0, CZ1 = 6, 28, Z1 + 1, 44
    for x in range(CX0, CX1 + 1):
        for z in range(CZ0, CZ1 + 1):
            inner = CX0 + 4 <= x <= CX1 - 4 and CZ0 + 4 <= z <= CZ1 - 4
            bd.set(x, -1, z, "grass_block" if inner else bd.pick([("stone_bricks", 3), ("mossy_stone_bricks", 2), ("cobblestone", 1)]))
            ring = (x in (CX0 + 4, CX1 - 4) and CZ0 + 4 <= z <= CZ1 - 4) or (z in (CZ0 + 4, CZ1 - 4) and CX0 + 4 <= x <= CX1 - 4)
            outer = x in (CX0, CX1) or z == CZ1
            if ring and (x + z) % 3 == 0:                                 # slender columns with arches
                h = crumble(5, x, z, 2)
                for y in range(0, h): bd.set(x, y, z, "stone_brick_wall")
                if h >= 4: bd.set(x, 4, z, "stone_bricks")
            elif ring and r.random() < 0.6:
                bd.set(x, 4, z, "stone_brick_slab", type="top", waterlogged="false")
            if outer:
                for y in range(0, crumble(6, x, z, 3)): bd.set(x, y, z, W(x, y, z))
    wx, wz = (CX0 + CX1) // 2, (CZ0 + CZ1) // 2                          # the well
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            if dx or dz: bd.set(wx + dx, 0, wz + dz, "mossy_cobblestone")
            else:
                bd.set(wx, -1, wz, "water")                                   # shallow: the template has no underground part
    for dx, dz in ((-1, -1), (1, 1)):
        bd.set(wx + dx, 1, wz + dz, "spruce_fence"); bd.set(wx + dx, 2, wz + dz, "spruce_fence")
    for dx in (-1, 0, 1): bd.set(wx + dx, 3, wz, "spruce_slab", type="bottom", waterlogged="false")
    bd.set(wx, 2, wz, "chain", axis="y", waterlogged="false")
    for _ in range(14):                                                   # a few old trees reclaiming the garth
        x, z = r.randint(CX0 + 5, CX1 - 5), r.randint(CZ0 + 5, CZ1 - 5)
        if abs(x - wx) < 3 and abs(z - wz) < 3: continue
        if r.random() < 0.5: bd.set(x, 0, z, r.choice(["fern", "grass", "azure_bluet", "oxeye_daisy", "poppy"]))
    for tx, tz in ((CX0 + 6, CZ1 - 6), (CX1 - 6, CZ0 + 6)):
        for y in range(0, 5): bd.set(tx, y, tz, "oak_log", axis="y")
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                for dy in (3, 4, 5):
                    if abs(dx) + abs(dz) + (dy - 4) * 2 < 4 and bd.get(tx + dx, dy, tz + dz) is None:
                        bd.set(tx + dx, dy, tz + dz, "oak_leaves", persistent="true", distance="1", waterlogged="false")
    overgrow(bd, vines=0.9, moss=0.35, leaves=30)
    # moss carpet and ferns over the nave floor
    for x in range(1, X1):
        for z in range(1, Z1):
            if bd.get(x, 0, z) is None and r.random() < 0.18: bd.set(x, 0, z, r.choice(["moss_carpet", "moss_carpet", "fern", "grass"]))
    return bd

# ------------------------------------------------------------------ 3. the besieged chapel: the hermit's refuge
def chapel(seed=3):
    bd = Build(seed); r = bd.r
    D = lambda x, y, z: bd.pick(DARK if y < 3 or (x + y) % 7 == 0 else WALL)
    X0, X1, Z0, Z1, WH = 0, 18, 0, 10, 8
    # graveyard ground and a low boundary wall with a gate gap
    for x in range(-9, 25):
        for z in range(-7, 18):
            bd.set(x, -1, z, bd.pick([("grass_block", 6), ("coarse_dirt", 2), ("podzol", 1), ("gravel", 0.5)]))
    for x in range(-9, 25):
        for z in (-7, 17):
            if not (z == 17 and -1 <= x <= 1) and r.random() > 0.08: bd.set(x, 0, z, "cobblestone_wall" if r.random() < 0.8 else "mossy_cobblestone_wall")
    for z in range(-7, 18):
        for x in (-9, 24):
            if not (x == -9 and 4 <= z <= 6) and r.random() > 0.08: bd.set(x, 0, z, "cobblestone_wall" if r.random() < 0.8 else "mossy_cobblestone_wall")
    for x, z in ((-9, 3), (-9, 7)):                                     # gate posts with lanterns
        bd.set(x, 0, z, "stone_bricks"); bd.set(x, 1, z, "stone_bricks"); bd.set(x, 2, z, "lantern", hanging="false", waterlogged="false")
    # path to the door
    for x in range(-8, 0):
        for z in (4, 5, 6): bd.set(x, -1, z, bd.pick([("gravel", 2), ("cobblestone", 1), ("dirt_path", 2)]))
    # gravestones
    for gx in range(-7, -1, 3):
        for gz in list(range(-5, 2, 3)) + list(range(9, 16, 3)):
            if r.random() < 0.2: continue
            kind = r.random()
            if kind < 0.4: bd.set(gx, 0, gz, "stone_brick_wall"); bd.set(gx, 1, gz, "stone_brick_slab", type="bottom", waterlogged="false")
            elif kind < 0.7: bd.set(gx, 0, gz, "cobblestone_wall"); bd.set(gx, 1, gz, "cobblestone_wall"); bd.set(gx, 2, gz, "cobblestone_slab", type="bottom", waterlogged="false")
            else: bd.set(gx, 0, gz, "mossy_stone_brick_stairs", facing="east", half="bottom", shape="straight", waterlogged="false")
            bd.set(gx + 1, -1, gz, "coarse_dirt")
            if r.random() < 0.4: bd.set(gx + 1, 0, gz, r.choice(["dead_bush", "candle", "fern", "poppy"]), **({"candles": "1", "lit": "true", "waterlogged": "false"} if False else {}))
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "candle" and not p: bd.b[k] = (n, {"candles": "1", "lit": "true", "waterlogged": "false"}, nbt)
    for gx in range(20, 24, 2):                                          # a few graves behind, freshly dug
        for gz in (0, 4, 8):
            for dz in (0, 1): bd.set(gx, -1, gz + dz, "dirt")
            bd.set(gx, 0, gz - 1, "spruce_fence"); bd.set(gx, 1, gz - 1, "spruce_fence")
    # walls: dark, heavy, a plinth of deepslate
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            edge = x in (X0, X1) or z in (Z0, Z1)
            for y in range(0, WH + 1):
                bd.set(x, y, z, D(x, y, z) if edge else "air")
            bd.set(x, -1, z, "polished_deepslate" if (x + z) % 2 else "deepslate_tiles")
    for x in range(X0, X1 + 1, 6):                                       # corner and mid buttresses
        buttress(bd, x, Z0, -1, 6, "south", DARK); buttress(bd, x, Z1, 1, 6, "north", DARK)
    gable_roof(bd, X0, X1, Z0, Z1, WH + 1, holes=0.0, gable_fill=[(X0, None), (X1, None)])
    # bell-cote on the west gable
    for y in range(WH + 7, WH + 11):
        for z in (4, 6): bd.set(X0, y, z, "deepslate_brick_wall")
    bd.set(X0, WH + 11, 5, "deepslate_bricks"); bd.set(X0, WH + 11, 4, "deepslate_brick_stairs", facing="south", half="bottom", shape="straight", waterlogged="false")
    bd.set(X0, WH + 11, 6, "deepslate_brick_stairs", facing="north", half="bottom", shape="straight", waterlogged="false")
    bd.set(X0, WH + 10, 5, "bell", attachment="ceiling", facing="east", powered="false")
    # the locked door: iron, no handle on this side
    for z in (4, 5, 6):
        for y in (0, 1, 2, 3): bd.set(X0, y, z, "air")
    bd.set(X0, 0, 5, "iron_door", facing="east", half="lower", hinge="left", open="false", powered="false")
    bd.set(X0, 1, 5, "iron_door", facing="east", half="upper", hinge="left", open="false", powered="false")
    for z in (4, 6):
        for y in (0, 1, 2): bd.set(X0, y, z, "deepslate_bricks")
    bd.set(X0, 2, 5, "deepslate_bricks"); bd.set(X0, 3, 4, "deepslate_brick_stairs", facing="south", half="top", shape="straight", waterlogged="false")
    bd.set(X0, 3, 6, "deepslate_brick_stairs", facing="north", half="top", shape="straight", waterlogged="false")
    bd.set(X0 - 1, 2, 3, "soul_lantern", hanging="false", waterlogged="false") if False else None
    bd.set(X0 - 1, -1, 4, "polished_deepslate"); bd.set(X0 - 1, -1, 5, "polished_deepslate"); bd.set(X0 - 1, -1, 6, "polished_deepslate")
    # barricaded windows: narrow slits boarded up from inside
    for wx in (4, 9, 14):
        for z, inside, face in ((Z0, 1, "south"), (Z1, -1, "north")):
            for y in (3, 4, 5): bd.set(wx, y, z, "iron_bars")
            for y in (3, 4, 5): bd.set(wx, y, z + inside, "spruce_trapdoor", facing=face, half="bottom", open="true", powered="false", waterlogged="false")
    # claw marks and siege damage outside: cracked stone round the door, an abandoned barricade
    for z in (2, 3, 7, 8):
        for y in (0, 1, 2): bd.set(X0, y, z, "cracked_deepslate_bricks")
    for z in (2, 8): bd.set(X0 - 2, 0, z, "spruce_fence"); bd.set(X0 - 2, 1, z, "spruce_fence")
    bd.set(X0 - 3, 0, 3, "spruce_slab", type="bottom", waterlogged="false"); bd.set(X0 - 2, 0, 7, "hay_block", axis="x")
    # ---- interior: the hermit's infirmary
    for x in range(1, X1):
        for z in range(1, Z1): bd.set(x, -1, z, "spruce_planks" if 2 <= z <= 8 else "polished_deepslate")
    for x in range(2, 15): bd.set(x, 0, 5, "brown_carpet")
    bd.set(16, 0, 2, "white_bed", part="foot", facing="north", occupied="false")                   # the sickbed
    bd.set(16, 0, 1, "white_bed", part="head", facing="north", occupied="false")
    bd.set(17, 0, 3, "barrel", facing="up", open="false"); bd.set(17, 1, 3, "candle", candles="2", lit="true", waterlogged="false")
    bd.set(15, 0, 1, "water_cauldron", level="2")
    bd.set(14, 0, 1, "brewing_stand", has_bottle_0="true", has_bottle_1="false", has_bottle_2="false")
    bd.set(13, 0, 1, "barrel", facing="up", open="false"); bd.set(13, 1, 1, "potted_fern"); bd.set(12, 0, 1, "composter", level="4")
    bd.set(12, 1, 1, "potted_azalea_bush")
    for y in (0, 1, 2):                                                   # shelves of herbs and books
        for z in (7, 8, 9): bd.set(17, y, z, "bookshelf" if y < 2 else "chiseled_bookshelf", **({} if y < 2 else {"facing": "west", **{f"slot_{i}_occupied": "false" for i in range(6)}}))
    bd.set(16, 0, 9, "cartography_table"); bd.set(15, 0, 9, "barrel", facing="up", open="false")
    bd.set(16, 1, 9, "candle", candles="3", lit="true", waterlogged="false")
    chest(bd, 14, 0, 9, "north", "minecraft:chests/village/village_temple")
    lectern(bd, 11, 0, 8, "north", "The Hermit's Remedy",
            ["If you read this, they have come for me again.", "My wound will not close. A golden apple, or a draught of healing, might yet hold death off a while.",
             "If you find me in time, I will give you what the old knights left in my care: the map to the gate over the sea."])
    # the old altar, pushed against the wall as a workbench
    for z in (4, 5, 6): bd.set(17, 0, z, "chiseled_deepslate"); bd.set(17, 1, z, "smithing_table" if z == 5 else "dark_oak_slab", **({} if z == 5 else {"type": "bottom", "waterlogged": "false"}))
    # benches stacked as a barricade inside the door
    for z in (2, 3, 7, 8): bd.set(1, 0, z, "spruce_stairs", facing="east", half="bottom", shape="straight", waterlogged="false"); bd.set(1, 1, z, "spruce_slab", type="bottom", waterlogged="false")
    bd.set(2, 0, 3, "barrel", facing="east", open="false"); bd.set(2, 0, 7, "barrel", facing="east", open="false")
    # light: a chandelier and wall candles
    for y in range(WH + 2, WH + 5): bd.set(9, y, 5, "chain", axis="y", waterlogged="false")
    bd.set(9, WH + 1, 5, "lantern", hanging="true", waterlogged="false")
    for x in (5, 11):
        for z in (1, 9): bd.set(x, 1, z, "candle", candles="2", lit="true", waterlogged="false")
    for x in range(1, X1):
        for z in range(1, Z1):
            if x % 6 == 3 and z in (1, 9): pass
    overgrow(bd, vines=0.2, moss=0.06, leaves=6,
             interior_box=lambda x, y, z: X0 < x < X1 and Z0 < z < Z1)
    return bd

# ------------------------------------------------------------------ the look: rustic, with banners; then the variants
WHITE = {"polished_andesite": [("stone_bricks", 2), ("andesite", 2), ("cobblestone", 1)], "polished_diorite": [("stone", 1)], "diorite": [("andesite", 1)],
         "calcite": [("tuff", 1)], "white_carpet": [("red_carpet", 1)], "smooth_stone": [("stone", 1)]}
STAIR_SWAP = {"polished_andesite_stairs": "cobblestone_stairs", "polished_andesite_slab": "cobblestone_slab"}

def rustic(bd):
    for k, (n, p, nbt) in list(bd.b.items()):
        if n in WHITE: bd.b[k] = (bd.pick(WHITE[n]), {} if n.endswith(("andesite", "diorite", "calcite", "stone")) else p, nbt)
        elif n in STAIR_SWAP: bd.b[k] = (STAIR_SWAP[n], p, nbt)

def banner(bd, x, y, z, facing):
    """A red banner hung on the wall face behind it (facing = the way it looks)."""
    if bd.get(x, y, z) in (None, "air") and bd.get(x, y - 1, z) in (None, "air"):
        bd.set(x, y, z, "red_wall_banner", facing=facing)

def dress_parish(bd):
    # red banners down the nave walls between the windows, at the door and on the tower, like the old harbour
    for x in (16, 22, 28):
        banner(bd, x, 9, 1, "south"); banner(bd, x, 9, 13, "north")              # inside
    for z in (5, 9): banner(bd, -1, 5, z, "west")                                # flanking the west door
    # a dark arched recess round the west door
    for z in (5, 9):
        for y in range(1, 6): bd.set(0, y, z, bd.pick([("deepslate_bricks", 3), ("cracked_deepslate_bricks", 1)]))
    rustic(bd)

def dress_abbey(bd):
    for x in range(7, 28, 6): banner(bd, x, 9, 1, "south")
    rustic(bd)

def dress_chapel(bd):
    for z in (2, 8): banner(bd, -1, 4, z, "west")
    for x in (6, 12): banner(bd, x, 6, 1, "south"); banner(bd, x, 6, 9, "north")
    rustic(bd)

def copy(bd, seed):
    nb = Build(seed); nb.b = dict(bd.b); return nb

def overgrown(bd, seed, interior):
    """Long abandoned: moss over everything, ivy down the walls, bushes and saplings inside, holes in the roof."""
    r = random.Random(seed); bd.r = r
    top = max(k[1] for k in bd.b)
    for k, (n, p, nbt) in list(bd.b.items()):
        if n.endswith("_stairs") and ("tile" in n or "deepslate" in n) and k[1] > 9 and r.random() < 0.18: del bd.b[k]   # slipped slates
        elif n == "red_wall_banner" and r.random() < 0.5: del bd.b[k]
        elif n in ("candle",) and r.random() < 0.7: del bd.b[k]
    overgrow(bd, vines=1.0, moss=0.5, leaves=40, cobwebs=10, interior_box=interior)
    floor = [(x, y, z) for (x, y, z), (n, _, _) in bd.b.items() if full(n) and bd.get(x, y + 1, z) in (None, "air") and y <= 2]
    for (x, y, z) in floor:
        if interior and not interior(x, y + 1, z): continue
        q = r.random()
        if q < 0.32: bd.set(x, y + 1, z, "moss_carpet")
        elif q < 0.4: bd.set(x, y + 1, z, "grass")
        elif q < 0.44: bd.set(x, y + 1, z, "fern")
        elif q < 0.46: bd.set(x, y + 1, z, r.choice(["oak_leaves", "azalea_leaves", "flowering_azalea_leaves"]), persistent="true", distance="1", waterlogged="false")
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "stone_bricks" and r.random() < 0.25: bd.b[k] = ("mossy_stone_bricks", p, nbt)
        elif n == "cobblestone" and r.random() < 0.35: bd.b[k] = ("mossy_cobblestone", p, nbt)
    return bd

def rubble(bd, seed, lo=2, hi=11, floor_y=0):
    """Near rubble: the walls stand a few blocks high at most, the roof is gone, the stones lie round the foot."""
    r = random.Random(seed); bd.r = r
    ph = r.random() * 6
    def H(x, z):
        n = 0.5 + 0.5 * math.sin(x * 0.23 + z * 0.17 + ph) * math.cos(x * 0.07 - z * 0.19 + ph * 0.5)
        return lo + int((hi - lo) * n * (0.65 + 0.35 * r.random()))
    fallen = collections.Counter()
    for (x, y, z), (n, p, nbt) in list(bd.b.items()):
        if y <= floor_y: continue
        if n in ("chain", "bell") or (n == "lantern" and p.get("hanging") == "true") or n.endswith("banner") and r.random() < 0.8:
            del bd.b[(x, y, z)]; continue
        if y > H(x, z):
            if full(n): fallen[(x, z)] += 1
            del bd.b[(x, y, z)]
    # furniture knocked over or gone
    for k, (n, p, nbt) in list(bd.b.items()):
        if k[1] > floor_y and any(t in n for t in ("stairs", "trapdoor", "carpet", "candle", "pane")) and r.random() < 0.5 and not full(n): del bd.b[k]
    # rubble at the foot of the walls, inside and out
    D = [(1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2), (1, 1), (-1, -1)]
    for (x, z), cnt in fallen.items():
        for _ in range(min(3, cnt // 3 + (1 if r.random() < 0.5 else 0))):
            dx, dz = r.choice(D)
            px, pz = x + dx, z + dz
            y = floor_y + 1
            while bd.get(px, y, pz) not in (None, "air") and y < floor_y + 3: y += 1
            if y >= floor_y + 3 or bd.get(px, y - 1, pz) in (None, "air") and y - 1 > floor_y: continue
            m = r.random()
            if m < 0.45: bd.set(px, y, pz, r.choice(["cobblestone", "mossy_cobblestone", "cracked_stone_bricks", "stone", "andesite"]))
            elif m < 0.7: bd.set(px, y, pz, r.choice(["cobblestone_slab", "stone_brick_slab", "mossy_cobblestone_slab"]), type="bottom", waterlogged="false")
            elif m < 0.85: bd.set(px, y, pz, r.choice(["stone_brick_stairs", "mossy_stone_brick_stairs", "cobblestone_stairs"]), facing=r.choice(["north", "south", "east", "west"]), half="bottom", shape="straight", waterlogged="false")
            else: bd.set(px, y, pz, "gravel" if y == floor_y + 1 else "cobblestone")
    # stairs and slabs left hanging over nothing drop away
    for _ in range(3):
        for (x, y, z), (n, p, nbt) in list(bd.b.items()):
            if y > floor_y + 1 and not full(n) and bd.get(x, y - 1, z) in (None, "air") and n not in ("air", "vine", "ladder", "red_wall_banner", "iron_bars") and "pane" not in n and "wall" not in n:
                del bd.b[(x, y, z)]
    overgrow(bd, vines=0.5, moss=0.35, leaves=24, cobwebs=0)
    for (x, y, z), (n, _, _) in list(bd.b.items()):
        if y == floor_y and full(n) and bd.get(x, y + 1, z) in (None, "air"):
            q = r.random()
            if q < 0.25: bd.set(x, y + 1, z, "moss_carpet")
            elif q < 0.35: bd.set(x, y + 1, z, r.choice(["grass", "fern", "dead_bush"]))
    return bd

if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    NAVE = lambda x, y, z: (9 < x < 36 and 0 < z < 14) or (0 < x < 8 and 3 < z < 11) or (37 <= x < 43 and 3 < z < 11)
    p = parish(); dress_parish(p)
    save(copy(p, 0), out / "church_parish.nbt")
    save(overgrown(copy(p, 0), 11, NAVE), out / "church_parish_overgrown.nbt")
    save(rubble(copy(p, 0), 12, lo=2, hi=12), out / "church_parish_rubble.nbt")
    a = abbey(); dress_abbey(a)
    save(copy(a, 0), out / "church_ruined_abbey.nbt")
    save(overgrown(copy(a, 0), 21, None), out / "church_ruined_abbey_overgrown.nbt")
    save(rubble(copy(a, 0), 22, lo=1, hi=8), out / "church_ruined_abbey_rubble.nbt")
    c = chapel(); dress_chapel(c)
    save(copy(c, 0), out / "church_besieged_chapel.nbt")
    save(overgrown(copy(c, 0), 31, lambda x, y, z: 0 < x < 18 and 0 < z < 10), out / "church_besieged_chapel_overgrown.nbt")
