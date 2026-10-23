"""Fishing docks. The land end is at z=0, the pier runs out along +Z; the walkway is template layer 7 (sea level + 1)."""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from churchgen import *

WALK = 7
def slab(bd, x, y, z, n, t="bottom"): bd.set(x, y, z, n, type=t, waterlogged="false")
def dock(seed, length, platform=False):
    bd = Build(seed); r = bd.r
    W_ = 5                                              # posts at x=0 and x=4, planks x=1..3
    BANK = 6
    # the bank: a stone landing bedded into the shore, mixed cobble, bricks and slabs, steps up to the land
    for x in range(-1, W_ + 1):
        for z in range(0, BANK):
            for y in range(WALK - 4, WALK):
                bd.set(x, y, z, bd.pick([("cobblestone", 4), ("mossy_cobblestone", 3), ("stone", 2), ("andesite", 1)]))
            top = bd.pick([("cobblestone", 3), ("mossy_cobblestone", 3), ("stone_bricks", 2), ("cracked_stone_bricks", 1), ("mossy_stone_bricks", 2),
                           ("cobblestone_slab", 2), ("stone_slab", 2), ("mossy_cobblestone_slab", 1), ("stone_brick_slab", 1), ("andesite_slab", 1), ("smooth_stone_slab", 1)])
            if top.endswith("_slab"): bd.set(x, WALK, z, top, type="bottom", waterlogged="false"); bd.set(x, WALK - 1, z, "cobblestone")
            else: bd.set(x, WALK, z, top) if z < 2 or r.random() < 0.6 else slab(bd, x, WALK, z, "stone_slab")
        if r.random() < 0.8: bd.set(x, WALK + 1, 0, "cobblestone_stairs" if r.random() < 0.6 else "mossy_cobblestone_stairs",
                                    facing="north", half="bottom", shape="straight", waterlogged="false")
    for x in (-1, W_):                                  # low kerb stones at the sides
        for z in range(1, BANK):
            if r.random() < 0.5: bd.set(x, WALK + 1, z, r.choice(["cobblestone_wall", "mossy_cobblestone_wall"]))
    # the pier: 3 planks wide on log piles driven into the seabed
    for z in range(BANK, BANK + length):
        for x in range(1, 4):
            if r.random() < 0.06: continue                                    # a rotten plank gone
            if r.random() < 0.15: slab(bd, x, WALK, z, r.choice(["spruce_slab", "dark_oak_slab"]), "top")
            else: bd.set(x, WALK, z, r.choice(["spruce_planks", "spruce_planks", "dark_oak_planks", "stripped_spruce_wood"]), **({"axis": "z"} if False else {}))
        if (z - BANK) % 4 == 0 or z == BANK + length - 1:
            for x in (0, 4):
                for y in range(0, WALK + 2 if r.random() < 0.7 else WALK + 1): bd.set(x, y, z, "spruce_log", axis="y")
            bd.set(2, WALK - 1, z, "stripped_spruce_log", axis="x")             # cross beam under the boards
            for x in (1, 3): bd.set(x, WALK - 1, z, "stripped_spruce_log", axis="x")
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "stripped_spruce_wood" and not p: bd.b[k] = (n, {"axis": "y"}, nbt)
    end = BANK + length - 1
    bd.set(0, WALK + 2, end, "lantern", hanging="false", waterlogged="false")
    bd.set(1, WALK + 1, end, "barrel", facing="up", open="false"); bd.set(3, WALK + 1, end - 1, "spruce_trapdoor", facing="south", half="bottom", open="false", powered="false", waterlogged="false")
    bd.set(4, WALK + 2, end - 4, "chain", axis="y", waterlogged="false") if (4, WALK + 1, end - 4) in bd.b else None
    if platform:                                       # a side landing for small boats
        for z in range(end - 6, end + 1):
            for x in range(5, 8):
                if r.random() > 0.08: bd.set(x, WALK, z, r.choice(["spruce_planks", "dark_oak_planks"]))
            if (z - (end - 6)) % 3 == 0:
                for y in range(0, WALK + 1): bd.set(8, y, z, "spruce_log", axis="y")
        bd.set(7, WALK + 1, end - 1, "hay_block", axis="x"); bd.set(6, WALK + 1, end, "barrel", facing="north", open="false")
    # weathering and a little green
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "cobblestone" and r.random() < 0.25: bd.b[k] = ("mossy_cobblestone", p, nbt)
    for x in range(-1, W_ + 1):
        for z in range(0, BANK):
            if bd.get(x, WALK + 1, z) is None and r.random() < 0.18: bd.set(x, WALK + 1, z, r.choice(["moss_carpet", "grass", "fern"]))
    for (x, y, z), (n, p, nbt) in list(bd.b.items()):
        if n == "spruce_log" and y < WALK - 1 and r.random() < 0.1:
            for face, dx in (("east", -1), ("west", 1)):
                if bd.get(x + dx, y, z) is None: bd.set(x + dx, y, z, "vine", north="false", south="false", east=str(face == "east").lower(), west=str(face == "west").lower(), up="false")
    return bd

if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    save(dock(61, 22), out / "fishing_dock.nbt")
    save(dock(62, 16, platform=True), out / "boat_landing.nbt")
