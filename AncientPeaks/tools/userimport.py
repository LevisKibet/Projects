"""Bring the user's own .nbt builds into the mod: swap blocks from other mods for vanilla ones, set them on a ground layer
(template layer 1 = the land's surface, layer 0 = footing, like every other build in the mod), and make variants.

    python3 userimport.py IN_DIR OUT_DIR
"""
import sys, json, math, random, pathlib, collections
HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import churchgen
from churchgen import Build, overgrow, full, finish
from nbtedit import Template, Tag, C, S, I, L, ints, write_nbt

DATA_VERSION = 3465

# ------------------------------------------------------------------ loading and saving with entities
def load(path):
    t = Template(path)
    blocks = {}
    for b in t.blocks:
        x, y, z = [q.v for q in b.v["pos"].v[1]]
        p = t.pal[b.v["state"].v].v
        name = p["Name"].v
        props = {k: v.v for k, v in p["Properties"].v.items()} if "Properties" in p else {}
        blocks[(x, y, z)] = (name, props, b.v.get("nbt"))
    ents = list(t.root["entities"].v[1])
    return blocks, ents

def save(blocks, ents, path, drop_air=False):
    keys = [k for k, (n, _, _) in blocks.items() if not (drop_air and n.endswith(":air"))]
    xs = [k[0] for k in keys]; ys = [k[1] for k in keys]; zs = [k[2] for k in keys]
    mx, my, mz = min(xs), min(ys), min(zs)
    size = (max(xs) - mx + 1, max(ys) - my + 1, max(zs) - mz + 1)
    palette, index, out = [], {}, []
    for (x, y, z) in sorted(keys, key=lambda k: (k[1], k[2], k[0])):
        name, props, be = blocks[(x, y, z)]
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            e = {"Name": S(name)}
            if props: e["Properties"] = C({k: S(str(v)) for k, v in props.items()})
            palette.append(C(e))
        b = {"pos": ints(x - mx, y - my, z - mz), "state": I(index[key])}
        if be is not None: b["nbt"] = be
        out.append(C(b))
    ent_out = []
    for e in ents:
        d = dict(e.v)
        pos = [q.v for q in d["pos"].v[1]]; bp = [q.v for q in d["blockPos"].v[1]]
        d["pos"] = L(6, [Tag(6, pos[0] - mx), Tag(6, pos[1] - my), Tag(6, pos[2] - mz)])
        d["blockPos"] = ints(bp[0] - mx, bp[1] - my, bp[2] - mz)
        nb = dict(d["nbt"].v)
        nb.pop("UUID", None)
        if "Pos" in nb: nb["Pos"] = L(6, [Tag(6, pos[0] - mx), Tag(6, pos[1] - my), Tag(6, pos[2] - mz)])
        d["nbt"] = C(nb)
        ent_out.append(C(d))
    root = C({"DataVersion": I(DATA_VERSION), "size": ints(*size), "palette": L(10, palette), "blocks": L(10, out), "entities": L(10, ent_out)})
    open(path, "wb").write(write_nbt(root))
    c = collections.Counter(n for n, _, _ in blocks.values())
    mods = [n for n in c if not n.startswith("minecraft:")]
    print(f"{pathlib.Path(path).name}: {size[0]}x{size[1]}x{size[2]}, {sum(v for k, v in c.items() if not k.endswith(':air'))} blocks,"
          f" {len(ent_out)} entities{', NON-VANILLA: ' + str(mods) if mods else ''}")

# ------------------------------------------------------------------ other mods' blocks -> vanilla
WOOD = ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped")
def wood_of(mat):
    m = mat.split(":")[-1].replace("stripped_", "")
    for w in sorted(WOOD, key=len, reverse=True):
        if m.startswith(w + "_"): return w
    return None

def slab_of(mat):
    m = mat.split(":")[-1]
    w = wood_of(m)
    if w: return f"minecraft:{w}_slab"
    if m.endswith("_wool"): return "minecraft:" + m.replace("_wool", "_carpet")
    known = {"stone": "stone_slab", "cobblestone": "cobblestone_slab", "stone_bricks": "stone_brick_slab", "iron_block": "smooth_stone_slab",
             "deepslate_bricks": "deepslate_brick_slab", "deepslate_tiles": "deepslate_tile_slab", "polished_deepslate": "polished_deepslate_slab",
             "andesite": "andesite_slab", "polished_andesite": "polished_andesite_slab", "bricks": "brick_slab", "mud_bricks": "mud_brick_slab"}
    return "minecraft:" + known.get(m, "stone_slab")

def copycat_material(nbt):
    if nbt is None: return None
    d = nbt.v
    if "Material" in d: return d["Material"].v["Name"].v, {k: v.v for k, v in d["Material"].v.get("Properties", Tag(10, {})).v.items()}
    if "material_data" in d:
        for part in d["material_data"].v.values():
            n = part.v["material"].v["Name"].v
            if n != "create:copycat_base": return n, {}
    return None

SIMPLE = {
    "quark:mossy_cobblestone_bricks": "minecraft:mossy_stone_bricks", "quark:hollow_oak_log": "minecraft:oak_log",
    "quark:oak_post": "minecraft:oak_fence", "quark:potted_lavender_blossom_sapling": "minecraft:potted_azalea_bush",
    "mcwfences:spruce_horse_fence": "minecraft:spruce_fence", "mcwstairs:spruce_railing": "minecraft:spruce_fence",
    "mcwstairs:spruce_balcony": "minecraft:spruce_fence", "chipped:vertical_stiped_black_stained_glass_pane": "minecraft:gray_stained_glass_pane",
    "quark:black_framed_glass_pane": "minecraft:gray_stained_glass_pane", "farmersdelight:cabbage_crate": "minecraft:barrel",
    "farmersdelight:carrot_crate": "minecraft:barrel", "chipped:botanist_workbench": "minecraft:crafting_table",
    "chipped:glassblower": "minecraft:smoker", "chipped:tinkering_table": "minecraft:smithing_table", "chipped:mason_table": "minecraft:stonecutter",
    "create:white_table_cloth": "minecraft:white_carpet", "create:schematic_table": "minecraft:cartography_table",
}
KEEP_PROPS = {"minecraft:oak_log": ("axis",), "minecraft:gray_stained_glass_pane": ("north", "south", "east", "west", "waterlogged"),
              "minecraft:spruce_fence": (), "minecraft:oak_fence": (), "minecraft:smoker": ("facing",), "minecraft:stonecutter": ("facing",),
              "minecraft:barrel": ()}

def vanilla(name, props, nbt):
    if name.startswith("minecraft:"): return name, props, nbt
    if name in SIMPLE:
        n = SIMPLE[name]
        keep = KEEP_PROPS.get(n, ())
        p = {k: v for k, v in props.items() if k in keep}
        if n == "minecraft:barrel": p = {"facing": "up", "open": "false"}
        if n == "minecraft:smoker": p = {"facing": props.get("facing", "north"), "lit": "false"}
        if n == "minecraft:stonecutter": p = {"facing": props.get("facing", "north")}
        return n, p, None
    if name == "quark:spruce_chest":
        be = None
        if nbt is not None:
            d = dict(nbt.v); d["id"] = S("minecraft:chest"); be = C(d)
        return "minecraft:chest", {"facing": props.get("facing", "north"), "type": props.get("type", "single"), "waterlogged": "false"}, be
    if name == "mcwstairs:spruce_bulk_stairs":
        return "minecraft:spruce_stairs", {"facing": props.get("facing", "north"), "half": "bottom", "shape": "straight", "waterlogged": "false"}, None
    if name == "mcwdoors:garage_white_door":
        part = props.get("part", "bottom")
        if part == "top": return "minecraft:spruce_planks", {}, None
        return "minecraft:spruce_door", {"facing": props.get("facing", "north"), "half": "upper" if part == "middle" else "lower",
                                         "hinge": "left", "open": "false", "powered": "false"}, None
    if "copycat" in name:
        mat = copycat_material(nbt)
        if mat is None: return None
        mname, mprops = mat
        if name.endswith("copycat_beam") or name.endswith("copycat_shaft"):
            if mname == "minecraft:iron_block": return "minecraft:chain", {"axis": props.get("axis", "y"), "waterlogged": "false"}, None
            p = {"axis": props.get("axis", "y")} if mname.endswith(("_log", "_wood", "stem", "hyphae")) else {}
            return mname, p, None
        if name.endswith("copycat_byte"):
            return slab_of(mname), ({"type": "top" if any(k.startswith("top") and v == "true" for k, v in props.items()) else "bottom",
                                      "waterlogged": "false"} if not slab_of(mname).endswith("_carpet") else {}), None
        # steps and slabs
        sl = slab_of(mname)
        if sl.endswith("_carpet"): return sl, {}, None
        typ = props.get("half", props.get("type", "bottom"))
        return sl, {"type": typ if typ in ("top", "bottom", "double") else "bottom", "waterlogged": "false"}, None
    print("  unmapped", name, props); return None

def vanillaize(blocks):
    out = {}
    for k, (n, p, nbt) in blocks.items():
        r = vanilla(n, p, nbt)
        if r is not None: out[k] = r
    return out

def clean_entities(ents):
    keep = []
    for e in ents:
        eid = e.v["nbt"].v["id"].v
        if eid in ("minecraft:bat",): continue
        d = dict(e.v); nb = dict(d["nbt"].v)
        for k in ("Brain", "Gossips", "LastRestock", "LastGossipDecay", "RestocksToday", "Xp"): nb.pop(k, None)
        if eid == "minecraft:villager": continue                       # no traders at the stalls: the market is deserted
        d["nbt"] = C(nb)
        keep.append(C(d))
    return keep

def strip_sign_text(blocks):
    for k, (n, p, nbt) in list(blocks.items()):
        if nbt is not None and "front_text" in nbt.v:
            d = dict(nbt.v)
            for side in ("front_text", "back_text"):
                if side in d:
                    t = dict(d[side].v); t["messages"] = L(8, [S('{"text":""}')] * 4); d[side] = C(t)
            blocks[k] = (n, p, C(d))

# ------------------------------------------------------------------ setting a build on the ground
def lift(blocks, ents, ground=lambda x, z, occupied: "minecraft:grass_block", footing="minecraft:dirt", pad=0):
    """Everything up two; layer 1 becomes the ground surface under the whole footprint (plus pad), layer 0 its footing."""
    xs = [k[0] for k in blocks]; zs = [k[2] for k in blocks]; my = min(k[1] for k in blocks)
    occ = {(x, z) for (x, y, z) in blocks if y == my}
    out = {(x, y - my + 2, z): v for (x, y, z), v in blocks.items()}
    for x in range(min(xs) - pad, max(xs) + pad + 1):
        for z in range(min(zs) - pad, max(zs) + pad + 1):
            out[(x, 0, z)] = (footing, {}, None)
            g = ground(x, z, (x, z) in occ)
            out[(x, 1, z)] = (g, {"snowy": "false"} if g in ("minecraft:grass_block", "minecraft:podzol") else {}, None)
    moved = []
    for e in ents:
        d = dict(e.v)
        pos = [q.v for q in d["pos"].v[1]]; bp = [q.v for q in d["blockPos"].v[1]]
        d["pos"] = L(6, [Tag(6, pos[0]), Tag(6, pos[1] - my + 2), Tag(6, pos[2])])
        d["blockPos"] = ints(bp[0], bp[1] - my + 2, bp[2])
        moved.append(C(d))
    return out, moved

# ------------------------------------------------------------------ churchgen's weathering on our dicts
def to_build(blocks, seed):
    bd = Build(seed)
    for k, (n, p, nbt) in blocks.items():
        bd.b[k] = (n.split(":", 1)[1] if n.startswith("minecraft:") else n, dict(p), nbt)
    return bd

def from_build(bd):
    return {k: (n if ":" in n else "minecraft:" + n, p, nbt) for k, (n, p, nbt) in bd.b.items()}

# ------------------------------------------------------------------ the builds
def church_variants(src, out):
    blocks, ents = load(src)
    blocks = vanillaize(blocks); strip_sign_text(blocks)
    blocks = {k: v for k, v in blocks.items() if not v[0].endswith("concrete_powder")}       # (powder would fall)
    rnd = random.Random(7)
    def ground(x, z, occ):
        return rnd.choice(["minecraft:grass_block"] * 6 + ["minecraft:coarse_dirt", "minecraft:podzol", "minecraft:rooted_dirt"])
    blocks, ents = lift(blocks, clean_entities(ents), ground, pad=2)
    save(blocks, ents, out / "church_merlin.nbt")                                       # whole: Merlin's
    interior = None
    v = from_build(churchgen.overgrown(to_build(blocks, 0), 41, interior)); save(v, [], out / "church_overgrown_1.nbt")
    b2 = to_build(blocks, 0); churchgen.rubble(b2, 42, lo=8, hi=22, floor_y=1)
    v = from_build(churchgen.overgrown(b2, 43, interior)); save(v, [], out / "church_overgrown_2.nbt")
    b3 = to_build(blocks, 0); churchgen.rubble(b3, 44, lo=3, hi=12, floor_y=1); save(from_build(b3), [], out / "church_rubble_1.nbt")
    b4 = to_build(blocks, 0); churchgen.rubble(b4, 45, lo=2, hi=6, floor_y=1); save(from_build(b4), [], out / "church_rubble_2.nbt")

def house(src, out_path):
    blocks, ents = load(src)
    blocks = vanillaize(blocks); strip_sign_text(blocks)
    rnd = random.Random(hash(out_path.name) & 0xffff)
    def ground(x, z, occ):
        return "minecraft:grass_block" if rnd.random() < 0.8 else rnd.choice(["minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:podzol"])
    blocks, ents = lift(blocks, clean_entities(ents), ground, footing="minecraft:cobblestone", pad=1)
    save(blocks, ents, out_path)

STALL_COLOURS = [("red", "white"), ("blue", "light_blue"), ("green", "lime"), ("yellow", "orange"), ("brown", "white"), ("purple", "magenta")]
def stalls(srcs, out):
    n = 0
    for si, src in enumerate(srcs):
        blocks, ents = load(src)
        blocks = vanillaize(blocks); strip_sign_text(blocks)
        blocks = {k: v for k, v in blocks.items() if v[0] not in ("minecraft:snow",)}   # no snow on a summer market
        rnd = random.Random(si)
        def ground(x, z, occ):
            return rnd.choice(["minecraft:dirt_path", "minecraft:dirt_path", "minecraft:coarse_dirt", "minecraft:gravel", "minecraft:grass_block"])
        base, bents = lift(blocks, clean_entities(ents), ground, footing="minecraft:dirt", pad=1)
        # the awnings in a few colours
        orig = collections.Counter(v[0] for v in base.values() if v[0].endswith("_wool"))
        main = [c for c, _ in orig.most_common()]
        for ci, (a, b) in enumerate(STALL_COLOURS):
            m = {}
            if main: m[main[0]] = f"minecraft:{a}_wool"
            if len(main) > 1: m[main[1]] = f"minecraft:{b}_wool"
            v = {k: ((m.get(nm, nm)), p, nbt) for k, (nm, p, nbt) in base.items()}
            v = {k: ((nm.replace("blue_candle", f"{a}_candle").replace("red_candle", f"{a}_candle")), p, nbt) for k, (nm, p, nbt) in v.items()}
            n += 1
            save(v, bents, out / f"stall_{n:02d}.nbt")

if __name__ == "__main__":
    src = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2])
    (out / "churches").mkdir(parents=True, exist_ok=True); (out / "village/houses_town").mkdir(parents=True, exist_ok=True)
    (out / "village/market").mkdir(parents=True, exist_ok=True)
    church_variants(src / "dark_medieval_mansion_-_Copy.nbt", out / "churches")
    for i in (1, 2, 3): house(src / f"medieval_house_{i}.nbt", out / f"village/houses_town/medieval_house_{i}.nbt")
    stalls([src / "market1_om3tc3imjl.nbt", src / "market_2_b68yze2r16.nbt"], out / "village/market")
