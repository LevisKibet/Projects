"""Ancient Peaks village: timber-and-slate houses (3 lore houses, 15 ruins), farms and Merlin's tower.

    python3 villagegen.py OUTDIR
      OUTDIR/village/houses_lore/*.nbt   3 houses still standing (lore goes here)
      OUTDIR/village/houses/*.nbt        15 ruined houses, one per trade
      OUTDIR/village/farms/*.nbt         4 overgrown farm plots
      OUTDIR/wizard_tower/*.nbt          Merlin's tower

Style: cobble/stone-brick plinth, dark oak timber frame with dark oak / spruce infill, deepslate-tile roofs with dark oak
rakes, brick chimneys, porches on log posts - then broken, mossy and overgrown.
"""
import sys, math, pathlib, random, collections
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from churchgen import *          # Build, save, gable_roof, overgrow, chest, lectern, NBT helpers
import churchgen

ST = dict(half="bottom", shape="straight", waterlogged="false")
LEAVES = ["azalea_leaves", "flowering_azalea_leaves", "oak_leaves", "dark_oak_leaves"]
FLOWERS = ["poppy", "dandelion", "oxeye_daisy", "cornflower", "azure_bluet", "allium", "lily_of_the_valley", "white_tulip"]

def leaf(bd, x, y, z, n=None):
    bd.set(x, y, z, n or bd.r.choice(LEAVES), persistent="true", distance="1", waterlogged="false")

def tall(bd, x, y, z, n):
    bd.set(x, y, z, n, half="lower"); bd.set(x, y + 1, z, n, half="upper")

def stairs(bd, x, y, z, n, facing, half="bottom"):
    bd.set(x, y, z, n, facing=facing, half=half, shape="straight", waterlogged="false")

def slab(bd, x, y, z, n, t="bottom"):
    bd.set(x, y, z, n, type=t, waterlogged="false")

def solid(n):
    return n is not None and n != "air"

# ------------------------------------------------------------------ ruin pass (shared)
def gravity(bd):
    """Removes everything no longer connected to the ground."""
    keep, todo = set(), [k for k, v in bd.b.items() if v[0] != "air" and k[1] <= 0]
    keep.update(todo)
    while todo:
        x, y, z = todo.pop()
        for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            q = (x + d[0], y + d[1], z + d[2])
            if q not in keep and q in bd.b and bd.b[q][0] != "air":
                keep.add(q); todo.append(q)
    gone = [k for k, v in bd.b.items() if v[0] != "air" and k not in keep]
    for k in gone: del bd.b[k]
    return len(gone)

def ruin(bd, level, box, roof_y):
    """level 0..1. box = (x0, x1, z0, z1) of the walls. roof_y = eave height."""
    r = bd.r; x0, x1, z0, z1 = box
    removed = 0
    def blast(cx, cy, cz, rad):
        nonlocal removed
        for x in range(int(cx - rad) - 1, int(cx + rad) + 2):
            for y in range(max(1, int(cy - rad) - 1), int(cy + rad) + 2):
                for z in range(int(cz - rad) - 1, int(cz + rad) + 2):
                    if (x - cx) ** 2 + (y - cy) ** 2 + (z - cz) ** 2 + r.uniform(-1.5, 1.5) < rad * rad and (x, y, z) in bd.b:
                        if bd.b[(x, y, z)][0] != "air": removed += 1
                        del bd.b[(x, y, z)]
    pts = [k for k, v in bd.b.items() if v[0] != "air" and k[1] >= 2]
    for _ in range(int(level * 7)):                                     # holes in walls and roof, mostly high up
        c = max(r.sample(pts, 3), key=lambda p: p[1])
        blast(*c, r.uniform(1.4, 1.6 + 2.6 * level))
    if level > 0.45:                                                    # a stretch of roof has fallen in
        span = max(2, int((x1 - x0) * r.uniform(0.25, 0.6))); sx = r.randint(x0 - 1, max(x0, x1 - span))
        for k in [k for k in bd.b if sx <= k[0] <= sx + span and k[1] >= roof_y - (1 if level > 0.7 else 0)]:
            if r.random() < 0.9: removed += 1; del bd.b[k]
    if level > 0.7:                                                     # a corner has come down
        cx, cz = r.choice([(x0, z0), (x1, z0), (x0, z1), (x1, z1)])
        blast(cx, r.randint(3, 5), cz, r.uniform(2.5, 3.8))
    removed += gravity(bd)
    # fallen material on the floor
    floor = [(x, z) for x in range(x0 + 1, x1) for z in range(z0 + 1, z1)]
    for _ in range(int(removed * 0.12)):
        x, z = r.choice(floor)
        y = 1
        while (x, y, z) in bd.b and bd.b[(x, y, z)][0] != "air": y += 1
        if y > 2: continue
        n = r.choice(["cobblestone", "mossy_cobblestone", "dark_oak_planks", "deepslate_tiles", "stone_bricks"])
        if r.random() < 0.5: slab(bd, x, y, z, r.choice(["deepslate_tile_slab", "spruce_slab", "cobblestone_slab", "dark_oak_slab"]))
        else: bd.set(x, y, z, n)
    weather(bd, 0.08 + 0.3 * level)
    # where the sky gets in, the floor goes green
    for x, z in floor:
        open_sky = all(not solid(bd.get(x, y, z)) for y in range(2, roof_y + 8))
        if not open_sky or r.random() > 0.55: continue
        y = 1
        while solid(bd.get(x, y, z)) and y < 4: y += 1
        if bd.get(x, y, z) in (None, "air") and solid(bd.get(x, y - 1, z)):
            p = r.random()
            if p < 0.4: bd.set(x, y, z, "moss_carpet")
            elif p < 0.7: bd.set(x, y, z, r.choice(["grass", "fern"]))
            elif p < 0.8 and bd.get(x, y + 1, z) in (None, "air"): tall(bd, x, y, z, "large_fern")
            elif p < 0.88: leaf(bd, x, y, z)
    return removed

def weather(bd, p):
    r = bd.r
    SW = {"stone_bricks": ["mossy_stone_bricks", "cracked_stone_bricks"], "cobblestone": ["mossy_cobblestone"],
          "stone_brick_stairs": ["mossy_stone_brick_stairs"], "cobblestone_stairs": ["mossy_cobblestone_stairs"],
          "cobblestone_wall": ["mossy_cobblestone_wall"], "deepslate_tiles": ["cracked_deepslate_tiles"], "dark_oak_planks": ["spruce_planks"]}
    for k, (n, props, nbt) in list(bd.b.items()):
        if n in SW and r.random() < p: bd.b[k] = (r.choice(SW[n]), props, nbt)

def roof_ivy(bd, amount):
    """Leafy clumps and moss creeping over the slates, like ivy on an old roof."""
    r = bd.r
    tops = [k for k, v in bd.b.items() if ("deepslate_tile" in v[0] or "dark_oak_stairs" in v[0]) and bd.get(k[0], k[1] + 1, k[2]) in (None, "air")]
    for _ in range(int(len(tops) * amount)):
        x, y, z = r.choice(tops)
        for dx, dz in ((0, 0), (1, 0), (0, 1), (-1, 0)):
            if r.random() < 0.6:
                k = (x + dx, y, z + dz)
                if k in bd.b and "deepslate_tile" in bd.b[k][0]:
                    if r.random() < 0.5: leaf(bd, *k)
                    elif bd.get(k[0], k[1] + 1, k[2]) in (None, "air"): bd.set(k[0], k[1] + 1, k[2], "moss_carpet")

def garden(bd, xs, zs, density=0.35, avoid=()):
    """Ground apron (y=0) with grass, flowers and bushes on top (y=1)."""
    r = bd.r
    for x in xs:
        for z in zs:
            if (x, 0, z) in bd.b: continue
            bd.set(x, 0, z, r.choice(["grass_block"] * 6 + ["coarse_dirt", "moss_block", "podzol", "rooted_dirt"]), **({"snowy": "false"} if False else {}))
            if (x, z) in avoid or (x, 1, z) in bd.b: continue
            p = r.random()
            if p < density * 0.45: bd.set(x, 1, z, r.choice(["grass", "grass", "fern"]))
            elif p < density * 0.7: bd.set(x, 1, z, r.choice(FLOWERS))
            elif p < density * 0.8 and (x, 2, z) not in bd.b: tall(bd, x, 1, z, r.choice(["tall_grass", "large_fern", "lilac", "rose_bush", "peony"]))
            elif p < density * 0.9: bd.set(x, 1, z, r.choice(["azalea", "flowering_azalea"]))
    for k, (n, props, nbt) in list(bd.b.items()):
        if n in ("grass_block", "podzol") and not props: bd.b[k] = (n, {"snowy": "false"}, nbt)

# ------------------------------------------------------------------ the house kit
TRADES = {   # trade: (loot table, workstation builder)
    "farmer": "minecraft:chests/village/village_plains_house", "fisherman": "minecraft:chests/village/village_fisher",
    "toolsmith": "minecraft:chests/village/village_toolsmith", "weaponsmith": "minecraft:chests/village/village_weaponsmith",
    "armorer": "minecraft:chests/village/village_armorer", "butcher": "minecraft:chests/village/village_butcher",
    "mason": "minecraft:chests/village/village_mason", "shepherd": "minecraft:chests/village/village_shepherd",
    "fletcher": "minecraft:chests/village/village_fletcher", "leatherworker": "minecraft:chests/village/village_tannery",
    "cartographer": "minecraft:chests/village/village_cartographer", "cleric": "minecraft:chests/village/village_temple",
    "librarian": "minecraft:chests/village/village_plains_house", "elder": "minecraft:chests/village/village_plains_house",
    "cottager": "minecraft:chests/village/village_plains_house",
}

def house(seed, trade, w=9, d=7, stories=1, annex=False, porch=False, chimney=True, damage=0.6, lore=None):
    bd = Build(seed); r = bd.r
    M = 4                                                               # margin round the house for porch, annex, garden
    x0, z0 = M, M; x1, z1 = x0 + w - 1, z0 + d - 1
    top = 5 * stories                                                   # beam level of the top storey = eave
    door_x = x0 + w // 2
    # ground, foundation, floor
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            bd.set(x, -1, z, "cobblestone")
            bd.set(x, 0, z, r.choice(["cobblestone", "stone_bricks", "cobblestone"]) if edge else ("spruce_planks" if (x + z) % 5 else "dark_oak_planks"))
    bd.air(x0 + 1, 1, z0 + 1, x1 - 1, top + d // 2 + 3, z1 - 1)
    # walls: stone course, then timber frame with plank infill; a beam at each storey
    for s in range(stories):
        yb = 1 + 5 * s
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if not (x in (x0, x1) or z in (z0, z1)): continue
                corner = x in (x0, x1) and z in (z0, z1)
                post = corner or (z in (z0, z1) and (x - x0) % 4 == 0) or (x in (x0, x1) and (z - z0) % 4 == 0)
                for y in range(yb, yb + 4):
                    if post: bd.set(x, y, z, "dark_oak_log", axis="y")
                    elif s == 0 and y == yb: bd.set(x, y, z, r.choice(["cobblestone", "stone_bricks", "mossy_cobblestone"]))
                    else: bd.set(x, y, z, "dark_oak_planks" if (s == 0) else r.choice(["spruce_planks", "spruce_planks", "dark_oak_planks"]))
                axis = "x" if z in (z0, z1) and not corner else "z"
                bd.set(x, yb + 4, z, "stripped_dark_oak_log", axis=axis)
        if s > 0:                                                       # upper floor
            for x in range(x0 + 1, x1):
                for z in range(z0 + 1, z1): bd.set(x, 5 * s, z, "spruce_planks")
            bd.set(x0 + 1, 5 * s, z0 + 1, "air")
            for y in range(1, 5 * s + 1): bd.set(x0 + 1, y, z0 + 1, "ladder", facing="south", waterlogged="false")
        # windows: two-pane-high, with shutters (spruce trapdoors) and a sill
        for x in range(x0 + 2, x1 - 1, 2):
            if (x - x0) % 4 == 0 or (s == 0 and abs(x - door_x) <= 1): continue
            for z, out, face in ((z0, -1, "north"), (z1, 1, "south")):
                if s == 0 and z == z1 and abs(x - door_x) <= 1: continue
                for y in (yb + 1, yb + 2): bd.set(x, y, z, "glass_pane" if r.random() > damage * 0.7 else "air")
                stairs(bd, x, yb, z + out, "spruce_stairs", "north" if face == "south" else "south", half="top")
                if r.random() < 0.5: bd.set(x, yb + 1, z + out, r.choice(["potted_red_tulip", "potted_azure_bluet", "potted_fern", "potted_azalea_bush", "potted_poppy"]))
        for z in range(z0 + 2, z1 - 1, 2):
            if (z - z0) % 4 == 0: continue
            for x in (x0, x1):
                for y in (yb + 1, yb + 2): bd.set(x, y, z, "glass_pane" if r.random() > damage * 0.7 else "air")
    # door
    bd.set(door_x, 1, z1, "dark_oak_door", facing="north", half="lower", hinge="left", open="false", powered="false")
    bd.set(door_x, 2, z1, "dark_oak_door", facing="north", half="upper", hinge="left", open="false", powered="false")
    bd.set(door_x, 3, z1, "dark_oak_planks")
    stairs(bd, door_x, 0, z1 + 1, "stone_brick_stairs", "north")
    bd.set(door_x - 1, 3, z1 + 1, "lantern", hanging="false", waterlogged="false") if not porch else None
    # roof: deepslate tiles, dark oak rakes, plank gables with a little attic window
    churchgen.gable_roof(bd, x0, x1, z0, z1, top, mat="deepslate_tile", ridge_block="deepslate_tiles",
                         holes=0.0, gable_fill=[(x0, "spruce_planks"), (x1, "spruce_planks")])
    for k, (n, p, nbt) in list(bd.b.items()):
        if k[0] in (x0 - 1, x1 + 1) and n.startswith("deepslate_tile_"):
            bd.b[k] = (n.replace("deepslate_tile", "dark_oak"), p, nbt)
    mz = (z0 + z1) // 2
    for x in (x0, x1):
        if d >= 7: bd.set(x, top + 2, mz, "glass_pane")
    for x in (x0 - 1, x1 + 1):                                         # lanterns hung at the gable peaks
        bd.set(x, top + 1, mz, "lantern", hanging="true", waterlogged="false") if r.random() < 0.6 else None
    # chimney
    cx = x1 + 1 if not annex else x0 - 1
    if chimney:
        for y in range(0, top + d // 2 + 4): bd.set(cx, y, mz, "bricks" if r.random() < 0.85 else "mud_bricks")
        bd.set(cx, top + d // 2 + 4, mz, "brick_wall")
        inside = cx - 1 if cx > x1 else cx + 1
        bd.set(cx if False else (x1 if cx > x1 else x0), 1, mz, "bricks"); bd.set(cx if False else (x1 if cx > x1 else x0), 2, mz, "bricks")
        bd.set(inside, 1, mz, "campfire", facing="north", lit="false", signal_fire="false", waterlogged="false")
    # porch on log posts with a slab roof
    if porch:
        for x in range(x0, x1 + 1):
            for zz in (z1 + 1, z1 + 2): bd.set(x, 0, zz, "spruce_planks" if x != door_x else "stone_bricks")
            slab(bd, x, 4, z1 + 1, "dark_oak_slab", "top"); slab(bd, x, 4, z1 + 2, "dark_oak_slab", "top")
        for x in range(x0, x1 + 1, 3 if w % 3 == 1 else 4):
            for y in range(1, 4): bd.set(x, y, z1 + 2, "dark_oak_fence")
        for y in range(1, 4): bd.set(x1, y, z1 + 2, "dark_oak_fence")
        bd.set(door_x - 1, 3, z1 + 2, "lantern", hanging="false", waterlogged="false")
        bd.set(x0 + 1, 1, z1 + 2, "barrel", facing="up", open="false")
    # workshop lean-to
    ax0, ax1 = x1 + 1, x1 + 4
    if annex:
        for x in range(ax0, ax1 + 1):
            for z in range(z0, z1 + 1):
                bd.set(x, 0, z, r.choice(["stone_bricks", "cobblestone", "polished_andesite"]))
                slab(bd, x, 4 if x < ax1 else 3, z, "deepslate_tile_slab", "bottom")
        for z in (z0, z1):
            for y in range(1, 4): bd.set(ax1, y, z, "dark_oak_log", axis="y")
        for z in range(z0 + 1, z1):
            if r.random() < 0.6: bd.set(ax1, 1, z, "cobblestone_wall")
        for x in range(ax0, ax1): bd.set(x, 1, z0, "cobblestone"); bd.set(x, 2, z0, "cobblestone_wall")
    # ---- interior
    loot = TRADES[trade]
    stuff = []                              # (block, props, nbt)
    bedz = z0 + 1
    def furn(n, **p): stuff.append((n, p, None))
    table = {
        "farmer": lambda: [furn("composter", level="5"), furn("hay_block", axis="y"), furn("barrel", facing="up", open="false"), furn("hay_block", axis="x")],
        "fisherman": lambda: [furn("barrel", facing="up", open="false"), furn("barrel", facing="north", open="false"), furn("water_cauldron", level="2")],
        "toolsmith": lambda: [furn("smithing_table"), furn("grindstone", face="floor", facing="north")],
        "weaponsmith": lambda: [furn("grindstone", face="floor", facing="east"), furn("chipped_anvil", facing="east")],
        "armorer": lambda: [furn("blast_furnace", facing="south", lit="false"), furn("cauldron")],
        "butcher": lambda: [furn("smoker", facing="south", lit="false"), furn("hay_block", axis="y")],
        "mason": lambda: [furn("stonecutter", facing="south"), furn("polished_andesite"), furn("stone_bricks")],
        "shepherd": lambda: [furn("loom", facing="south"), furn("white_wool"), furn("light_gray_wool")],
        "fletcher": lambda: [furn("fletching_table"), furn("target", power="0")],
        "leatherworker": lambda: [furn("cauldron"), furn("barrel", facing="up", open="false")],
        "cartographer": lambda: [furn("cartography_table"), furn("bookshelf")],
        "cleric": lambda: [furn("brewing_stand", has_bottle_0="true", has_bottle_1="false", has_bottle_2="false"), furn("candle", candles="3", lit="false", waterlogged="false")],
        "librarian": lambda: [furn("bookshelf"), furn("bookshelf"), furn("bookshelf")],
        "elder": lambda: [furn("bookshelf"), furn("bookshelf")],
        "cottager": lambda: [furn("barrel", facing="up", open="false")],
    }
    table[trade]()
    furn("crafting_table")
    # back wall row, left to right (skip the ladder corner and the hearth)
    spots = [(x, z0 + 1) for x in range(x0 + 2, x1)] + [(x1 - 1, z) for z in range(z0 + 2, z1 - 1)]
    spots = [s for s in spots if s != (x1 - 1, mz) and s != (x0 + 1, mz)]
    for (n, p, nbt), (x, z) in zip(stuff, spots):
        bd.set(x, 1, z, n, nbt=nbt, **p)
    # bed, table, chest, light
    bx = x0 + 1 if stories == 1 else x1 - 2
    by = 1 if stories == 1 else 6
    bz = z1 - 2
    if stories == 1 and trade in ("weaponsmith", "toolsmith", "armorer", "mason") and annex: by = 1
    bd.set(bx, by, bz, "red_bed" if r.random() < 0.5 else "brown_bed", part="head", facing="north", occupied="false")
    bd.set(bx, by, bz + 1, "red_bed" if r.random() < 0.5 else "brown_bed", part="foot", facing="north", occupied="false")
    bd.set(x0 + 1, 1, z1 - 1 if stories == 1 else mz, "air") if False else None
    tx, tz = (x0 + x1) // 2 - 1, mz
    if 0 < tx - x0 and (tx, 1, tz) not in [(door_x, 1, z1 - 1)]:
        bd.set(tx, 1, tz, "dark_oak_fence"); bd.set(tx, 2, tz, "spruce_pressure_plate", powered="false")
        stairs(bd, tx - 1, 1, tz, "spruce_stairs", "east") if tx - 1 > x0 else None
    chest(bd, x1 - 1, 1, z1 - 1, "west", loot)
    for y in range(top - 1, top + 1): bd.set((x0 + x1) // 2, y, mz, "chain", axis="y", waterlogged="false")
    bd.set((x0 + x1) // 2, top - 2, mz, "lantern", hanging="true", waterlogged="false")
    if stories == 2:                                                    # upstairs: storage and a second bed
        bd.set(x0 + 2, 6, z0 + 1, "barrel", facing="up", open="false"); bd.set(x0 + 3, 6, z0 + 1, "bookshelf")
        chest(bd, x1 - 1, 6, z0 + 1, "south", loot)
    if lore:
        lectern(bd, x0 + 2, 1, z1 - 1, "north", lore[0], lore[1])
    # annex workstations: the forge outside
    if annex:
        mid = (z0 + z1) // 2
        if trade in ("weaponsmith", "armorer", "toolsmith"):
            for z in range(mid - 1, mid + 2):
                bd.set(ax1 - 1, 1, z, "bricks"); bd.set(ax1 - 1, 2, z, "bricks")
            bd.set(ax1 - 1, 1, mid, "magma_block" if damage < 0.3 else "campfire", **({} if damage < 0.3 else dict(facing="west", lit="false", signal_fire="false", waterlogged="false")))
            bd.set(ax0, 1, mid, "anvil" if damage < 0.3 else "damaged_anvil", facing="north")
            bd.set(ax0 + 1, 1, mid + 1, "water_cauldron", level="1")
            bd.set(ax0, 1, z1 - 1, "grindstone", face="floor", facing="north")
            bd.set(ax0 + 1, 1, z0 + 1, "barrel", facing="up", open="false")
        else:
            bd.set(ax0 + 1, 1, mid, "stonecutter" if trade == "mason" else "smoker", facing="east", **({} if trade == "mason" else {"lit": "false"}))
            bd.set(ax0, 1, z0 + 1, "barrel", facing="up", open="false"); bd.set(ax0 + 1, 1, z0 + 1, "hay_block", axis="y")
    # fisherman: drying racks and a coil of rope by the door
    if trade == "fisherman":
        for x in range(x0, x0 + 3):
            bd.set(x, 0, z1 + 2, "spruce_planks"); bd.set(x, 1, z1 + 3, "spruce_fence")
        bd.set(x0 + 1, 2, z1 + 3, "spruce_fence"); bd.set(x0 + 1, 1, z1 + 2, "barrel", facing="up", open="false")
    if trade == "farmer":
        for x in range(x1 + 1, x1 + 4):
            for z in range(z0, z1 + 1):
                if x == x1 + 2 and z == mz: bd.set(x, 0, z, "water", level="0"); continue
                bd.set(x, 0, z, "farmland", moisture="7"); bd.set(x, 1, z, r.choice(["wheat", "carrots", "potatoes", "grass"]))
        for k, (n, p, nbt) in list(bd.b.items()):
            if n in ("wheat", "carrots", "potatoes") and not p: bd.b[k] = (n, {"age": str(r.randint(2, 7))}, nbt)
    # weathering, breakage, overgrowth
    if damage > 0.15: ruin(bd, damage, (x0, x1, z0, z1), top)
    else: weather(bd, 0.08)
    garden(bd, range(0, x1 + M + (4 if annex else 0)), range(0, z1 + M), density=0.25 + 0.35 * damage)
    churchgen.overgrow(bd, vines=0.5 + 1.2 * damage, moss=0.05 + 0.25 * damage, leaves=4 + int(10 * damage),
                       interior_box=lambda x, y, z: x0 < x < x1 and z0 < z < z1)
    roof_ivy(bd, 0.05 + 0.15 * damage)
    for x, z in ((x0 + 1, z0 + 1), (x1 - 1, z0 + 1), (x1 - 1, z1 - 1)):
        if bd.get(x, top - 1, z) in ("air", None) and r.random() < 0.6: bd.set(x, top - 1, z, "cobweb")
    return bd

LORE_TODO = ["(lore to be written)"]
LORE = [
    ("elder_hall", dict(trade="elder", w=13, d=9, stories=2, porch=True, damage=0.08,
                        lore=("Village Elder's Ledger", LORE_TODO))),
    ("blacksmith_forge", dict(trade="weaponsmith", w=9, d=7, stories=1, annex=True, damage=0.1,
                              lore=("Smith's Order Book", LORE_TODO))),
    ("scribe_house", dict(trade="librarian", w=9, d=8, stories=2, damage=0.1,
                          lore=("A Scribe's Notes", LORE_TODO))),
]
RUINS = [
    ("farmer_cottage", dict(trade="farmer", w=9, d=7, damage=0.55)),
    ("farmer_longhouse", dict(trade="farmer", w=13, d=7, porch=True, damage=0.75)),
    ("fisherman_hut", dict(trade="fisherman", w=7, d=6, chimney=False, damage=0.6)),
    ("fisherman_house", dict(trade="fisherman", w=9, d=7, stories=2, damage=0.8)),
    ("toolsmith_workshop", dict(trade="toolsmith", w=9, d=7, annex=True, damage=0.6)),
    ("weaponsmith_forge", dict(trade="weaponsmith", w=9, d=8, annex=True, damage=0.85)),
    ("armorer_house", dict(trade="armorer", w=11, d=7, stories=2, damage=0.65)),
    ("butcher_shop", dict(trade="butcher", w=9, d=7, annex=True, damage=0.7)),
    ("mason_yard", dict(trade="mason", w=9, d=7, annex=True, chimney=False, damage=0.5)),
    ("shepherd_house", dict(trade="shepherd", w=11, d=8, porch=True, damage=0.6)),
    ("fletcher_house", dict(trade="fletcher", w=9, d=7, damage=0.9)),
    ("leatherworker_house", dict(trade="leatherworker", w=9, d=8, stories=2, damage=0.7)),
    ("cartographer_house", dict(trade="cartographer", w=11, d=8, stories=2, porch=True, damage=0.55)),
    ("cleric_house", dict(trade="cleric", w=9, d=7, damage=0.75)),
    ("cottage_collapsed", dict(trade="cottager", w=7, d=6, damage=0.95)),
]

# ------------------------------------------------------------------ farms
def fence_ring(bd, x0, x1, z0, z1, gate, broken):
    r = bd.r
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if not (x in (x0, x1) or z in (z0, z1)): continue
            if (x, z) == gate:
                bd.set(x, 1, z, "spruce_fence_gate", facing="south", in_wall="false", open="true", powered="false"); continue
            if r.random() < broken: continue
            bd.set(x, 1, z, "spruce_fence" if (x + z) % 7 else "dark_oak_log", **({} if (x + z) % 7 else {"axis": "y"}))

def crop_field(seed=11):
    bd = Build(seed); r = bd.r
    W_, D_ = 19, 13
    for x in range(W_):
        for z in range(D_):
            bd.set(x, -1, z, "dirt")
            edge = x in (0, W_ - 1) or z in (0, D_ - 1)
            if edge: bd.set(x, 0, z, "grass_block", snowy="false"); continue
            if x in (5, 13) and 1 <= z <= D_ - 2:
                bd.set(x, 0, z, "water", level="0"); continue
            overgrown = r.random() < 0.35
            bd.set(x, 0, z, "grass_block" if overgrown else "farmland", **({"snowy": "false"} if overgrown else {"moisture": "7"}))
            crop = ["wheat", "wheat", "carrots", "potatoes", "beetroots"][(x // 6) % 5]
            if overgrown: bd.set(x, 1, z, r.choice(["grass", "fern", "grass", "poppy", "dandelion"]))
            elif r.random() < 0.75: bd.set(x, 1, z, crop, age=str(r.randint(0, 3 if crop == "beetroots" else 7)))
    for z in range(1, D_ - 1):
        for x in (5, 13):
            if r.random() < 0.3: bd.set(x, 1, z, "lily_pad")
    fence_ring(bd, 0, W_ - 1, 0, D_ - 1, (9, D_ - 1), broken=0.3)
    # scarecrow
    sx, sz = 9, 6
    bd.set(sx, 0, sz, "coarse_dirt"); bd.set(sx, 1, sz, "spruce_fence"); bd.set(sx, 2, sz, "hay_block", axis="y")
    bd.set(sx - 1, 2, sz, "spruce_fence"); bd.set(sx + 1, 2, sz, "spruce_fence")
    bd.set(sx, 3, sz, "carved_pumpkin", facing="south")
    for x, z, n, p in ((1, 1, "composter", {"level": "6"}), (2, 1, "hay_block", {"axis": "x"}), (W_ - 2, 1, "barrel", {"facing": "up", "open": "false"}),
                       (W_ - 2, 2, "hay_block", {"axis": "y"})):
        bd.set(x, 0, z, "coarse_dirt"); bd.set(x, 1, z, n, **p)
    churchgen.overgrow(bd, vines=0, moss=0, leaves=0)
    return bd

def pumpkin_patch(seed=12):
    bd = Build(seed); r = bd.r
    W_, D_ = 13, 11
    for x in range(W_):
        for z in range(D_):
            bd.set(x, -1, z, "dirt")
            if x == 6 and 1 <= z <= D_ - 2: bd.set(x, 0, z, "water", level="0"); continue
            bd.set(x, 0, z, r.choice(["farmland", "farmland", "coarse_dirt", "grass_block", "rooted_dirt"]))
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "farmland": bd.b[k] = (n, {"moisture": "7"}, nbt)
        if n == "grass_block": bd.b[k] = (n, {"snowy": "false"}, nbt)
    for z in range(1, D_ - 1, 2):
        for x, fruit, face in ((5, "pumpkin", "west"), (7, "melon", "east")):
            if bd.get(x, 0, z) != "farmland": bd.set(x, 0, z, "farmland", moisture="7")
            stem = "attached_" + fruit + "_stem"
            if r.random() < 0.7:
                bd.set(x, 1, z, stem, facing=face)
                fx = x - 1 if face == "west" else x + 1
                bd.set(fx, 0, z, "coarse_dirt"); bd.set(fx, 1, z, fruit if fruit == "melon" else ("pumpkin" if r.random() < 0.8 else "carved_pumpkin"),
                                                         **({"facing": "south"} if False else {}))
            else:
                bd.set(x, 1, z, fruit + "_stem", age=str(r.randint(2, 7)))
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "carved_pumpkin" and not p: bd.b[k] = (n, {"facing": "south"}, nbt)
    for x in range(W_):
        for z in range(D_):
            if bd.get(x, 1, z) is None and bd.get(x, 0, z) in ("grass_block", "coarse_dirt", "rooted_dirt") and r.random() < 0.4:
                bd.set(x, 1, z, r.choice(["grass", "fern", "dead_bush" if False else "grass", "sweet_berry_bush"]))
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "sweet_berry_bush": bd.b[k] = (n, {"age": str(r.randint(1, 3))}, nbt)
    fence_ring(bd, 0, W_ - 1, 0, D_ - 1, (6, 0), broken=0.4)
    return bd

def animal_pen(seed=13):
    bd = Build(seed); r = bd.r
    W_, D_ = 17, 13
    for x in range(W_):
        for z in range(D_):
            bd.set(x, -1, z, "dirt")
            bd.set(x, 0, z, r.choice(["grass_block"] * 5 + ["coarse_dirt", "mud", "podzol"]))
            if bd.get(x, 0, z) in ("grass_block", "podzol"): bd.b[(x, 0, z)] = (bd.get(x, 0, z), {"snowy": "false"}, None)
            if 0 < x < W_ - 1 and 0 < z < D_ - 1 and r.random() < 0.3: bd.set(x, 1, z, r.choice(["grass", "grass", "fern", "dandelion"]))
    fence_ring(bd, 0, W_ - 1, 0, D_ - 1, (8, D_ - 1), broken=0.25)
    # open shed: log posts, slab roof, hay inside
    for x in range(1, 7):
        for z in range(1, 5):
            bd.set(x, 0, z, "packed_mud" if (x + z) % 3 else "coarse_dirt")
            bd.set(x, 4, z, "spruce_slab", type="bottom", waterlogged="false") if r.random() > 0.15 else None
            bd.set(x, 1, z, "air") if (x, 1, z) in bd.b and bd.get(x, 1, z) not in ("spruce_fence", "dark_oak_log") else None
    for x, z in ((1, 4), (6, 4), (6, 1)):
        for y in range(1, 4): bd.set(x, y, z, "spruce_log", axis="y")
    for x in range(2, 6):
        for y in range(1, 4): bd.set(x, y, 1, "spruce_planks" if r.random() > 0.25 else "air")
    for x, z in ((2, 2), (3, 2), (2, 3)): bd.set(x, 1, z, "hay_block", axis="y")
    bd.set(2, 2, 2, "hay_block", axis="x")
    # trough and feeder
    for x in range(10, 14): bd.set(x, 1, 6, "water_cauldron" if x % 2 else "cauldron", **({"level": "2"} if x % 2 else {}))
    bd.set(12, 1, 9, "composter", level="3"); bd.set(13, 1, 9, "barrel", facing="up", open="false")
    for x, z in ((14, 2), (3, 10)):
        bd.set(x, 1, z, "spruce_log", axis="y"); leaf(bd, x, 2, z); leaf(bd, x + 1, 1, z)
    return bd

def orchard(seed=14):
    bd = Build(seed); r = bd.r
    W_, D_ = 17, 15
    for x in range(W_):
        for z in range(D_):
            bd.set(x, -1, z, "dirt"); bd.set(x, 0, z, r.choice(["grass_block"] * 6 + ["coarse_dirt", "moss_block"]))
            if bd.get(x, 0, z) == "grass_block": bd.b[(x, 0, z)] = ("grass_block", {"snowy": "false"}, None)
    for tx in (3, 8, 13):
        for tz in (3, 8, 12):
            if r.random() < 0.15: bd.set(tx, 1, tz, "oak_log", axis="y"); continue          # a stump
            h = r.randint(3, 5)
            for y in range(1, h + 1): bd.set(tx, y, tz, "oak_log", axis="y")
            for dx in range(-2, 3):
                for dz in range(-2, 3):
                    for dy in range(-1, 2):
                        if abs(dx) + abs(dz) + max(0, dy) * 2 <= 3 and bd.get(tx + dx, h + dy, tz + dz) in (None,) and r.random() < 0.85:
                            leaf(bd, tx + dx, h + dy, tz + dz, r.choice(["oak_leaves", "oak_leaves", "flowering_azalea_leaves"]))
            if r.random() < 0.5: bd.set(tx, h - 1, tz + 1, "bee_nest", facing="south", honey_level=str(r.randint(0, 5)))
    for x in range(W_):
        for z in range(D_):
            if bd.get(x, 1, z) is None and r.random() < 0.3:
                bd.set(x, 1, z, r.choice(["grass", "sweet_berry_bush", "poppy", "dandelion", "fern", "grass"]))
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "sweet_berry_bush": bd.b[k] = (n, {"age": str(r.randint(1, 3))}, nbt)
    # a low dry-stone wall instead of a fence
    for x in range(W_):
        for z in range(D_):
            if (x in (0, W_ - 1) or z in (0, D_ - 1)) and not (z == D_ - 1 and 7 <= x <= 9) and r.random() > 0.2:
                bd.set(x, 1, z, "mossy_cobblestone_wall" if r.random() < 0.5 else "cobblestone_wall")
    bd.set(1, 1, 1, "barrel", facing="up", open="false"); bd.set(2, 1, 1, "composter", level="7")
    return bd

# ------------------------------------------------------------------ large fields (ruined dry-stone walls, ripe crops, a scarecrow)
def big_field(seed, W_, D_, crops, round_=False, stripes=True):
    """crops: [(crop, weight)]. Mostly ripe; a trodden gateway with moss patches and pebbles like an old farm track."""
    bd = Build(seed); r = bd.r
    cx, cz = (W_ - 1) / 2, (D_ - 1) / 2
    def inside(x, z, shrink=0):
        if round_: return ((x - cx) / (cx - shrink)) ** 2 + ((z - cz) / (cz - shrink)) ** 2 <= 1
        return shrink <= x <= W_ - 1 - shrink and shrink <= z <= D_ - 1 - shrink
    gate = (int(cx), D_ - 1)
    order = [c for c, w in crops for _ in range(w)]
    for x in range(W_):
        for z in range(D_):
            if not inside(x, z): continue
            bd.set(x, -1, z, "dirt")
            wall = not inside(x, z, 1.2 if round_ else 1)
            near_gate = abs(x - gate[0]) <= 2 and z >= D_ - 6
            if wall:
                bd.set(x, 0, z, r.choice(["coarse_dirt", "rooted_dirt", "grass_block"]))
                if near_gate and abs(x - gate[0]) <= 1: continue
                p = r.random()
                if p < 0.12: continue                                          # gaps where stones were carried off
                n = r.choice(["mossy_cobblestone", "mossy_cobblestone", "cobblestone", "mossy_stone_bricks", "cracked_stone_bricks"])
                if p < 0.45: bd.set(x, 1, z, n)
                elif p < 0.75:
                    face = ("west" if x > cx else "east") if abs(x - cx) / max(cx, 1) > abs(z - cz) / max(cz, 1) else ("north" if z > cz else "south")
                    stairs(bd, x, 1, z, r.choice(["mossy_cobblestone_stairs", "cobblestone_stairs", "mossy_stone_brick_stairs"]), face)
                else: slab(bd, x, 1, z, r.choice(["mossy_cobblestone_slab", "cobblestone_slab"]))
                if p < 0.3 and r.random() < 0.35: bd.set(x, 2, z, r.choice(["mossy_cobblestone", "cobblestone"]))   # taller stretches
                continue
            if near_gate:                                                     # the worn track into the field
                bd.set(x, 0, z, r.choice(["coarse_dirt", "coarse_dirt", "rooted_dirt", "gravel"]))
                if r.random() < 0.25: bd.set(x, 1, z, "moss_carpet")
                elif r.random() < 0.1: bd.set(x, 1, z, "stone_button", face="floor", facing="north", powered="false")
                continue
            if r.random() < 0.035:                                            # bare or weedy spots
                bd.set(x, 0, z, "grass_block", snowy="false"); bd.set(x, 1, z, r.choice(["grass", "fern", "poppy", "cornflower"])); continue
            bd.set(x, 0, z, "farmland", moisture="7")
            crop = order[(x // 4) % len(order)] if stripes else order[r.randrange(len(order))]
            age = 7 if r.random() < 0.85 else r.randint(4, 6)
            bd.set(x, 1, z, crop, age=str(min(age, 3) if crop == "beetroots" else age))
    # scarecrow in the middle, a cart of hay and a barrel by the gate
    sx, sz = int(cx), int(cz)
    bd.set(sx, 0, sz, "coarse_dirt"); bd.set(sx, 1, sz, "spruce_fence"); bd.set(sx, 2, sz, "hay_block", axis="y")
    bd.set(sx - 1, 2, sz, "spruce_fence"); bd.set(sx + 1, 2, sz, "spruce_fence"); bd.set(sx, 3, sz, "carved_pumpkin", facing="south")
    for x, n, p in ((gate[0] - 3, "hay_block", {"axis": "x"}), (gate[0] + 3, "barrel", {"facing": "up", "open": "false"}), (gate[0] + 4, "composter", {"level": "4"})):
        z = D_ - 3
        if bd.get(x, 1, z) not in (None,) or True: bd.set(x, 0, z, "coarse_dirt"); bd.set(x, 1, z, n, **p)
    churchgen.overgrow(bd, vines=0, moss=0, leaves=0)
    return bd

def wheat_field(): return big_field(41, 33, 27, [("wheat", 1)])
def mixed_field(): return big_field(42, 29, 23, [("wheat", 3), ("carrots", 1), ("wheat", 2), ("potatoes", 1)])
def round_field(): return big_field(43, 31, 31, [("wheat", 4), ("potatoes", 1), ("wheat", 3), ("carrots", 1)], round_=True)

# ------------------------------------------------------------------ Merlin's tower
def wizard_tower(seed=21):
    bd = Build(seed); r = bd.r
    R = 6.4; H = 30; LV = 7                                             # outer radius, wall height, storey height
    def dist(x, z): return math.hypot(x, z)
    cells = [(x, z) for x in range(-8, 9) for z in range(-8, 9)]
    STONE = [("stone_bricks", 40), ("mossy_stone_bricks", 14), ("cracked_stone_bricks", 10), ("deepslate_bricks", 12), ("polished_andesite", 6), ("tuff_bricks", 8)]
    for x, z in cells:
        dd = dist(x, z)
        if dd <= R + 1.6:                                               # stepped plinth
            bd.set(x, -1, z, "cobblestone")
            if dd > R - 0.2: bd.set(x, 0, z, "stone_brick_stairs" if dd > R + 0.6 else "stone_bricks", **({} if dd <= R + 0.6 else {}))
        if dd <= R:
            for y in range(0, H + 1):
                if dd > R - 1.2: bd.set(x, y, z, bd.pick(STONE) if y % LV else "deepslate_tiles")
                elif y % LV == 0: bd.set(x, y, z, "spruce_planks" if y else "polished_deepslate")
                else: bd.set(x, y, z, "air")
    for k, (n, p, nbt) in list(bd.b.items()):                           # plinth stairs face outward
        if n == "stone_brick_stairs" and not p:
            x, y, z = k
            face = ("west" if x > 0 else "east") if abs(x) >= abs(z) else ("north" if z > 0 else "south")
            bd.b[k] = (n, dict(facing=face, **ST), nbt)
    # spiral stair round the square path at +-3 (24 cells), one step per block of height
    path = [(x, -3) for x in range(-3, 3)] + [(3, z) for z in range(-3, 3)] + [(x, 3) for x in range(3, -3, -1)] + [(-3, z) for z in range(3, -3, -1)]
    def dirn(a, b):
        dx, dz = b[0] - a[0], b[1] - a[1]
        return "east" if dx > 0 else "west" if dx < 0 else "south" if dz > 0 else "north"
    top_floor = LV * (H // LV)
    for i in range(top_floor):
        p, q = path[i % 24], path[(i + 1) % 24]
        y = i + 1
        stairs(bd, p[0], y, p[1], "spruce_stairs" if y % LV else "spruce_planks", dirn(p, q)) if y % LV else bd.set(p[0], y, p[1], "spruce_planks")
        for hh in (1, 2, 3):                                            # headroom through the floors
            if (p[0], y + hh, p[1]) in bd.b and (y + hh) % LV == 0 and y + hh <= top_floor: bd.set(p[0], y + hh, p[1], "air")
        bd.set(p[0], y - 1, p[1], bd.get(p[0], y - 1, p[1]) or "air") if False else None
    # door on the south, arrow-slit windows with violet glass
    for y in (1, 2, 3):
        for x in (-1, 0, 1):
            if y == 3 and x: continue
            bd.set(x, y, 6, "air")
    bd.set(0, 1, 6, "dark_oak_door", facing="north", half="lower", hinge="left", open="false", powered="false")
    bd.set(0, 2, 6, "dark_oak_door", facing="north", half="upper", hinge="left", open="false", powered="false")
    for x in (-1, 1): bd.set(x, 1, 6, "stone_bricks"); bd.set(x, 2, 6, "stone_bricks")
    for x in (-2, 2): bd.set(x, 3, 7, "soul_lantern", hanging="false", waterlogged="false") if (x, 3, 7) else None
    for lv in range(1, H // LV + 1):
        for (x, z) in ((0, -6), (6, 0), (-6, 0), (4, 4), (-4, -4), (4, -4)):
            yb = (lv - 1) * LV + 3
            for y in (yb, yb + 1, yb + 2):
                if (x, y, z) in bd.b: bd.set(x, y, z, "purple_stained_glass_pane" if r.random() < 0.6 else "magenta_stained_glass_pane")
    # storeys: 0 entrance & stores, 1 alchemy, 2 library, 3 observatory
    def at(x, y, z, n, **p):
        if (x, z) not in path: bd.set(x, y, z, n, **p)
    y = 1
    for x, z in ((-1, -1), (1, -1)): at(x, y, z, "barrel", facing="up", open="false")
    chest(bd, 0, 1, -1, "south", "minecraft:chests/village/village_temple")
    at(4, 1, 1, "barrel", facing="west", open="false"); at(4, 1, -1, "barrel", facing="west", open="false")
    at(0, 1, 0, "red_carpet"); at(1, 1, 1, "lantern", hanging="false", waterlogged="false")
    y = LV + 1                                                           # alchemy
    for x, z in ((-1, -1), (1, -1)): at(x, y, z, "brewing_stand", has_bottle_0="true", has_bottle_1="true", has_bottle_2="false")
    at(0, y, -1, "water_cauldron", level="3"); at(-1, y, 1, "cauldron")
    at(4, y, 0, "barrel", facing="west", open="false"); at(4, y, 1, "potted_crimson_fungus"); at(4, y, -1, "potted_blue_orchid")
    at(-4, y, 0, "bookshelf"); at(-4, y, 1, "potted_allium"); at(1, y, 1, "amethyst_cluster", facing="up", waterlogged="false")
    for yy in (LV * 2 - 2, LV * 2 - 1): bd.set(0, yy, 0, "chain", axis="y", waterlogged="false")
    bd.set(0, LV * 2 - 3, 0, "soul_lantern", hanging="true", waterlogged="false")
    y = 2 * LV + 1                                                       # library
    for x, z in ((-4, 0), (-4, 1), (-4, -1), (4, 0), (4, 1), (4, -1), (0, -4), (1, -4), (-1, -4), (0, 4), (1, 4), (-1, 4)):
        at(x, y, z, "bookshelf"); at(x, y + 1, z, "bookshelf")
    at(0, y, 0, "enchanting_table")
    lectern(bd, 1, y, 1, "north", "Merlin's Commonplace Book", ["(lore to be written)"])
    at(-1, y, 1, "candle", candles="4", lit="false", waterlogged="false")
    y = 3 * LV + 1                                                       # observatory: open loggias, a star chart, the scope
    for (x, z) in ((0, -6), (6, 0), (-6, 0), (0, 6)):
        for yy in range(y, y + 4):
            for k in (-1, 0, 1):
                xx, zz = (x + k, z) if x == 0 else (x, z + k)
                if (xx, yy, zz) in bd.b and not (yy == y + 3 and k): bd.set(xx, yy, zz, "air")
        bx, bz = (x, z + (1 if z < 0 else -1)) if x == 0 else (x + (1 if x < 0 else -1), z)
    at(0, y, 0, "cartography_table"); at(1, y, 0, "copper_block"); at(1, y + 1, 0, "lightning_rod", facing="east", powered="false", waterlogged="false")
    at(-1, y, -1, "lectern", facing="south", has_book="false", powered="false"); at(1, y, -1, "spyglass" if False else "barrel", facing="up", open="false")
    for x in (-1, 1): at(x, y, 1, "candle", candles="2", lit="false", waterlogged="false")
    # conical roof: dark slate with violet bands, an iron finial
    RT = H + 1
    for k in range(0, 13):
        rad = R + 1 - k * 0.6
        if rad < 0.6: break
        for x, z in cells:
            dd = dist(x, z)
            if rad - 1.1 < dd <= rad:
                face = ("west" if x > 0 else "east") if abs(x) >= abs(z) else ("north" if z > 0 else "south")
                mat = "deepslate_tile" if (k % 4) else "purpur"
                if mat == "purpur": bd.set(x, RT + k, z, "purpur_stairs", facing=face, **ST)
                else: stairs(bd, x, RT + k, z, "deepslate_tile_stairs", face)
            elif dd <= rad - 1.1 and k > 0:
                bd.set(x, RT + k, z, "deepslate_tiles") if dd > rad - 2.2 else None
    yt = RT + 13
    bd.set(0, yt - 2, 0, "deepslate_tiles"); bd.set(0, yt - 1, 0, "deepslate_tile_wall"); bd.set(0, yt, 0, "lightning_rod", facing="up", powered="false", waterlogged="false")
    for x, z in cells:                                                  # eaves overhang
        if R + 0.2 < dist(x, z) <= R + 1.2: slab(bd, x, H + 1, z, "deepslate_tile_slab", "top") if (x, H + 1, z) not in bd.b else None
    # age: cracks, moss, a scar high on the north side, vines and climbing leaves
    weather(bd, 0.25)
    for _ in range(3):
        cx, cz = r.choice([(-5, -3), (-3, -5), (5, -3)]); cy = r.randint(12, 26)
        for x in range(cx - 2, cx + 3):
            for yy in range(cy - 2, cy + 3):
                for z in range(cz - 2, cz + 3):
                    if (x - cx) ** 2 + (yy - cy) ** 2 + (z - cz) ** 2 < 4.5 and dist(x, z) > R - 1.3 and (x, yy, z) in bd.b:
                        del bd.b[(x, yy, z)]
    gravity(bd)
    garden(bd, range(-10, 11), range(-10, 11), density=0.5, avoid={(0, 7), (0, 8)})
    for z in (7, 8, 9, 10): bd.set(0, 0, z, "dirt_path"); bd.set(-1, 0, z, r.choice(["dirt_path", "gravel", "coarse_dirt"]))
    churchgen.overgrow(bd, vines=1.4, moss=0.2, leaves=16, interior_box=lambda x, y, z: dist(x, z) < R - 1)
    return bd

if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1])
    for sub in ("village/houses_lore", "village/houses", "village/farms", "wizard_tower"): (out / sub).mkdir(parents=True, exist_ok=True)
    for i, (name, kw) in enumerate(LORE): save(house(100 + i, **kw), out / "village/houses_lore" / f"{name}.nbt")
    for i, (name, kw) in enumerate(RUINS): save(house(200 + i, **kw), out / "village/houses" / f"{name}.nbt")
    for f in (crop_field, pumpkin_patch, animal_pen, orchard, wheat_field, mixed_field, round_field): save(f(), out / "village/farms" / f"{f.__name__}.nbt")
    save(wizard_tower(), out / "wizard_tower" / "merlin_tower.nbt")
