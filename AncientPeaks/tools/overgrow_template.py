"""Adds greenery to a template without removing or changing anything: vines down the outer walls, moss and leaf tufts
on ledges, bushes round the foot.  python3 overgrow_template.py in.nbt out.nbt [amount 0..1]"""
import sys, random, numpy as np
from collections import deque
from nbtedit import Template

def outside_air(t):
    occ = t.occupied(); out = np.zeros_like(occ)
    q = deque()
    for x in range(t.X):
        for y in range(t.Y):
            for z in range(t.Z):
                if (x in (0, t.X - 1) or y == t.Y - 1 or z in (0, t.Z - 1)) and not occ[x, y, z]:
                    out[x, y, z] = True; q.append((x, y, z))
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            a, b, c = x + dx, y + dy, z + dz
            if 0 <= a < t.X and 0 <= b < t.Y and 0 <= c < t.Z and not occ[a, b, c] and not out[a, b, c]:
                out[a, b, c] = True; q.append((a, b, c))
    return out

def overgrow(t, amount=0.5, seed=5):
    r = random.Random(seed)
    solid = t.solid_mask(); out = outside_air(t)
    DIRS = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
    occ = t.occupied()
    sky = np.flip(np.cumsum(np.flip(occ, 1), 1), 1) == 0
    n = {"vine": 0, "leaves": 0, "moss": 0}
    for x, y, z in zip(*np.nonzero(out)):
        if y < 1: continue
        for face, (dx, dz) in DIRS.items():
            wx, wz = x + dx, z + dz
            if not (0 <= wx < t.X and 0 <= wz < t.Z) or not solid[wx, y, wz]: continue
            if r.random() > 0.05 * amount: break
            for j in range(r.randint(2, 7)):
                yy = y - j
                if yy < 0 or not out[x, yy, z] or not solid[wx, yy, wz] or t.name(x, yy, z) not in (None, "air"): break
                if t.set(x, yy, z, "vine", **{f: f == face for f in DIRS}, up="false"): n["vine"] += 1
            break
    for x, y, z in zip(*np.nonzero(out)):
        if y < 1 or not solid[x, y - 1, z]: continue
        p = r.random()
        foot = y <= 2
        if p < (0.10 if foot else 0.025) * amount:
            leaf = r.choice(["azalea_leaves", "flowering_azalea_leaves", "oak_leaves"])
            for dx, dy, dz in ((0, 0, 0), (1, 0, 0), (0, 0, 1), (-1, 0, 0), (0, 1, 0)):
                a, b, c = x + dx, y + dy, z + dz
                if 0 <= a < t.X and 0 <= b < t.Y and 0 <= c < t.Z and out[a, b, c] and r.random() < 0.7:
                    if t.set(a, b, c, leaf, persistent="true", distance="1", waterlogged="false"): n["leaves"] += 1
        elif p < (0.10 if foot else 0.06) * amount:
            if t.set(x, y, z, "moss_carpet"): n["moss"] += 1
        elif foot and p < 0.2 * amount:
            t.set(x, y, z, r.choice(["fern", "grass", "azure_bluet", "lily_of_the_valley"]))
    return n

if __name__ == "__main__":
    t = Template(sys.argv[1])
    print(overgrow(t, float(sys.argv[3]) if len(sys.argv) > 3 else 0.5))
    t.save(sys.argv[2])
