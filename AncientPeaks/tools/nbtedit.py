"""Edit structure templates in place, keeping every existing block, block entity and entity."""
import pathlib, numpy as np
HERE = pathlib.Path(__file__).resolve().parent
_src = (HERE.parent / "gen_structures.py").read_text()
exec(_src[:_src.index("# ------------------------------------------------------------------ build")])

NONFULL = ("slab", "stairs", "fence", "wall", "pane", "bars", "door", "trapdoor", "sign", "carpet", "button", "plate", "lantern", "torch",
           "candle", "chain", "rod", "ladder", "vine", "leaves", "flower", "grass", "fern", "bush", "pot", "head", "skull", "banner", "bed",
           "lectern", "anvil", "bell", "cobweb", "rail", "sapling", "chest", "barrel", "glass", "air", "water", "lava", "coral", "kelp", "campfire",
           "stand", "table", "frame", "lichen", "scaffolding", "snow", "mushroom", "dripleaf", "azalea", "pickle", "cauldron", "hopper", "grindstone")

class Template:
    def __init__(self, path):
        raw = open(path, "rb").read()
        self.root = read_nbt(raw).v
        self.X, self.Y, self.Z = [t.v for t in self.root["size"].v[1]]
        self.pal = self.root["palette"].v[1]
        self.blocks = self.root["blocks"].v[1]
        if "entities" not in self.root: self.root["entities"] = L(10, [])
        self.names = [p.v["Name"].v.split(":")[1] for p in self.pal]
        self.G = np.full((self.X, self.Y, self.Z), -1, np.int32)
        self.index = {}
        for i, b in enumerate(self.blocks):
            x, y, z = [t.v for t in b.v["pos"].v[1]]
            self.G[x, y, z] = b.v["state"].v
            self.index[(x, y, z)] = i
        self._palkey = {}
        for i, p in enumerate(self.pal):
            props = tuple(sorted((k, t.v) for k, t in p.v["Properties"].v.items())) if "Properties" in p.v else ()
            self._palkey[(p.v["Name"].v, props)] = i

    def name(self, x, y, z):
        if not (0 <= x < self.X and 0 <= y < self.Y and 0 <= z < self.Z): return None
        s = self.G[x, y, z]
        return None if s < 0 else self.names[s]

    def solid_mask(self):
        full = np.array([not any(k in n for k in NONFULL) for n in self.names] + [False])
        return full[self.G]                                   # G=-1 indexes the trailing False

    def occupied(self):
        air = np.array([n in ("air", "cave_air", "structure_void") for n in self.names] + [True])
        return ~air[self.G]

    def state(self, name, **props):
        key = ("minecraft:" + name, tuple(sorted((k, str(v).lower()) for k, v in props.items())))
        if key not in self._palkey:
            e = {"Name": S(key[0])}
            if props: e["Properties"] = C({k: S(v) for k, v in key[1]})
            self.pal.append(C(e)); self.names.append(name); self._palkey[key] = len(self.pal) - 1
        return self._palkey[key]

    def set(self, x, y, z, name, nbt=None, **props):
        """Places a block only where the template has nothing solid (air or unset). Returns True if placed."""
        if not (0 <= x < self.X and 0 <= y < self.Y and 0 <= z < self.Z): return False
        cur = self.G[x, y, z]
        if cur >= 0 and self.names[cur] not in ("air", "cave_air"): return False
        s = self.state(name, **props)
        entry = {"pos": ints(x, y, z), "state": I(s)}
        if nbt is not None: entry["nbt"] = nbt
        if (x, y, z) in self.index: self.blocks[self.index[(x, y, z)]] = C(entry)
        else: self.index[(x, y, z)] = len(self.blocks); self.blocks.append(C(entry))
        self.G[x, y, z] = s
        return True

    def add_entity(self, x, y, z, nbt):
        self.root["entities"].v[1].append(C({"pos": L(6, [Tag(6, x + 0.5), Tag(6, float(y)), Tag(6, z + 0.5)]),
                                             "blockPos": ints(x, y, z), "nbt": nbt}))

    def save(self, path):
        open(path, "wb").write(write_nbt(C(self.root)))
