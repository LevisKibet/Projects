"""Voxelizes a textured .glb model into a Minecraft structure .nbt (1.20.1).

    python3 glb2nbt.py model.glb out.nbt --size 48 [--axis longest|height] [--palette building|stone] [--solid]

--size     blocks along the chosen axis (longest horizontal side by default, or the height)
--palette  'building' matches the model's colours to stone, brick, wood, glass and so on;
           'stone' uses only stone-type blocks (stone, andesite, deepslate, cobble, ...), shaded by brightness
--solid    fill the inside of closed shapes (for swords and other solid props)
"""
import argparse, io, json, struct, sys, pathlib
import numpy as np
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
_src = (pathlib.Path(__file__).resolve().parent.parent / "gen_structures.py").read_text()
exec(_src[:_src.index("# ------------------------------------------------------------------ build")])   # NBT writer + template()

CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}

def load_glb(path):
    b = open(path, "rb").read()
    jl = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + jl])
    off = 20 + jl
    binl = struct.unpack("<I", b[off:off + 4])[0]
    blob = b[off + 8:off + 8 + binl]

    def view(i):
        v = j["bufferViews"][i]; s = v.get("byteOffset", 0); return blob[s:s + v["byteLength"]], v.get("byteStride")

    def acc(i):
        a = j["accessors"][i]
        data, stride = view(a["bufferView"])
        dt = np.dtype(CT[a["componentType"]]); n = NC[a["type"]]; cnt = a["count"]; o = a.get("byteOffset", 0)
        if stride and stride != dt.itemsize * n:
            raw = np.frombuffer(data, np.uint8)[o:o + stride * cnt].reshape(cnt, stride)[:, :dt.itemsize * n]
            arr = np.frombuffer(raw.tobytes(), dt).reshape(cnt, n)
        else:
            arr = np.frombuffer(data, dt, cnt * n, o).reshape(cnt, n)
        arr = arr.astype(np.float64)
        if a.get("normalized"):
            arr /= np.iinfo(dt).max
        return arr

    images = []
    for im in j.get("images", []):
        data, _ = view(im["bufferView"])
        img = Image.open(io.BytesIO(data)).convert("RGBA")
        if max(img.size) > 1024: img = img.resize((min(1024, img.size[0]), min(1024, img.size[1])))
        images.append(np.asarray(img).astype(np.float32) / 255)

    def tex_of(mat):
        pbr = mat.get("pbrMetallicRoughness", {})
        t = pbr.get("baseColorTexture")
        img = None
        if t is not None:
            src = j["textures"][t["index"]].get("source")
            if src is not None: img = images[src]
        return img, np.array(pbr.get("baseColorFactor", [1, 1, 1, 1]))

    def node_mat(n):
        if "matrix" in n: return np.array(n["matrix"]).reshape(4, 4).T
        T = np.eye(4); T[:3, 3] = n.get("translation", [0, 0, 0])
        x, y, z, w = n.get("rotation", [0, 0, 0, 1])
        R = np.eye(4); R[:3, :3] = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                                    [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                                    [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
        S = np.diag(list(n.get("scale", [1, 1, 1])) + [1])
        return T @ R @ S

    prims = []
    def walk(ni, parent):
        n = j["nodes"][ni]; M = parent @ node_mat(n)
        if "mesh" in n:
            for p in j["meshes"][n["mesh"]]["primitives"]:
                if p.get("mode", 4) != 4: continue
                P = acc(p["attributes"]["POSITION"])
                P = (np.c_[P, np.ones(len(P))] @ M.T)[:, :3]
                UV = acc(p["attributes"]["TEXCOORD_0"]) if "TEXCOORD_0" in p["attributes"] else np.zeros((len(P), 2))
                I = acc(p["indices"]).astype(np.int64).reshape(-1, 3) if "indices" in p else np.arange(len(P)).reshape(-1, 3)
                img, fac = tex_of(j["materials"][p["material"]]) if "material" in p else (None, np.ones(4))
                prims.append((P, UV, I, img, fac))
        for c in n.get("children", []): walk(c, M)
    scene = j["scenes"][j.get("scene", 0)] if "scenes" in j else {"nodes": list(range(len(j["nodes"])))}
    for ni in scene["nodes"]: walk(ni, np.eye(4))
    return prims

def sample(prims, scale, origin, step):
    """Points (in block units) on every triangle, spaced < step apart, with their sampled RGBA colour."""
    pts, cols = [], []
    for P, UV, I, img, fac in prims:
        V = (P - origin) * scale
        A, B, C = V[I[:, 0]], V[I[:, 1]], V[I[:, 2]]
        ua, ub, uc = UV[I[:, 0]], UV[I[:, 1]], UV[I[:, 2]]
        edge = np.maximum.reduce([np.linalg.norm(B - A, axis=1), np.linalg.norm(C - B, axis=1), np.linalg.norm(A - C, axis=1)])
        n = np.clip(np.ceil(edge / step).astype(int), 1, 400)
        for k in np.unique(n):
            sel = n == k
            g = np.array([(a / k, b / k) for a in range(k + 1) for b in range(k + 1 - a)])      # barycentric grid
            w0, w1 = g[:, 0], g[:, 1]; w2 = 1 - w0 - w1
            p = A[sel][:, None] * w2[None, :, None] + B[sel][:, None] * w0[None, :, None] + C[sel][:, None] * w1[None, :, None]
            uv = ua[sel][:, None] * w2[None, :, None] + ub[sel][:, None] * w0[None, :, None] + uc[sel][:, None] * w1[None, :, None]
            p = p.reshape(-1, 3); uv = uv.reshape(-1, 2)
            if img is not None:
                h, w = img.shape[:2]
                x = (np.mod(uv[:, 0], 1) * (w - 1)).astype(int); y = (np.mod(uv[:, 1], 1) * (h - 1)).astype(int)
                c = img[y, x] * fac
            else:
                c = np.tile(fac, (len(p), 1))
            pts.append(p); cols.append(c)
    return np.concatenate(pts), np.concatenate(cols)

# ------------------------------------------------------------------ palettes (block, average colour)
BUILDING = {
    "stone_bricks": (122, 121, 122), "mossy_stone_bricks": (115, 121, 105), "cracked_stone_bricks": (118, 117, 118),
    "cobblestone": (128, 127, 128), "mossy_cobblestone": (110, 118, 95), "stone": (125, 125, 125), "andesite": (136, 136, 137),
    "polished_andesite": (132, 134, 133), "smooth_stone": (159, 159, 159), "diorite": (189, 188, 189), "calcite": (223, 224, 220),
    "deepslate_bricks": (70, 70, 71), "deepslate_tiles": (54, 54, 55), "cobbled_deepslate": (77, 77, 80), "polished_blackstone": (53, 49, 57),
    "tuff": (108, 109, 103), "bricks": (150, 97, 83), "granite": (149, 103, 86), "sandstone": (216, 203, 155),
    "dark_oak_planks": (67, 43, 20), "spruce_planks": (115, 85, 49), "oak_planks": (162, 131, 79), "stripped_dark_oak_log": (96, 76, 49),
    "spruce_log": (59, 38, 17), "dirt": (134, 96, 67), "moss_block": (89, 110, 45), "white_terracotta": (210, 178, 161),
    "light_gray_terracotta": (135, 107, 98), "gray_terracotta": (58, 42, 36), "terracotta": (152, 94, 68),
    "gold_block": (246, 208, 61), "iron_block": (220, 220, 220), "copper_block": (192, 107, 79), "oxidized_copper": (82, 162, 132),
    "blue_terracotta": (74, 60, 91), "red_terracotta": (143, 61, 47), "black_concrete": (8, 10, 15), "white_concrete": (207, 213, 214),
}
STONE = {  # stone-type blocks only, for the giant swords
    "polished_blackstone": (53, 49, 57), "deepslate_tiles": (54, 54, 55), "deepslate_bricks": (70, 70, 71), "cobbled_deepslate": (77, 77, 80),
    "polished_deepslate": (72, 72, 73), "tuff": (108, 109, 103), "stone_bricks": (122, 121, 122), "cobblestone": (128, 127, 128),
    "stone": (125, 125, 125), "andesite": (136, 136, 137), "polished_andesite": (132, 134, 133), "smooth_stone": (159, 159, 159),
    "diorite": (189, 188, 189), "polished_diorite": (192, 193, 194), "calcite": (223, 224, 220),
}

def to_lab(rgb):
    c = np.asarray(rgb, np.float64) / 255
    c = np.where(c > 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)
    M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = c @ M.T / np.array([0.9505, 1.0, 1.089])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)

def match(colors, palette, stone_only):
    names = list(palette); lab = to_lab([palette[k] for k in names])
    c = to_lab(np.clip(colors[:, :3], 0, 1) * 255)
    if stone_only:                       # shade by brightness only, so the sword reads as carved stone
        d = np.abs(c[:, None, 0] - lab[None, :, 0]) + 0.15 * np.linalg.norm(c[:, None, 1:] - lab[None, :, 1:], axis=2)
    else:
        d = np.linalg.norm((c[:, None] - lab[None]) * np.array([1.0, 1.4, 1.4]), axis=2)
    return np.array(names)[np.argmin(d, axis=1)]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("glb"); ap.add_argument("out")
    ap.add_argument("--size", type=float, required=True)
    ap.add_argument("--axis", default="longest", choices=["longest", "height"])
    ap.add_argument("--palette", default="building", choices=["building", "stone"])
    ap.add_argument("--solid", action="store_true")
    ap.add_argument("--up", default="y", help="model axis that points up (y, z, -y, ...)")
    ap.add_argument("--minw", type=float, default=0, help="stretch the width to at least this many blocks")
    ap.add_argument("--mind", type=float, default=0, help="stretch the depth to at least this many blocks")
    ap.add_argument("--align", action="store_true", help="stand a long thin model (a sword) upright, hilt at the top")
    a = ap.parse_args()

    prims = load_glb(a.glb)
    if a.up != "y":
        ax = {"x": 0, "y": 1, "z": 2}[a.up[-1]]; sgn = -1 if a.up.startswith("-") else 1
        for i, (P, UV, I, img, fac) in enumerate(prims):
            Q = P.copy(); Q[:, 1] = sgn * P[:, ax]; Q[:, ax] = -sgn * P[:, 1] if ax != 1 else Q[:, ax]
            prims[i] = (Q, UV, I, img, fac)
    if a.align:
        allP = np.concatenate([p[0] for p in prims]); c0 = allP.mean(0)
        _, _, Vt = np.linalg.svd(allP[::max(1, len(allP) // 20000)] - c0, full_matrices=False)
        R = np.stack([Vt[1], Vt[0], Vt[2]])              # longest direction -> up
        if np.linalg.det(R) < 0: R[2] = -R[2]
        prims = [((P - c0) @ R.T, UV, I, img, fac) for P, UV, I, img, fac in prims]
        allP = np.concatenate([p[0] for p in prims])
        y = allP[:, 1]; lo, hi = y.min(), y.max()
        def spread(m): q = allP[m]; return np.ptp(q[:, 0]) if len(q) else 0
        upper = max(spread((y > lo + (hi - lo) * k / 10) & (y <= lo + (hi - lo) * (k + 1) / 10)) for k in range(5, 10))
        lower = max(spread((y > lo + (hi - lo) * k / 10) & (y <= lo + (hi - lo) * (k + 1) / 10)) for k in range(0, 5))
        if lower > upper:                                  # the crossguard is the widest part: keep it at the top
            F = np.diag([1.0, -1.0, -1.0])
            prims = [(P @ F, UV, I, img, fac) for P, UV, I, img, fac in prims]
    allP = np.concatenate([p[0] for p in prims])
    mn, mx = allP.min(0), allP.max(0); ext = mx - mn
    ref = ext[1] if a.axis == "height" else max(ext[0], ext[2])
    scale = a.size / ref
    if a.minw or a.mind:                 # chunkier props: stretch thin axes so they read at block scale
        sx = max(1.0, a.minw / (ext[0] * scale)) if a.minw else 1.0
        sz = max(1.0, a.mind / (ext[2] * scale)) if a.mind else 1.0
        S = np.array([sx, 1.0, sz]); ctr = (mn + mx) / 2
        prims = [((P - ctr) * S + ctr, UV, I, img, fac) for P, UV, I, img, fac in prims]
        allP = np.concatenate([p[0] for p in prims]); mn = allP.min(0)
    pts, cols = sample(prims, scale, mn, 0.35)
    vox = np.floor(pts).astype(np.int64)
    dims = vox.max(0) + 1
    key = (vox[:, 0] * dims[1] + vox[:, 1]) * dims[2] + vox[:, 2]
    order = np.argsort(key); key = key[order]; cols = cols[order]
    uk, start, cnt = np.unique(key, return_index=True, return_counts=True)
    csum = np.add.reduceat(cols, start, axis=0) / cnt[:, None]
    glass = csum[:, 3] < 0.5
    names = match(csum, STONE if a.palette == "stone" else BUILDING, a.palette == "stone")
    names = np.where(glass, "glass", names)
    untextured = all(p[3] is None for p in prims)
    if untextured and a.palette == "building":
        # a plain grey model: weathered castle stone - bricks with cracks and moss (mossier near the ground), cobble footings
        ys_ = (uk // dims[2]) % dims[1]
        h = ((uk * 2654435761) % 1000) / 1000.0
        low = ys_ < max(3, dims[1] * 0.12)
        moss = 0.10 + 0.20 * (ys_ < dims[1] * 0.3)
        names = np.where(h < 0.55, "stone_bricks", np.where(h < 0.70, "cracked_stone_bricks",
                np.where(h < 0.70 + moss, "mossy_stone_bricks", np.where(h < 0.93, "cobblestone", "andesite"))))
        names = np.where(low & (h > 0.5), np.where(h > 0.8, "mossy_cobblestone", "cobblestone"), names)
    xs = uk // (dims[1] * dims[2]); ys = (uk // dims[2]) % dims[1]; zs = uk % dims[2]
    grid = {}
    for x, y, z, n in zip(xs.tolist(), ys.tolist(), zs.tolist(), names.tolist()): grid[(x, y, z)] = n
    if a.solid:                          # fill enclosed space: anything not reachable from outside
        occ = np.zeros(dims + 2, bool); occ[xs + 1, ys + 1, zs + 1] = True
        out = np.zeros_like(occ); out[0, :, :] = True
        from collections import deque
        q = deque([(0, 0, 0)]); out[0, 0, 0] = True; out[:] = False; out[0, 0, 0] = True
        while q:
            x, y, z = q.popleft()
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                X, Y, Z = x + dx, y + dy, z + dz
                if 0 <= X < occ.shape[0] and 0 <= Y < occ.shape[1] and 0 <= Z < occ.shape[2] and not occ[X, Y, Z] and not out[X, Y, Z]:
                    out[X, Y, Z] = True; q.append((X, Y, Z))
        inner = ~occ & ~out
        for x, y, z in zip(*np.nonzero(inner)):
            # interior takes the nearest surface block below or beside it
            grid[(x - 1, y - 1, z - 1)] = grid.get((x - 2, y - 1, z - 1)) or grid.get((x - 1, y - 2, z - 1)) or "stone"
    blocks = [(x, y, z, "minecraft:" + n, {}, None) for (x, y, z), n in sorted(grid.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0]))]
    open(a.out, "wb").write(template(tuple(int(d) for d in dims), blocks))
    from collections import Counter
    top = Counter(grid.values()).most_common(8)
    print(f"{pathlib.Path(a.glb).name}: {int(dims[0])}x{int(dims[1])}x{int(dims[2])} (w x h x d), {len(grid)} blocks; {', '.join(f'{k} {v}' for k, v in top)}")

if __name__ == "__main__":
    main()
