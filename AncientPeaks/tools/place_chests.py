"""Puts loot chests into a structure .nbt at the spots listed in a JSON file (after the ruin pass).
    python3 place_chests.py in.nbt chests.json out.nbt [loot_table]"""
import sys, json, pathlib, numpy as np
HERE = pathlib.Path(__file__).resolve().parent
_src = (HERE.parent / "gen_structures.py").read_text()
exec(_src[:_src.index("# ------------------------------------------------------------------ build")])
src, cj, out = sys.argv[1:4]
loot = sys.argv[4] if len(sys.argv) > 4 else "minecraft:chests/village/village_weaponsmith"
root = read_nbt(open(src, "rb").read()).v
X, Y, Z = [t.v for t in root["size"].v[1]]
pal = root["palette"].v[1]
occupied = {}
for b in root["blocks"].v[1]:
    occupied[tuple(t.v for t in b.v["pos"].v[1])] = b
def name_at(p):
    b = occupied.get(p)
    return None if b is None else pal[b.v["state"].v].v["Name"].v
placed = 0
blocks = root["blocks"].v[1]
for c in json.load(open(cj)):
    x, y, z = c["pos"]
    # the ruin may have moved things: find the nearest free floor spot around it
    spot = None
    for r in range(0, 4):
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                for dy in range(-r, r + 1):
                    p = (x + dx, y + dy, z + dz)
                    nm = name_at(p)
                    below = name_at((p[0], p[1] - 1, p[2]))
                    if (nm is None or nm in ("minecraft:moss_carpet",)) and below and "leaves" not in below and "vine" not in below and "carpet" not in below:
                        spot = p; break
                if spot: break
            if spot: break
        if spot: break
    if not spot: continue
    pal.append(C({"Name": S("minecraft:chest"), "Properties": C({"facing": S(c["facing"]), "type": S("single"), "waterlogged": S("false")})}))
    entry = C({"pos": ints(*spot), "state": I(len(pal) - 1),
               "nbt": C({"id": S("minecraft:chest"), "LootTable": S(loot)})})
    if spot in occupied: blocks.remove(occupied[spot])
    blocks.append(entry); occupied[spot] = entry; placed += 1
open(out, "wb").write(write_nbt(C(root)))
print("chests placed:", placed)
