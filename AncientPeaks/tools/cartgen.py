"""Raided supply carts (1.20.1 structure .nbt): the supplies the raiders robbed on the roads, with a lore book left
lying on a crate (an item frame laid flat; the mod's loot processor puts the book in it) and a looted chest.
Template layer 0 = footing, layer 1 = the ground, carts from layer 2.

    python3 cartgen.py OUTDIR
"""
import sys, random, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from userimport import save
from nbtedit import Tag, C, S, I, L, ints

def frame_up(x, y, z):
    """An item frame lying face up on the block below (x, y-1, z) - empty: the book is put in as it's placed."""
    nbt = C({"id": S("minecraft:item_frame"), "Facing": Tag(1, 1), "ItemRotation": Tag(1, 1), "Invisible": Tag(1, 0), "Fixed": Tag(1, 0),
             "Item": C({"id": S("minecraft:written_book"), "Count": Tag(1, 1)}), "Rotation": L(5, [Tag(5, 0.0), Tag(5, -90.0)]),
             "TileX": I(x), "TileY": I(y), "TileZ": I(z)})
    return C({"pos": L(6, [Tag(6, x + 0.5), Tag(6, y + 0.03125), Tag(6, z + 0.5)]), "blockPos": ints(x, y, z), "nbt": nbt})

def ground(b, r, x0, x1, z0, z1):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            b[(x, 0, z)] = ("minecraft:dirt", {}, None)
            g = r.choice(["minecraft:coarse_dirt", "minecraft:dirt_path", "minecraft:grass_block", "minecraft:grass_block", "minecraft:gravel"])
            b[(x, 1, z)] = (g, {"snowy": "false"} if g == "minecraft:grass_block" else {}, None)

def put(b, x, y, z, n, **p): b[(x, y, z)] = ("minecraft:" + n, {k: str(v).lower() for k, v in p.items()}, None)
def chest(b, x, y, z, facing): b[(x, y, z)] = ("minecraft:chest", {"facing": facing, "type": "single", "waterlogged": "false"}, C({"id": S("minecraft:chest")}))
def barrel(b, x, y, z, facing="up"): b[(x, y, z)] = ("minecraft:barrel", {"facing": facing, "open": "false"}, C({"id": S("minecraft:barrel")}))

def cart_robbed(seed=1):
    """Still on its wheels, the tailboard down, the load gone through: a chest broken open, barrels rolled off."""
    r = random.Random(seed); b = {}; ents = []
    ground(b, r, -3, 6, -3, 9)
    for x in range(0, 3):                                             # the bed on its axles
        for z in range(0, 5): put(b, x, 2, z, "spruce_slab", type="top", waterlogged="false")
    for z in (1, 3):
        put(b, -1, 2, z, "dark_oak_trapdoor", facing="west", half="bottom", open="true", powered="false", waterlogged="false")
        put(b, 3, 2, z, "dark_oak_trapdoor", facing="east", half="bottom", open="true", powered="false", waterlogged="false")
    for z in range(0, 5):                                             # side boards
        put(b, -0, 3, z, "spruce_trapdoor", facing="east", half="bottom", open="true", powered="false", waterlogged="false") if r.random() < 0.8 else None
        put(b, 2, 3, z, "spruce_trapdoor", facing="west", half="bottom", open="true", powered="false", waterlogged="false") if r.random() < 0.8 else None
    put(b, 1, 3, 0, "spruce_trapdoor", facing="south", half="bottom", open="true", powered="false", waterlogged="false")
    put(b, 1, 2, 5, "spruce_fence"); put(b, 1, 2, 6, "spruce_fence"); put(b, 1, 2, 7, "spruce_slab", type="bottom", waterlogged="false")   # the shaft
    chest(b, 1, 3, 3, "south")
    put(b, 1, 3, 1, "hay_block", axis="x")
    barrel(b, 4, 2, 2, "east"); barrel(b, 5, 2, 0, "north"); put(b, -2, 2, 4, "hay_block", axis="z")
    barrel(b, -2, 2, 1, "up"); ents.append(frame_up(-2, 3, 1))                # the book left lying on a crate
    put(b, 4, 2, 5, "composter", level="3"); put(b, 3, 2, 7, "cobweb")
    for _ in range(4):
        x, z = r.randint(-3, 6), r.randint(-3, 9)
        if (x, 2, z) not in b: put(b, x, 2, z, r.choice(["wheat", "potatoes", "carrots"]) if False else "grass")
    return b, ents

def cart_overturned(seed=2):
    """On its side in the ditch, wheels in the air, sacks and barrels everywhere."""
    r = random.Random(seed); b = {}; ents = []
    ground(b, r, -3, 7, -3, 8)
    for y in (2, 3, 4):                                               # the bed, standing on edge
        for z in range(0, 5): put(b, 0, y, z, "spruce_planks" if y < 4 else "spruce_slab", **({} if y < 4 else {"type": "bottom", "waterlogged": "false"}))
    for z in (1, 3):                                                  # wheels up in the air on the far side
        put(b, 1, 3, z, "dark_oak_trapdoor", facing="east", half="bottom", open="true", powered="false", waterlogged="false")
    put(b, 1, 2, 2, "stripped_dark_oak_log", axis="z")                # an axle
    put(b, -1, 2, 6, "spruce_fence"); put(b, -2, 2, 7, "spruce_slab", type="bottom", waterlogged="false")
    chest(b, 2, 2, 4, "east")
    barrel(b, 3, 2, 1, "south"); barrel(b, 4, 2, 3, "west"); barrel(b, 2, 2, 0, "up"); ents.append(frame_up(2, 3, 0))
    put(b, 5, 2, 5, "hay_block", axis="x"); put(b, 6, 2, 5, "hay_block", axis="x"); put(b, 5, 3, 5, "hay_block", axis="y")
    put(b, 3, 2, 6, "composter", level="5")
    for x, z in ((4, 0), (6, 2), (3, 7)): put(b, x, 2, z, "dead_bush")
    return b, ents

def cart_burnt(seed=3):
    """Burnt where it stood: a black frame, the embers still warm, the raiders' fire beside it."""
    r = random.Random(seed); b = {}; ents = []
    ground(b, r, -3, 6, -3, 8)
    for x in range(-3, 7):
        for z in range(-3, 9):
            if r.random() < 0.25: put(b, x, 1, z, r.choice(["coarse_dirt", "gravel"]))
    for x in range(0, 3):
        for z in range(0, 5):
            if r.random() < 0.6: put(b, x, 2, z, r.choice(["dark_oak_slab", "dark_oak_slab", "blackstone_slab"]), type="top", waterlogged="false")
    for z in (1, 3):
        if r.random() < 0.7: put(b, -1, 2, z, "dark_oak_trapdoor", facing="west", half="bottom", open="true", powered="false", waterlogged="false")
        put(b, 3, 2, z, "dark_oak_trapdoor", facing="east", half="bottom", open="true", powered="false", waterlogged="false")
    for z in (0, 4): put(b, 0, 3, z, "dark_oak_fence"); put(b, 2, 3, z, "dark_oak_fence")
    put(b, 1, 1, 6, "coal_block"); put(b, 1, 2, 6, "campfire", facing="north", lit="false", signal_fire="false", waterlogged="false")
    for x, z in ((0, 7), (2, 7)): put(b, x, 2, z, "cobblestone_slab", type="bottom", waterlogged="false")
    chest(b, 4, 2, 2, "west")
    barrel(b, 4, 2, 4, "up"); ents.append(frame_up(4, 3, 4))
    put(b, -2, 2, 2, "hay_block", axis="z")
    put(b, 1, 3, 2, "lantern", hanging="false", waterlogged="false") if r.random() < 0.5 else None
    return b, ents

if __name__ == "__main__":
    out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    for name, fn in (("cart_robbed", cart_robbed), ("cart_overturned", cart_overturned), ("cart_burnt", cart_burnt)):
        b, e = fn()
        b = {k: v for k, v in b.items() if v is not None}
        save(b, e, out / f"{name}.nbt")
