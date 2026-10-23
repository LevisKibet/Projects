"""Turns the project's structures/ folder into worldgen data.

structures/<name>/structure.json   where and how the structure is placed (see structures/README.md)
structures/<name>/*.nbt | *.schem  the start pieces (picked at random, by weight)
structures/<name>/<pool>/*.nbt     extra jigsaw pools, referenced as "ancientpeaks:<name>/<pool>"

A folder with no schematic of its own gets a small placeholder marker so the spot is visible in game.
"""
import gzip, io, json, pathlib, shutil, struct, zlib

ROOT = pathlib.Path(__file__).parent
SRC = ROOT / "structures"
R = ROOT / "src/main/resources"
NS = "ancientpeaks"
DATA_VERSION = 3465  # 1.20.1

# ------------------------------------------------------------------ typed NBT
class Tag:
    def __init__(self, t, v): self.t, self.v = t, v
    def __repr__(self): return f"Tag({self.t},{self.v!r})"

def read_nbt(raw):
    if raw[:2] == b"\x1f\x8b": raw = gzip.decompress(raw)
    b = io.BytesIO(raw)
    def rd(fmt): return struct.unpack(">" + fmt, b.read(struct.calcsize(fmt)))[0]
    def s(): return b.read(rd("H")).decode("utf-8", "replace")
    def val(t):
        if t == 1: return rd("b")
        if t == 2: return rd("h")
        if t == 3: return rd("i")
        if t == 4: return rd("q")
        if t == 5: return rd("f")
        if t == 6: return rd("d")
        if t == 7: n = rd("i"); return bytearray(b.read(n))
        if t == 8: return s()
        if t == 9:
            et = rd("b"); n = rd("i"); return (et, [Tag(et, val(et)) for _ in range(n)])
        if t == 10:
            d = {}
            while True:
                et = rd("b")
                if et == 0: return d
                k = s(); d[k] = Tag(et, val(et))
        if t == 11: n = rd("i"); return [rd("i") for _ in range(n)]
        if t == 12: n = rd("i"); return [rd("q") for _ in range(n)]
        raise ValueError(f"bad tag {t}")
    t = rd("b"); s()
    return Tag(t, val(t))

def write_nbt(root):
    b = io.BytesIO()
    def w(fmt, x): b.write(struct.pack(">" + fmt, x))
    def s(x): e = x.encode("utf-8"); w("H", len(e)); b.write(e)
    def val(tag):
        t, v = tag.t, tag.v
        if t == 1: w("b", v)
        elif t == 2: w("h", v)
        elif t == 3: w("i", v)
        elif t == 4: w("q", v)
        elif t == 5: w("f", v)
        elif t == 6: w("d", v)
        elif t == 7: w("i", len(v)); b.write(bytes(v))
        elif t == 8: s(v)
        elif t == 9:
            et, items = v; et = items[0].t if items else 0; w("b", et); w("i", len(items))
            for it in items: val(it)
        elif t == 10:
            for k, x in v.items(): w("b", x.t); s(k); val(x)
            w("b", 0)
        elif t == 11: w("i", len(v)); [w("i", x) for x in v]
        elif t == 12: w("i", len(v)); [w("q", x) for x in v]
    w("b", 10); s(""); val(root)
    return gzip.compress(b.getvalue())

def I(v): return Tag(3, v)
def S(v): return Tag(8, v)
def C(d): return Tag(10, d)
def L(et, items): return Tag(9, (et, items))
def ints(*xs): return L(3, [I(x) for x in xs])

def template(size, blocks):
    """blocks: list of (x, y, z, name, props-dict, nbt-Tag-or-None)."""
    palette, index, out = [], {}, []
    for x, y, z, name, props, be in blocks:
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            e = {"Name": S(name)}
            if props: e["Properties"] = C({k: S(str(v)) for k, v in props.items()})
            palette.append(C(e))
        b = {"pos": ints(x, y, z), "state": I(index[key])}
        if be is not None: b["nbt"] = be
        out.append(C(b))
    return write_nbt(C({"DataVersion": I(DATA_VERSION), "size": ints(*size), "palette": L(10, palette),
                        "blocks": L(10, out), "entities": L(10, [])}))

def parse_state(st):
    if "[" not in st: return st, {}
    name, rest = st.split("[", 1)
    return name, dict(p.split("=", 1) for p in rest.rstrip("]").split(",") if "=" in p)

def schem_to_template(raw):
    """WorldEdit / Sponge .schem (v2 or v3) to a structure template. Air is skipped so the ground is kept."""
    root = read_nbt(raw).v
    if "Schematic" in root: root = root["Schematic"].v          # v3 wraps everything
    W, H, Lz = root["Width"].v & 0xFFFF, root["Height"].v & 0xFFFF, root["Length"].v & 0xFFFF
    blk = root["Blocks"].v if "Blocks" in root else root
    pal = {v.v: k for k, v in blk["Palette"].v.items()}
    data = blk["Data"].v if "Data" in blk else blk["BlockData"].v
    ids, i = [], 0
    while i < len(data):                                         # varints
        val = shift = 0
        while True:
            byte = data[i]; i += 1
            val |= (byte & 0x7F) << shift; shift += 7
            if not byte & 0x80: break
        ids.append(val)
    bes = {}
    for be in (blk.get("BlockEntities") or root.get("BlockEntities") or Tag(9, (10, []))).v[1]:
        d = dict(be.v); pos = d.pop("Pos").v
        bid = d.pop("Id").v if "Id" in d else None
        if "Data" in d: d = dict(d["Data"].v)
        if bid: d["id"] = S(bid)
        bes[tuple(pos)] = C(d)
    blocks = []
    for n, pid in enumerate(ids):
        x = n % W; z = (n // W) % Lz; y = n // (W * Lz)
        name, props = parse_state(pal[pid])
        if name in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"): continue
        if name == "minecraft:structure_void": continue
        blocks.append((x, y, z, name, props, bes.get((x, y, z))))
    return template((W, H, Lz), blocks)

def placeholder(label):
    """A small marker: a mossy stone ring with a lantern-topped pillar, so the spot is easy to find in game."""
    b = []
    for x in range(7):
        for z in range(7):
            d = max(abs(x - 3), abs(z - 3))
            if d == 3: b.append((x, 0, z, "minecraft:mossy_stone_bricks" if (x + z) % 3 else "minecraft:cracked_stone_bricks", {}, None))
            elif d <= 2: b.append((x, 0, z, "minecraft:gravel" if d == 2 else "minecraft:stone_bricks", {}, None))
    for y in (1, 2, 3): b.append((3, y, 3, "minecraft:chiseled_stone_bricks" if y == 2 else "minecraft:stone_bricks", {}, None))
    b.append((3, 4, 3, "minecraft:lantern", {"hanging": "false", "waterlogged": "false"}, None))
    sign = C({"id": S("minecraft:sign"), "front_text": C({"messages": L(8, [S(json.dumps({"text": t})) for t in ("Placeholder", label[:15], "", "")])}),
              "back_text": C({"messages": L(8, [S('""')] * 4)})})
    b.append((3, 1, 2, "minecraft:oak_wall_sign", {"facing": "north", "waterlogged": "false"}, sign))
    return template((7, 5, 7), b)

# ------------------------------------------------------------------ build
def W(rel, obj):
    p = R / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(json.dumps(obj, indent=2))

out_structs = R / f"data/{NS}/structures/modular"
if out_structs.exists(): shutil.rmtree(out_structs)
for d in ("worldgen/template_pool/modular", "worldgen/structure/modular", "worldgen/structure_set/modular"):
    if (R / f"data/{NS}/{d}").exists(): shutil.rmtree(R / f"data/{NS}/{d}")

ISLAND_BIOMES = [f"{NS}:{b}" for b in ("ancient_highlands", "highland_meadow", "highland_forest", "highland_plains", "highland_gorge",
                                         "highland_shore", "highland_peaks", "highland_tarn")]
W(f"data/{NS}/tags/worldgen/biome/has_structure/island.json", {"replace": False, "values": ISLAND_BIOMES})

def pieces(folder):
    """Schematics directly in a folder, as {file stem: template bytes}. Placeholders give way to real pieces."""
    found = {}
    for f in sorted(folder.iterdir()):
        if f.suffix == ".nbt": found[f.stem] = f.read_bytes()
        elif f.suffix == ".schem": found[f.stem] = schem_to_template(f.read_bytes())
    real = {k: v for k, v in found.items() if not k.startswith("placeholder")}
    return real or found

summary = []
for folder in sorted(p for p in SRC.iterdir() if p.is_dir()):
    name = folder.name
    cfg_file = folder / "structure.json"
    if not cfg_file.exists(): continue
    cfg = json.loads(cfg_file.read_text())
    if cfg.get("enabled", True) is False: continue
    weights = cfg.get("weights", {})
    processors = cfg.get("processors", "minecraft:empty")

    def pool(sub, files):
        elems = []
        for stem, data in files.items():
            rel = f"modular/{name}/{sub + '/' if sub else ''}{stem}"
            p = R / f"data/{NS}/structures/{rel}.nbt"; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)
            elems.append({"weight": int(weights.get(stem, 1)), "element": {"element_type": "minecraft:single_pool_element",
                          "location": f"{NS}:{rel}", "processors": processors, "projection": "rigid"}})
        W(f"data/{NS}/worldgen/template_pool/modular/{name}/{sub or 'start'}.json", {"fallback": "minecraft:empty", "elements": elems})

    start = pieces(folder)
    if not start:
        start = {"placeholder": placeholder(name)}
        (folder / "placeholder.nbt").write_bytes(start["placeholder"])
    pool("", start)
    for sub in sorted(p for p in folder.iterdir() if p.is_dir()):
        files = pieces(sub)
        if files: pool(sub.name, files)

    anchor = cfg.get("anchor", "plains_center")
    off = cfg.get("offset", [0, 0, 0])
    gateway = anchor in ("gateway", "chunk")
    W(f"data/{NS}/worldgen/structure/modular/{name}.json", {
        "type": f"{NS}:anchored", "anchor": anchor, "offset": off, "yield_to_lore": bool(cfg.get("yield_to_lore", gateway)),
        "start_pool": f"{NS}:modular/{name}/start", "size": int(cfg.get("jigsaw_depth", 1)),
        "max_distance_from_center": int(cfg.get("max_distance_from_center", 80)),
        "biomes": f"#{NS}:has_structure/highland_gateway" if gateway else f"#{NS}:has_structure/island",
        "step": cfg.get("step", "surface_structures"), "terrain_adaptation": cfg.get("terrain", "beard_thin"), "spawn_overrides": {}})
    placement = ({"type": "minecraft:random_spread", "salt": 734615021, "spacing": 44, "separation": 14} if gateway else
                 {"type": "minecraft:random_spread", "salt": sum(ord(c) * 31 ** i for i, c in enumerate(name)) % 2_000_000_000,
                  "spacing": 1, "separation": 0})
    W(f"data/{NS}/worldgen/structure_set/modular/{name}.json", {"structures": [{"structure": f"{NS}:modular/{name}", "weight": 1}], "placement": placement})
    summary.append(f"{name} @ {anchor} ({', '.join(start)})")

# the island-side companion is placed by id when the island gateway is built
print("structures:", "; ".join(summary))
