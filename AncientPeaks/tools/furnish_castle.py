"""Furnishes the castle's roofed spaces without touching any existing block: bookshelves, armour stands, chests and
barrels against the walls, faint candle and lantern light, cobwebs, and green growth on inner walls and ceilings.
    python3 furnish_castle.py in.nbt out.nbt"""
import sys, random, numpy as np
from nbtedit import Template, C, S, I, L, Tag

t = Template(sys.argv[1]); r = random.Random(17)
solid = t.solid_mask(); occ = t.occupied()
X, Y, Z = t.X, t.Y, t.Z
# roofed: something solid overhead within 16 blocks
roof_dist = np.full((X, Y, Z), 99, np.int16)
d = np.full((X, Z), 99, np.int16)
for y in range(Y - 1, -1, -1):
    d = np.where(solid[:, y, :], 0, np.minimum(d + 1, 99)); roof_dist[:, y, :] = d
free = ~occ
floor = free.copy(); floor[:, 1:, :] &= solid[:, :-1, :]; floor[:, 0, :] = False
roofed = (roof_dist <= 16) & (roof_dist >= 2)
spot = floor & roofed
P = np.pad(solid, ((1, 1), (0, 0), (1, 1)))
wallN = P[1:-1, :, :-2]; wallS = P[1:-1, :, 2:]; wallW = P[:-2, :, 1:-1]; wallE = P[2:, :, 1:-1]
against = wallN | wallS | wallW | wallE
FACE_AWAY = lambda x, y, z: ("south" if wallN[x, y, z] else "north" if wallS[x, y, z] else "east" if wallW[x, y, z] else "west")
DIRS = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
taken = np.zeros((X, Y, Z), bool)
def near_taken(x, y, z, rad=2):
    return taken[max(0, x - rad):x + rad + 1, y, max(0, z - rad):z + rad + 1].any()

LOOT = ["minecraft:chests/simple_dungeon", "minecraft:chests/village/village_weaponsmith", "minecraft:chests/village/village_armorer",
        "minecraft:chests/village/village_toolsmith", "minecraft:chests/village/village_temple"]
ARMOR = [("chainmail", 0.5), ("iron", 0.35), ("golden", 0.1), ("diamond", 0.05)]
def armor_item(slot):
    mat = r.choices([a for a, _ in ARMOR], [w for _, w in ARMOR])[0]
    if r.random() < 0.3: return C({})                                   # a piece long gone
    return C({"id": S(f"minecraft:{mat}_{slot}"), "Count": Tag(1, 1), "tag": C({"Damage": I(r.randint(40, 160))})})
stats = dict(bookshelf=0, chest=0, barrel=0, armor_stand=0, light=0, cobweb=0, leaves=0, vine=0, moss=0, table=0)

xs, ys, zs = np.nonzero(spot)
order = r.sample(range(len(xs)), len(xs))
for i in order:
    x, y, z = int(xs[i]), int(ys[i]), int(zs[i])
    if taken[x, y, z]: continue
    p = r.random()
    if against[x, y, z]:
        face = FACE_AWAY(x, y, z)
        if p < 0.06 and not near_taken(x, y, z, 1):                      # bookshelves in stacks of 1-3
            h = r.choice([1, 2, 2, 3])
            for k in range(h):
                if not free[x, y + k, z] or not t.set(x, y + k, z, "bookshelf"): break
            stats["bookshelf"] += 1; taken[x, y, z] = True
            if r.random() < 0.3: t.set(x, y + h, z, "candle", candles=str(r.randint(1, 3)), lit="false", waterlogged="false")
        elif p < 0.072 and not near_taken(x, y, z, 6):
            t.set(x, y, z, "chest", nbt=C({"id": S("minecraft:chest"), "LootTable": S(r.choice(LOOT))}), facing=face, type="single", waterlogged="false")
            stats["chest"] += 1; taken[x, y, z] = True
        elif p < 0.09 and not near_taken(x, y, z, 2):
            t.set(x, y, z, "barrel", facing="up", open="false"); stats["barrel"] += 1; taken[x, y, z] = True
            if r.random() < 0.4 and free[x, y + 1, z]: t.set(x, y + 1, z, "barrel", facing="up", open="false")
        elif p < 0.098 and not near_taken(x, y, z, 5):                  # an armour stand keeping watch
            yaw = {"north": 180.0, "south": 0.0, "west": 90.0, "east": -90.0}[face]
            t.add_entity(x, y, z, C({"id": S("minecraft:armor_stand"), "ShowArms": Tag(1, 1), "NoBasePlate": Tag(1, 0),
                "Rotation": L(5, [Tag(5, yaw), Tag(5, 0.0)]),
                "ArmorItems": L(10, [armor_item("boots"), armor_item("leggings"), armor_item("chestplate"), armor_item("helmet")]),
                "HandItems": L(10, [C({"id": S(r.choice(["minecraft:iron_sword", "minecraft:stone_sword", "minecraft:shield", "minecraft:iron_axe"])),
                                       "Count": Tag(1, 1), "tag": C({"Damage": I(r.randint(20, 120))})}) if r.random() < 0.6 else C({}), C({})])}))
            stats["armor_stand"] += 1; taken[x, y, z] = True
        elif p < 0.11:                                                   # faint light: a candle or a lantern on the floor
            if r.random() < 0.7: t.set(x, y, z, "candle", candles=str(r.randint(1, 4)), lit="true", waterlogged="false")
            else: t.set(x, y, z, "soul_lantern" if r.random() < 0.4 else "lantern", hanging="false", waterlogged="false")
            stats["light"] += 1; taken[x, y, z] = True
        elif p < 0.16:
            t.set(x, y, z, "moss_carpet"); stats["moss"] += 1
    else:
        if p < 0.004 and not near_taken(x, y, z, 4) and free[x, y + 1, z]:    # a table with a candle
            t.set(x, y, z, "dark_oak_fence"); t.set(x, y + 1, z, "spruce_pressure_plate", powered="false")
            t.set(x, y + 2, z, "candle", candles="2", lit="true", waterlogged="false") if r.random() < 0.5 else None
            for dx, dz, f in ((1, 0, "west"), (-1, 0, "east")):
                if r.random() < 0.6 and 0 <= x + dx < X and free[x + dx, y, z] and floor[x + dx, y, z]:
                    t.set(x + dx, y, z, "spruce_stairs", facing=f, half="bottom", shape="straight", waterlogged="false")
            stats["table"] += 1; taken[x, y, z] = True
        elif p < 0.09:
            t.set(x, y, z, "moss_carpet"); stats["moss"] += 1

# ceilings: hanging lanterns (rare), cobwebs in corners, leaves creeping across the roof inside
ceil = free & (roof_dist == 1)
ceil &= (np.arange(Y)[None, :, None] > 3)
for x, y, z in zip(*np.nonzero(ceil)):
    p = r.random()
    if p < 0.0035 and y >= 3 and free[x, y - 1, z] and free[x, y - 2, z]:
        t.set(x, y, z, "chain", axis="y", waterlogged="false"); t.set(x, y - 1, z, "lantern", hanging="true", waterlogged="false"); stats["light"] += 1
    elif p < 0.03 and against[x, y, z]:
        t.set(x, y, z, "cobweb"); stats["cobweb"] += 1
    elif p < 0.065:
        if t.set(x, y, z, r.choice(["azalea_leaves", "flowering_azalea_leaves", "oak_leaves", "dark_oak_leaves"]), persistent="true", distance="1", waterlogged="false"):
            stats["leaves"] += 1
            if r.random() < 0.5 and free[x, y - 1, z]:
                t.set(x, y - 1, z, "azalea_leaves", persistent="true", distance="1", waterlogged="false")
# vines down the inner walls
inner = free & roofed
for x, y, z in zip(*np.nonzero(inner)):
    if r.random() > 0.006 or y < 2: continue
    for face, (dx, dz) in DIRS.items():
        wx, wz = x + dx, z + dz
        if 0 <= wx < X and 0 <= wz < Z and solid[wx, y, wz]:
            for j in range(r.randint(2, 6)):
                yy = y - j
                if yy < 1 or not free[x, yy, z] or not solid[wx, yy, wz] or t.name(x, yy, z) not in (None, "air"): break
                if t.set(x, yy, z, "vine", **{f: f == face for f in DIRS}, up="false"): stats["vine"] += 1
            break
t.save(sys.argv[2])
print(stats, "interior floor spots:", int(spot.sum()))
