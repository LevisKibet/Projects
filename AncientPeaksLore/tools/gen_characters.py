"""Stand-in GeckoLib models and animations for Merlin and Vortigern.

They use the vanilla illager texture layout (64x64), so until a character has its own texture the game shows
the illusioner (Merlin) or the vindicator (Vortigern) skin on them. Replace any of these files with your own
Blockbench (GeckoLib) exports of the same name; keep the animation names, the game asks for them by name.

Writes into src/main/resources/assets/ancientpeaks_lore/{geo,animations}/entity/ and, if given a folder, copies
them there as characters/<name>/<name>.geo.json / .animation.json starter files.
"""
import json, pathlib, sys, shutil

ROOT = pathlib.Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/ancientpeaks_lore"


def cube(origin, size, uv, mirror=False, inflate=0.0):
    c = {"origin": origin, "size": size, "uv": uv}
    if mirror: c["mirror"] = True
    if inflate: c["inflate"] = inflate
    return c


def humanoid(name, extra_bones):
    bones = [
        {"name": "root", "pivot": [0, 0, 0]},
        {"name": "body", "parent": "root", "pivot": [0, 24, 0],
         "cubes": [cube([-4, 12, -3], [8, 12, 6], [16, 20]), cube([-4, 4, -3], [8, 20, 6], [0, 38], inflate=0.25)]},
        {"name": "head", "parent": "body", "pivot": [0, 24, 0],
         "cubes": [cube([-4, 24, -4], [8, 10, 8], [0, 0]), cube([-1, 25, -6], [2, 4, 2], [24, 0])]},
        {"name": "right_arm", "parent": "body", "pivot": [5, 22, 0], "cubes": [cube([4, 12, -2], [4, 12, 4], [40, 46])]},
        {"name": "left_arm", "parent": "body", "pivot": [-5, 22, 0], "cubes": [cube([-8, 12, -2], [4, 12, 4], [40, 46], mirror=True)]},
        {"name": "right_leg", "parent": "root", "pivot": [2, 12, 0], "cubes": [cube([0, 0, -2], [4, 12, 4], [0, 22])]},
        {"name": "left_leg", "parent": "root", "pivot": [-2, 12, 0], "cubes": [cube([-4, 0, -2], [4, 12, 4], [0, 22], mirror=True)]},
    ] + extra_bones
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.{name}", "texture_width": 64, "texture_height": 64,
                        "visible_bounds_width": 3, "visible_bounds_height": 4, "visible_bounds_offset": [0, 1.5, 0]},
        "bones": bones}]}


def player_humanoid(name, extra_bones):
    """The standard 64x64 player-skin layout (with the outer layers), for a skin painted like a player skin."""
    bones = [
        {"name": "root", "pivot": [0, 0, 0]},
        {"name": "body", "parent": "root", "pivot": [0, 24, 0],
         "cubes": [cube([-4, 12, -2], [8, 12, 4], [16, 16]), cube([-4, 12, -2], [8, 12, 4], [16, 32], inflate=0.25)]},
        {"name": "head", "parent": "body", "pivot": [0, 24, 0],
         "cubes": [cube([-4, 24, -4], [8, 8, 8], [0, 0]), cube([-4, 24, -4], [8, 8, 8], [32, 0], inflate=0.5)]},
        {"name": "right_arm", "parent": "body", "pivot": [5, 22, 0],
         "cubes": [cube([4, 12, -2], [4, 12, 4], [40, 16]), cube([4, 12, -2], [4, 12, 4], [40, 32], inflate=0.25)]},
        {"name": "left_arm", "parent": "body", "pivot": [-5, 22, 0],
         "cubes": [cube([-8, 12, -2], [4, 12, 4], [32, 48]), cube([-8, 12, -2], [4, 12, 4], [48, 48], inflate=0.25)]},
        {"name": "right_leg", "parent": "root", "pivot": [2, 12, 0],
         "cubes": [cube([0, 0, -2], [4, 12, 4], [0, 16]), cube([0, 0, -2], [4, 12, 4], [0, 32], inflate=0.25)]},
        {"name": "left_leg", "parent": "root", "pivot": [-2, 12, 0],
         "cubes": [cube([-4, 0, -2], [4, 12, 4], [16, 48]), cube([-4, 0, -2], [4, 12, 4], [0, 48], inflate=0.25)]},
    ] + extra_bones
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.{name}", "texture_width": 64, "texture_height": 64,
                        "visible_bounds_width": 3, "visible_bounds_height": 4, "visible_bounds_offset": [0, 1.5, 0]},
        "bones": bones}]}


MERLIN_BONES = [
    # his staff, held upright in the right hand
    {"name": "staff", "parent": "right_arm", "pivot": [6, 13, 0], "cubes": [cube([5.5, 2, -3.5], [1, 30, 1], [56, 16])]},
]
VORTIGERN_BONES = [
    {"name": "sword", "parent": "right_arm", "pivot": [6, 13, 0],
     "cubes": [cube([5.5, 10, -16], [1, 1, 14], [32, 0]), cube([4.5, 9.5, -3], [3, 2, 1], [56, 0]), cube([5.5, 10, -2], [1, 1, 3], [56, 4])]},
    {"name": "crown", "parent": "head", "pivot": [0, 34, 0],
     "cubes": [cube([-4.5, 34, -4.5], [9, 2, 1], [32, 10]), cube([-4.5, 34, 3.5], [9, 2, 1], [32, 10]),
               cube([-4.5, 34, -3.5], [1, 2, 7], [32, 13]), cube([3.5, 34, -3.5], [1, 2, 7], [32, 13]),
               cube([-0.5, 36, -4.5], [1, 1, 1], [32, 10]), cube([-0.5, 36, 3.5], [1, 1, 1], [32, 10])]},
]


def kf(points):
    """{time: [x, y, z]} with times as strings, the way Blockbench writes them."""
    return {f"{t:g}": v for t, v in points}


def anim(length, loop, bones):
    a = {"animation_length": length, "bones": bones}
    if loop == "loop": a["loop"] = True
    elif loop == "hold": a["loop"] = "hold_on_last_frame"
    return a


def walk(length, legs, arms, arm_base=0):
    h = length / 2
    return {
        "right_leg": {"rotation": kf([(0, [legs, 0, 0]), (h, [-legs, 0, 0]), (length, [legs, 0, 0])])},
        "left_leg": {"rotation": kf([(0, [-legs, 0, 0]), (h, [legs, 0, 0]), (length, [-legs, 0, 0])])},
        "right_arm": {"rotation": kf([(0, [arm_base - arms, 0, 0]), (h, [arm_base + arms, 0, 0]), (length, [arm_base - arms, 0, 0])])},
        "left_arm": {"rotation": kf([(0, [arms, 0, 0]), (h, [-arms, 0, 0]), (length, [arms, 0, 0])])},
    }


MERLIN = {
    # sitting on the chapel floor, back against the wall: dropped to the ground (hips at floor level), legs out in
    # front - the right knee drawn up a little - head bowed, left hand pressed to the wound in his side, the staff
    # resting across the right leg; slow, laboured breathing
    "injured_leaning": anim(4, "loop", {
        "root": {"position": kf([(0, [0, -10.5, 0])])},
        "body": {"rotation": kf([(0, [-12, 0, 2]), (2, [-9, 0, 2]), (4, [-12, 0, 2])])},
        "head": {"rotation": kf([(0, [24, 12, 4]), (2, [28, 12, 4]), (4, [24, 12, 4])])},
        "left_arm": {"rotation": kf([(0, [-40, 28, -20]), (2, [-44, 28, -20]), (4, [-40, 28, -20])])},
        "right_arm": {"rotation": kf([(0, [-28, -6, 14])])},
        "staff": {"rotation": kf([(0, [-62, 0, 18])])},
        "right_leg": {"rotation": kf([(0, [-62, 8, 0])])},
        "left_leg": {"rotation": kf([(0, [-86, -6, 0])])}}),
    "idle": anim(3, "loop", {
        "body": {"rotation": kf([(0, [0, 0, 0]), (1.5, [2, 0, 0]), (3, [0, 0, 0])])},
        "head": {"rotation": kf([(0, [0, 0, 0]), (1.5, [-4, 3, 0]), (3, [0, 0, 0])])},
        "right_arm": {"rotation": kf([(0, [-12, 0, 0]), (1.5, [-14, 0, 0]), (3, [-12, 0, 0])])}}),
    "walk": anim(1.2, "loop", walk(1.2, 20, 12, arm_base=-12)),
    # wounded on the chapel floor: on his back, staff fallen aside, breathing slowly
    "injured_lying": anim(4, "loop", {
        "root": {"rotation": kf([(0, [-90, 0, 0])]), "position": kf([(0, [0, 2, 0])])},
        "body": {"rotation": kf([(0, [0, 0, 0]), (2, [3, 0, 0]), (4, [0, 0, 0])])},
        "head": {"rotation": kf([(0, [10, 15, 0]), (2, [14, 15, 0]), (4, [10, 15, 0])])},
        "right_arm": {"rotation": kf([(0, [-20, 0, 40])])},
        "left_arm": {"rotation": kf([(0, [-60, 0, -20])])},
        "staff": {"rotation": kf([(0, [0, 0, 70])])}}),
    "heal_getup": anim(2.5, "once", {
        "root": {"rotation": kf([(0, [-90, 0, 0]), (1.0, [-50, 0, 0]), (2.0, [0, 0, 0])]),
                 "position": kf([(0, [0, 2, 0]), (2.0, [0, 0, 0])])},
        "right_arm": {"rotation": kf([(0, [-20, 0, 40]), (1.2, [-60, 0, 10]), (2.5, [-12, 0, 0])])},
        "head": {"rotation": kf([(0, [10, 15, 0]), (2.0, [-10, 0, 0]), (2.5, [0, 0, 0])])}}),
    "talk": anim(1.5, "once", {
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (0.4, [-45, 0, -12]), (1.0, [-35, 0, -8]), (1.5, [0, 0, 0])])},
        "head": {"rotation": kf([(0, [0, 0, 0]), (0.5, [5, 8, 0]), (1.0, [-3, -5, 0]), (1.5, [0, 0, 0])])}}),
    "cast": anim(2, "once", {
        "right_arm": {"rotation": kf([(0, [-12, 0, 0]), (0.6, [-150, 0, 0]), (1.4, [-150, 0, 0]), (2, [-12, 0, 0])])},
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (0.6, [-120, 0, -15]), (1.4, [-120, 0, -15]), (2, [0, 0, 0])])},
        "head": {"rotation": kf([(0, [0, 0, 0]), (0.6, [-20, 0, 0]), (1.4, [-20, 0, 0]), (2, [0, 0, 0])])}}),
    "point": anim(1.5, "once", {
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (0.4, [-90, 0, 0]), (1.2, [-90, 0, 0]), (1.5, [0, 0, 0])])},
        "head": {"rotation": kf([(0, [0, 0, 0]), (0.4, [0, -10, 0]), (1.5, [0, 0, 0])])}}),
    "vanish": anim(1, "hold", {
        "root": {"scale": kf([(0, [1, 1, 1]), (1, [0, 0, 0])])}}),
}

VORTIGERN = {
    "idle": anim(2.5, "loop", {
        "body": {"rotation": kf([(0, [0, 0, 0]), (1.25, [3, 0, 0]), (2.5, [0, 0, 0])])},
        "right_arm": {"rotation": kf([(0, [-15, 0, 5]), (1.25, [-18, 0, 5]), (2.5, [-15, 0, 5])])},
        "left_arm": {"rotation": kf([(0, [0, 0, -5]), (1.25, [-3, 0, -5]), (2.5, [0, 0, -5])])}}),
    "walk": anim(1.2, "loop", walk(1.2, 28, 18, arm_base=-15)),
    "run": anim(0.7, "loop", {**walk(0.7, 45, 35, arm_base=-25),
                              "body": {"rotation": kf([(0, [12, 0, 0])])}}),
    # rises out of the ground at the summit
    "intro": anim(3, "once", {
        "root": {"position": kf([(0, [0, -34, 0]), (1.6, [0, 0, 0])])},
        "right_arm": {"rotation": kf([(0, [-15, 0, 0]), (1.8, [-15, 0, 0]), (2.4, [-160, 0, 20]), (3, [-15, 0, 5])])},
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (1.8, [0, 0, 0]), (2.4, [-160, 0, -20]), (3, [0, 0, -5])])},
        "head": {"rotation": kf([(0, [30, 0, 0]), (1.8, [0, 0, 0]), (2.4, [-25, 0, 0]), (3, [0, 0, 0])])}}),
    # overhead chop: hits at 0.4 s (tick 8)
    "attack_1": anim(0.9, "once", {
        "right_arm": {"rotation": kf([(0, [-15, 0, 5]), (0.25, [-160, 0, 10]), (0.4, [20, 0, 0]), (0.9, [-15, 0, 5])])},
        "body": {"rotation": kf([(0, [0, 0, 0]), (0.25, [-10, -15, 0]), (0.4, [15, 10, 0]), (0.9, [0, 0, 0])])}}),
    # sideways sweep: hits at 0.5 s (tick 10)
    "attack_2": anim(1.0, "once", {
        "right_arm": {"rotation": kf([(0, [-15, 0, 5]), (0.3, [-80, -60, 60]), (0.5, [-80, 60, 30]), (1.0, [-15, 0, 5])])},
        "body": {"rotation": kf([(0, [0, 0, 0]), (0.3, [0, 35, 0]), (0.5, [0, -35, 0]), (1.0, [0, 0, 0])])}}),
    # both fists into the ground: lands at 0.8 s (tick 16)
    "slam": anim(1.5, "once", {
        "right_arm": {"rotation": kf([(0, [-15, 0, 5]), (0.6, [-175, 0, 10]), (0.8, [-20, 0, 0]), (1.5, [-15, 0, 5])])},
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (0.6, [-175, 0, -10]), (0.8, [-20, 0, 0]), (1.5, [0, 0, -5])])},
        "body": {"rotation": kf([(0, [0, 0, 0]), (0.6, [-15, 0, 0]), (0.8, [35, 0, 0]), (1.2, [35, 0, 0]), (1.5, [0, 0, 0])])},
        "root": {"position": kf([(0, [0, 0, 0]), (0.6, [0, 4, 0]), (0.8, [0, 0, 0])])}}),
    # dark fangs along the ground: cast at 0.6 s (tick 12)
    "spell": anim(1.25, "once", {
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (0.4, [-100, 0, -10]), (0.9, [-100, 0, -10]), (1.25, [0, 0, -5])])},
        "head": {"rotation": kf([(0, [0, 0, 0]), (0.4, [-10, 0, 0]), (1.25, [0, 0, 0])])}}),
    # calls his warriors: they appear at 1.0 s (tick 20)
    "summon": anim(1.5, "once", {
        "right_arm": {"rotation": kf([(0, [-15, 0, 5]), (0.7, [-165, 0, 35]), (1.2, [-165, 0, 35]), (1.5, [-15, 0, 5])])},
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (0.7, [-165, 0, -35]), (1.2, [-165, 0, -35]), (1.5, [0, 0, -5])])},
        "head": {"rotation": kf([(0, [0, 0, 0]), (0.7, [-30, 0, 0]), (1.2, [-30, 0, 0]), (1.5, [0, 0, 0])])}}),
    "stagger": anim(1.0, "once", {
        "body": {"rotation": kf([(0, [0, 0, 0]), (0.15, [-25, 0, 8]), (0.6, [-15, 0, 4]), (1.0, [0, 0, 0])])},
        "head": {"rotation": kf([(0, [0, 0, 0]), (0.15, [-25, 0, 0]), (1.0, [0, 0, 0])])},
        "root": {"position": kf([(0, [0, 0, 0]), (0.15, [0, 0, 3]), (1.0, [0, 0, 0])])}}),
    # at half health: a roar, arms flung wide (he can't be hurt meanwhile)
    "phase_change": anim(2.0, "once", {
        "right_arm": {"rotation": kf([(0, [-15, 0, 5]), (0.5, [-120, 0, 70]), (1.6, [-120, 0, 70]), (2.0, [-15, 0, 5])])},
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (0.5, [-120, 0, -70]), (1.6, [-120, 0, -70]), (2.0, [0, 0, -5])])},
        "head": {"rotation": kf([(0, [0, 0, 0]), (0.5, [-35, 0, 0]), (1.6, [-35, 0, 0]), (2.0, [0, 0, 0])])},
        "body": {"rotation": kf([(0, [0, 0, 0]), (0.5, [-15, 0, 0]), (1.6, [-15, 0, 0]), (2.0, [0, 0, 0])])}}),
    "death": anim(3.0, "hold", {
        "body": {"rotation": kf([(0, [0, 0, 0]), (0.6, [-20, 0, 0]), (1.2, [25, 0, 0])])},
        "root": {"rotation": kf([(0, [0, 0, 0]), (1.2, [0, 0, 0]), (2.2, [90, 0, 0])]),
                 "position": kf([(0, [0, 0, 0]), (1.2, [0, -4, 0]), (2.2, [0, 2, 0])])},
        "right_arm": {"rotation": kf([(0, [-15, 0, 5]), (1.2, [-60, 0, 30]), (2.2, [-10, 0, 40])])},
        "left_arm": {"rotation": kf([(0, [0, 0, 0]), (1.2, [-60, 0, -30]), (2.2, [-10, 0, -40])])}}),
}


def write(name, geo, anims, extra_dir=None):
    g = A / "geo/entity" / f"{name}.geo.json"; g.parent.mkdir(parents=True, exist_ok=True)
    g.write_text(json.dumps(geo, indent=1))
    a = A / "animations/entity" / f"{name}.animation.json"; a.parent.mkdir(parents=True, exist_ok=True)
    a.write_text(json.dumps({"format_version": "1.8.0", "animations": anims}, indent=1))
    if extra_dir:
        d = pathlib.Path(extra_dir) / name; d.mkdir(parents=True, exist_ok=True)
        shutil.copy(g, d / g.name); shutil.copy(a, d / a.name)


out = sys.argv[1] if len(sys.argv) > 1 else None
write("merlin", player_humanoid("merlin", MERLIN_BONES), {"injured_leaning": MERLIN["injured_leaning"]}, out)
print("merlin:", ", ".join(MERLIN))
