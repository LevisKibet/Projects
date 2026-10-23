"""Converts an old MCEdit/WorldEdit .schematic (pre-1.13 numeric block ids) into a 1.20.1 structure .nbt.

    python3 schematic2nbt.py Castle.schematic castle.nbt [--keep-air]

Block ids are translated with legacy_blocks.json (PrismarineJS minecraft-data). Air is skipped by default so the
structure sits on the terrain instead of carving a box into it.
"""
import argparse, json, pathlib, sys, collections
HERE = pathlib.Path(__file__).resolve().parent
_src = (HERE.parent / "gen_structures.py").read_text()
exec(_src[:_src.index("# ------------------------------------------------------------------ build")])   # NBT read/write + template()

RENAMED = {"minecraft:sign": "minecraft:oak_sign", "minecraft:wall_sign": "minecraft:oak_wall_sign"}

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("src"); ap.add_argument("out"); ap.add_argument("--keep-air", action="store_true")
    a = ap.parse_args()
    legacy = json.loads((HERE / "legacy_blocks.json").read_text())["blocks"]
    root = read_nbt(open(a.src, "rb").read()).v
    if "Schematic" in root: root = root["Schematic"].v
    W, H, L = root["Width"].v, root["Height"].v, root["Length"].v
    ids = root["Blocks"].v; data = root["Data"].v
    add = root["AddBlocks"].v if "AddBlocks" in root else None
    blocks, unknown = [], collections.Counter()
    for i in range(W * H * L):
        bid = ids[i] & 0xFF
        if add is not None: bid |= ((add[i >> 1] >> (0 if i & 1 else 4)) & 0xF) << 8
        meta = data[i] & 0xF
        state = legacy.get(f"{bid}:{meta}") or legacy.get(f"{bid}:0")
        if state is None: unknown[bid] += 1; continue
        name, props = parse_state(state)
        name = RENAMED.get(name, name)
        if name in ("minecraft:air", "minecraft:cave_air") and not a.keep_air: continue
        x = i % W; z = (i // W) % L; y = i // (W * L)
        blocks.append((x, y, z, name, props, None))
    open(a.out, "wb").write(template((W, H, L), blocks))
    top = collections.Counter(b[3].split(":")[1] for b in blocks).most_common(10)
    print(f"{pathlib.Path(a.src).name}: {W}x{H}x{L} (w x h x d), {len(blocks)} blocks; {', '.join(f'{k} {v}' for k, v in top)}")
    if unknown: print("unknown ids skipped:", dict(unknown))

if __name__ == "__main__":
    main()
