"""The ruined pedestal ring at the centre of the three swords (Excalibur's stone goes on the centre block)."""
import sys, math, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from churchgen import *
import churchgen

def pedestal(seed=31):
    bd = Build(seed); r = bd.r
    R = 7
    for x in range(-R - 1, R + 2):
        for z in range(-R - 1, R + 2):
            d = math.hypot(x, z)
            if d > R + 0.6: continue
            bd.set(x, -1, z, "cobblestone")
            if d > R - 0.5:                                              # outer step
                face = ("west" if x > 0 else "east") if abs(x) >= abs(z) else ("north" if z > 0 else "south")
                bd.set(x, 0, z, "stone_brick_stairs" if r.random() > 0.2 else "cobblestone", **({"facing": face, "half": "bottom", "shape": "straight", "waterlogged": "false"} if True else {}))
            else:
                ring = abs(d - 4.5) < 0.6
                bd.set(x, 0, z, "chiseled_stone_bricks" if ring and (x + z) % 3 == 0 else bd.pick([("stone_bricks", 5), ("polished_andesite", 3), ("cracked_stone_bricks", 2), ("mossy_stone_bricks", 2)]))
    for k, (n, p, nbt) in list(bd.b.items()):
        if n == "cobblestone" and p: bd.b[k] = (n, {}, nbt)
    # broken pillars around the ring
    for i in range(8):
        a = i * math.pi / 4
        x, z = round(math.cos(a) * 6), round(math.sin(a) * 6)
        h = r.choice([1, 2, 2, 3, 4, 5])
        for y in range(1, h + 1): bd.set(x, y, z, "stone_bricks" if y < h else r.choice(["cracked_stone_bricks", "mossy_stone_bricks", "stone_brick_wall"]))
        if h <= 2 and r.random() < 0.6:                                  # its fallen drum beside it
            bd.set(x + (1 if x >= 0 else -1), 1, z, "stone_brick_slab", type="bottom", waterlogged="false")
    # raised dais and the stone the sword will stand in
    for x in range(-2, 3):
        for z in range(-2, 3):
            edge = max(abs(x), abs(z)) == 2
            if edge:
                face = ("west" if x > 0 else "east") if abs(x) >= abs(z) else ("north" if z > 0 else "south")
                bd.set(x, 1, z, "polished_deepslate_stairs", facing=face, half="bottom", shape="straight", waterlogged="false")
            else:
                bd.set(x, 1, z, "polished_deepslate")
    for x in range(-1, 2):
        for z in range(-1, 2): bd.set(x, 2, z, "deepslate_tiles" if (x or z) else "chiseled_deepslate")
    bd.set(0, 3, 0, "stone")                                             # the stone (Excalibur goes here)
    for x, z in ((-1, -1), (1, 1), (1, -1)):
        bd.set(x, 3, z, "candle", candles=str(r.randint(1, 4)), lit="false", waterlogged="false")
    churchgen.overgrow(bd, vines=0.6, moss=0.35, leaves=3)
    for _ in range(18):
        x, z = r.randint(-6, 6), r.randint(-6, 6)
        if math.hypot(x, z) < 6.5 and bd.get(x, 1, z) is None and max(abs(x), abs(z)) > 2:
            bd.set(x, 1, z, r.choice(["moss_carpet", "moss_carpet", "grass", "fern"]))
    return bd

if __name__ == "__main__":
    save(pedestal(), sys.argv[1])
