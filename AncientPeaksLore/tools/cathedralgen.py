"""Camelot's great church (village/cathedral/cathedral.nbt): a cruciform minster for the town.

    nave with arcades and side aisles, clerestory windows above, a crossing and transepts, a chancel ending in an apse
    of tall coloured windows; twin west towers with spires, a great door and a rose window over it. Inside: pews either
    side of a red runner, the altar on a raised floor, chandeliers, banners, candles. Years of neglect: moss, slipped
    slates, a few holes in the roof with leaves blown in, cobwebs, pews knocked aside.

    The door is on the +z side (the village turns every building's door to face the castle).

    python3 tools/cathedralgen.py
"""
import sys, math, pathlib, random
sys.path.insert(0, '/home/claude/hl/tools')
_src = (pathlib.Path('/home/claude/hl/tools/churchgen.py')).read_text()
_src = _src.replace('HERE = pathlib.Path(__file__).resolve().parent', "HERE = pathlib.Path('/home/claude/hl/tools')")
exec(_src[:_src.index("# ------------------------------------------------------------------ 1. the parish church")])
OUT = pathlib.Path(__file__).resolve().parent.parent / 'src/main/resources/data/ancientpeaks_lore/structures/village/cathedral/cathedral.nbt'

STONE = [("stone_bricks", 40), ("cracked_stone_bricks", 8), ("mossy_stone_bricks", 7), ("polished_andesite", 6), ("andesite", 5), ("stone", 4)]
PIER = [("polished_andesite", 5), ("stone_bricks", 3), ("chiseled_stone_bricks", 1)]

def cathedral(seed=11):
    bd = Build(seed); r = bd.r
    W = lambda *_: bd.pick(STONE)
    # ---- plan (x across, z along; altar at low z, door at high z)
    AX0, AX1 = 0, 22            # outer walls (aisles included)
    NX0, NX1 = 5, 17            # arcade lines (nave between)
    Z0, Z1 = 12, 50             # nave + aisles: from the crossing to the west front
    TX0, TX1, TZ0, TZ1 = -8, 30, 12, 21   # transepts (the crossing is x5..17)
    AH, NH = 9, 19              # aisle wall height, nave wall height
    # foundation everywhere under the church
    for x in range(TX0 - 1, TX1 + 2):
        for z in range(-1, Z1 + 3):
            if (AX0 - 1 <= x <= AX1 + 1) or (TZ0 - 1 <= z <= TZ1 + 1):
                bd.set(x, -1, z, bd.pick([("cobblestone", 3), ("stone", 2), ("mossy_cobblestone", 1)]))

    def wall(x0, z0, x1, z1, y0, y1):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                for y in range(y0, y1 + 1): bd.set(x, y, z, W())

    # ---- aisles' outer walls, the west front, transept walls
    wall(AX0, Z0, AX0, Z1, 0, AH); wall(AX1, Z0, AX1, Z1, 0, AH)
    wall(AX0, Z1, AX1, Z1, 0, AH)
    wall(TX0, TZ0, TX0, TZ1, 0, NH - 3); wall(TX1, TZ0, TX1, TZ1, 0, NH - 3)
    wall(TX0, TZ0, AX0, TZ0, 0, NH - 3); wall(TX0, TZ1, AX0, TZ1, 0, NH - 3)
    wall(AX1, TZ0, TX1, TZ0, 0, NH - 3); wall(AX1, TZ1, TX1, TZ1, 0, NH - 3)
    # clear the inside of nave, aisles and transepts
    bd.air(AX0 + 1, 1, TZ1 + 1, AX1 - 1, NH + 12, Z1 - 1)
    bd.air(TX0 + 1, 1, TZ0 + 1, TX1 - 1, NH + 12, TZ1 - 1)
    # the transepts' openings into the aisles
    for z in range(TZ0 + 1, TZ1):
        for y in range(1, NH - 3): bd.set(AX0, y, z, "air"); bd.set(AX1, y, z, "air")
    # ---- arcades: piers every 4 blocks, round arches between, clerestory wall above
    for xa in (NX0, NX1):
        for z in range(TZ1 + 1, Z1):
            k = (z - TZ1 - 1) % 4
            if k == 0:
                for y in range(1, NH + 1): bd.set(xa, y, z, bd.pick(PIER))
            else:
                for y in range(8, NH + 1): bd.set(xa, y, z, W())        # wall over the arch
                if k in (1, 3): bd.set(xa, 7, z, "stone_brick_stairs", facing="south" if k == 1 else "north", half="top", shape="straight", waterlogged="false")
    # crossing piers (bigger)
    for (x, z) in ((NX0, TZ0), (NX1, TZ0), (NX0, TZ1), (NX1, TZ1)):
        for y in range(0, NH + 1):
            for dx in (0, 1 if x == NX0 else -1):
                bd.set(x + dx, y, z, bd.pick(PIER))
    # ---- chancel and apse (narrower, the nave's width), the east end
    CZ0 = 1
    wall(NX0, CZ0, NX0, TZ0, 0, NH); wall(NX1, CZ0, NX1, TZ0, 0, NH)
    bd.air(NX0 + 1, 1, CZ0, NX1 - 1, NH + 10, TZ0)
    cx = (NX0 + NX1) // 2
    apse = []
    for a in range(0, 181, 6):                                            # half-round apse at the east end
        rad = (NX1 - NX0) / 2
        x = round(cx + rad * math.cos(math.radians(a))); z = round(CZ0 - rad * math.sin(math.radians(a)) * 0.8)
        apse.append((x, z))
    for (x, z) in set(apse):
        for y in range(0, NH + 1): bd.set(x, y, z, W())
    for x in range(NX0 + 1, NX1):
        for z in range(CZ0 - 5, CZ0):
            inside = (x - cx) ** 2 / ((NX1 - NX0) / 2) ** 2 + ((z - CZ0) / (0.8 * (NX1 - NX0) / 2)) ** 2 < 0.85
            if inside:
                for y in range(1, NH + 8): bd.set(x, y, z, "air")
                bd.set(x, -1, z, "cobblestone")
    # ---- floors (checkered nave, dark stone in the chancel)
    for (x, y, z) in [k for k in list(bd.b) if k[1] == -1]:
        if bd.get(x, 0, z) is None and bd.get(x, 1, z) == 'air':
            chancel = NX0 < x < NX1 and z <= TZ0 + 1
            bd.set(x, 0, z, ("polished_deepslate" if (x + z) % 2 else "deepslate_tiles") if chancel else ("polished_andesite" if (x + z) % 2 else "calcite"))
    for x in range(NX0 + 1, NX1):                                         # the chancel step
        bd.set(x, 1, TZ0 + 1, "polished_deepslate_stairs", facing="north", half="bottom", shape="straight", waterlogged="false")
    for x in range(NX0 + 1, NX1):
        for z in range(CZ0 - 5, TZ0 + 1):
            if bd.get(x, 1, z) == "air": bd.set(x, 1, z, "polished_deepslate_slab", type="bottom", waterlogged="false")
    # the runner up the nave
    for z in range(TZ0 + 2, Z1):
        for x in (cx - 1, cx, cx + 1):
            if bd.get(x, 1, z) == "air" and r.random() > 0.07: bd.set(x, 1, z, "red_carpet")
    # ---- windows: aisle lancets, clerestory, transept ends, apse
    for z in range(TZ1 + 3, Z1 - 2, 4):
        lancet(bd, "z", AX0, z, 3, 5, r.choice(GLASS), broken=0.0)
        lancet(bd, "z", AX1, z, 3, 5, r.choice(GLASS), broken=0.0)
        lancet(bd, "z", NX0, z, 11, 6, r.choice(GLASS), broken=0.0)
        lancet(bd, "z", NX1, z, 11, 6, r.choice(GLASS), broken=0.0)
    for zc in (TZ0 + 3, TZ1 - 3):
        lancet(bd, "z", TX0, zc, 3, 9, r.choice(GLASS), broken=0.0)
        lancet(bd, "z", TX1, zc, 3, 9, r.choice(GLASS), broken=0.0)
    for (x, z) in sorted(set(apse)):
        if NX0 < x < NX1:
            for y in range(4, 15): bd.set(x, y, z, ["blue_stained_glass_pane", "red_stained_glass_pane", "yellow_stained_glass_pane"][(x + y // 4) % 3])
    # ---- roofs: the nave (and chancel) gable over everything x5..17, transept gable across, lean-to aisle roofs
    for z in range(CZ0 - 6, Z1 + 1):
        for d in range(0, 8):
            y = NH + 1 + d
            for x, face in ((NX0 - 1 + d, "east"), (NX1 + 1 - d, "west")):
                if NX0 - 1 + d > NX1 + 1 - d: continue
                if (NX0 - 1 + d == NX1 + 1 - d):
                    bd.set(x, y, z, "deepslate_tiles"); continue
                inside_apse = z < CZ0 and (x - cx) ** 2 / 36 + ((z - CZ0) / 5) ** 2 > 1.2
                if inside_apse: continue
                bd.set(x, y, z, "deepslate_tile_stairs", facing=face, half="bottom", shape="straight", waterlogged="false")
    for x in range(TX0 - 1, TX1 + 2):
        for d in range(0, 6):
            y = NH - 2 + d
            for z, face in ((TZ0 - 1 + d, "south"), (TZ1 + 1 - d, "north")):
                if TZ0 - 1 + d > TZ1 + 1 - d: continue
                if NX0 <= x <= NX1 and y <= NH + 3: continue
                bd.set(x, y, z, "deepslate_tile_stairs" if TZ0 - 1 + d != TZ1 + 1 - d else "deepslate_tiles", **({} if TZ0 - 1 + d == TZ1 + 1 - d else dict(facing=face, half="bottom", shape="straight", waterlogged="false")))
    for z in range(TZ1 + 1, Z1 + 1):                                      # aisle lean-tos
        for xs, face in (((AX0 - 1, AX0, AX0 + 1, AX0 + 2, AX0 + 3, AX0 + 4), "east"), ((AX1 + 1, AX1, AX1 - 1, AX1 - 2, AX1 - 3, AX1 - 4), "west")):
            for i, x in enumerate(xs):
                bd.set(x, AH + 1 + i // 2, z, "deepslate_tile_stairs" if i % 2 == 0 else "deepslate_tile_slab",
                       **(dict(facing=face, half="bottom", shape="straight", waterlogged="false") if i % 2 == 0 else dict(type="top", waterlogged="false")))
    # gable ends: fill the triangles of the west front and transept ends
    for d in range(0, 8):
        for x in range(NX0 + d, NX1 - d + 1): bd.set(x, NH + 1 + d, Z1, W())
    for d in range(0, 6):
        for z in range(TZ0 + d, TZ1 - d + 1): bd.set(TX0, NH - 2 + d, z, W()); bd.set(TX1, NH - 2 + d, z, W())
    # timber tie-beams in the nave
    for z in range(TZ1 + 3, Z1, 4):
        for x in range(NX0 + 1, NX1): bd.set(x, NH, z, "stripped_dark_oak_log", axis="x")
    # ---- west front: the great door, a rose window, twin towers with spires
    for x in range(cx - 2, cx + 3):
        for y in range(1, 8):
            if y == 7 and abs(x - cx) == 2: continue
            bd.set(x, y, Z1, "air")
    for x in range(cx - 3, cx + 4): bd.set(x, 0, Z1 + 1, "stone_brick_stairs", facing="north", half="bottom", shape="straight", waterlogged="false")
    for dx in range(-3, 4):
        for dy in range(-3, 4):
            if dx * dx + dy * dy <= 10:
                ring = dx * dx + dy * dy
                bd.set(cx + dx, 13 + dy, Z1, "red_stained_glass_pane" if ring == 0 else "yellow_stained_glass_pane" if ring <= 2 else "blue_stained_glass_pane" if ring <= 5 else "purple_stained_glass_pane")
    TW = 5
    for tx0 in (AX0 - 1, AX1 - TW + 1):
        for x in range(tx0, tx0 + TW + 1):
            for z in range(Z1 - TW, Z1 + 1):
                edge = x in (tx0, tx0 + TW) or z in (Z1 - TW, Z1)
                for y in range(0, 31):
                    if edge: bd.set(x, y, z, "polished_andesite" if (x in (tx0, tx0 + TW) and z in (Z1 - TW, Z1) and y % 3) else W())
                    elif y > 0: bd.set(x, y, z, "air")
                if not edge: bd.set(x, 0, z, "stone_bricks")
        for y in range(24, 29):                                           # belfry openings
            mx = tx0 + TW // 2
            for k in (-1, 0, 1):
                if y == 28 and k: continue
                bd.set(mx + k, y, Z1, "air"); bd.set(mx + k, y, Z1 - TW, "air"); bd.set(tx0, y, Z1 - TW // 2 + k, "air"); bd.set(tx0 + TW, y, Z1 - TW // 2 + k, "air")
        bd.set(tx0 + TW // 2, 28, Z1 - TW // 2, "bell", attachment="ceiling", facing="north", powered="false")
        for y in range(1, 24): bd.set(tx0 + 1, y, Z1 - TW + 1, "ladder", facing="south", waterlogged="false")
        for x in range(tx0 + 1, tx0 + TW):
            for z in range(Z1 - TW + 1, Z1):
                if (x, z) != (tx0 + 1, Z1 - TW + 1): bd.set(x, 23, z, "spruce_planks")
        lo, hi, y = 0, TW, 31                                             # spire
        while hi - lo >= 0:
            for x in range(tx0 + lo, tx0 + hi + 1):
                for z in range(Z1 - TW + lo, Z1 - TW + hi + 1):
                    if x in (tx0 + lo, tx0 + hi) or z in (Z1 - TW + lo, Z1 - TW + hi): bd.set(x, y, z, "deepslate_tiles")
            y += 2; lo += 1; hi -= 1
        bd.set(tx0 + TW // 2, y, Z1 - TW // 2, "lightning_rod", facing="up", powered="false", waterlogged="false")
        for x in range(tx0 + 1, tx0 + TW):                                # doorways from the aisles into the towers
            pass
        for y in range(1, 4): bd.set(tx0 + (TW if tx0 < cx else 0), y, Z1 - 2, "air")
    # ---- furniture: pews, altar, lecterns, chandeliers, banners, candles
    for z in range(TZ1 + 3, Z1 - 2, 2):
        for x in list(range(NX0 + 1, cx - 1)) + list(range(cx + 2, NX1)):
            bd.set(x, 1, z, "spruce_stairs", facing="south", half="bottom", shape="straight", waterlogged="false")
    az = CZ0 + 2
    for x in (cx - 1, cx, cx + 1):
        bd.set(x, 2, az, "chiseled_stone_bricks"); bd.set(x, 3, az, "white_carpet")
    bd.set(cx - 1, 4, az, "candle", candles="3", lit="false", waterlogged="false"); bd.set(cx + 1, 4, az, "candle", candles="3", lit="false", waterlogged="false")
    bd.set(cx, 4, az, "gold_block") if False else bd.set(cx, 4, az, "candle", candles="1", lit="true", waterlogged="false")
    bd.set(cx, 2, az - 2, "gold_block"); bd.set(cx, 3, az - 2, "lightning_rod", facing="up", powered="false", waterlogged="false")
    bd.set(cx, 2, az + 3, "lectern", facing="south", has_book="false", powered="false")
    for x in (NX0 + 1, NX1 - 1):
        bd.set(x, 2, az, "spruce_fence"); bd.set(x, 3, az, "candle", candles="4", lit="false", waterlogged="false")
        bd.set(x, 2, TZ0 - 1, "barrel", facing="up", open="false")
    chest(bd, NX1 - 1, 2, CZ0, "west", "ancientpeaks_lore:chests/logres_chapel")
    for z in range(TZ1 + 4, Z1 - 1, 8):                                   # chandeliers down the nave
        for y in range(NH - 4, NH): bd.set(cx, y, z, "chain", axis="y", waterlogged="false")
        bd.set(cx, NH - 5, z, "spruce_fence")
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            bd.set(cx + dx, NH - 5, z + dz, "spruce_fence")
            bd.set(cx + dx, NH - 4, z + dz, "candle", candles=str(r.randint(1, 3)), lit=str(r.random() < 0.3).lower(), waterlogged="false")
        bd.set(cx, NH - 6, z, "lantern", hanging="true", waterlogged="false")
    for z in range(TZ1 + 3, Z1 - 1, 4):                                   # banners on the piers, facing the nave
        for xa, face in ((NX0, "east"), (NX1, "west")):
            bd.set(xa + (1 if face == "east" else -1), 6, z - 2, r.choice(["white_wall_banner", "yellow_wall_banner", "red_wall_banner"]), facing=face)
    for (x, z) in ((AX0 + 1, TZ1 + 2), (AX1 - 1, TZ1 + 2), (TX0 + 1, TZ0 + 1), (TX1 - 1, TZ1 - 1), (TX0 + 1, TZ1 - 1), (TX1 - 1, TZ0 + 1)):
        bd.set(x, 1, z, "candle", candles=str(r.randint(2, 4)), lit="false", waterlogged="false")
    # side chapels in the transept ends: a small altar each
    for x in (TX0 + 1, TX1 - 1):
        for z in range(TZ0 + 3, TZ1 - 2):
            bd.set(x, 1, z, "polished_andesite_slab", type="top", waterlogged="false")
        bd.set(x, 2, (TZ0 + TZ1) // 2, "candle", candles="2", lit="false", waterlogged="false")
        bd.set(x, 2, (TZ0 + TZ1) // 2 - 1, "potted_lily_of_the_valley")
    # ---- the years
    # moss creeping in over the floor, along the walls and under the pews
    for (x, y, z), (n, p, nbt) in list(bd.b.items()):
        if y == 0 and n in ("polished_andesite", "calcite") and bd.get(x, 1, z) in (None, "air") and r.random() < 0.10:
            bd.set(x, 1, z, "moss_carpet")
    for k, (n, p, nbt) in list(bd.b.items()):
        if n in ("azalea_leaves", "oak_leaves"): bd.b[k] = (n, {"persistent": "true", "distance": "1", "waterlogged": "false"}, nbt)
        if n == "red_carpet" and r.random() < 0.08: bd.b[k] = ("moss_carpet", {}, nbt)
    webs = [(AX0 + 1, AH - 1, Z1 - 1), (AX1 - 1, AH - 1, Z1 - 1), (AX0 + 1, AH - 1, TZ1 + 1), (AX1 - 1, AH - 1, TZ1 + 1), (TX0 + 1, NH - 4, TZ0 + 1),
            (TX1 - 1, NH - 4, TZ1 - 1), (NX0 + 1, NH - 1, CZ0), (NX1 - 1, NH - 1, CZ0), (cx, NH - 1, Z1 - 1)]
    cobweb_corners(bd, webs)
    overgrow(bd, vines=0.3, moss=0.10, leaves=14,
             interior_box=lambda x, y, z: (AX0 < x < AX1 and Z0 <= z < Z1) or (TX0 < x < TX1 and TZ0 < z < TZ1) or (NX0 < x < NX1 and CZ0 - 6 < z <= TZ0))
    return bd

OUT.parent.mkdir(parents=True, exist_ok=True)
save(cathedral(), str(OUT))
