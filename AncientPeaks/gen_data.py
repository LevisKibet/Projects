"""Generates all data files for Ancient Peaks 0.2 (Ancient Highlands biome)."""
import json, pathlib, shutil

R = pathlib.Path(__file__).parent / "src/main/resources"
NS = "ancientpeaks"
OLD = pathlib.Path(__file__).parent / "vendor/legacy_ruins"
V = pathlib.Path(__file__).parent / "vendor"
BIOME_ID = f"{NS}:ancient_highlands"

def W(rel, obj):
    p = R / rel; p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(obj, indent=2) if not isinstance(obj, str) else obj)

hexc = lambda h: int(h.lstrip("#"), 16)
uniform_h = lambda lo, hi: {"type": "minecraft:uniform", "min_inclusive": {"absolute": lo}, "max_inclusive": {"absolute": hi}}
BIOME = {"type": "minecraft:biome"}; SQ = {"type": "minecraft:in_square"}
count = lambda n: {"type": "minecraft:count", "count": n}
def cf(n, o): W(f"data/{NS}/worldgen/configured_feature/{n}.json", o)
def pf(n, f, pl): W(f"data/{NS}/worldgen/placed_feature/{n}.json", {"feature": f, "placement": pl})

# ------------------------------------------------------------------ mod metadata
W("META-INF/mods.toml", '''modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description=\'\'\'${mod_description}\'\'\'

[[dependencies.${mod_id}]]
    modId="forge"
    mandatory=true
    versionRange="${forge_version_range}"
    ordering="NONE"
    side="BOTH"
[[dependencies.${mod_id}]]
    modId="minecraft"
    mandatory=true
    versionRange="${minecraft_version_range}"
    ordering="NONE"
    side="BOTH"
[[dependencies.${mod_id}]]
    modId="lithostitched"
    mandatory=true
    versionRange="[1.4,)"
    ordering="AFTER"
    side="BOTH"
[[dependencies.${mod_id}]]
    modId="immersive_gateways"
    mandatory=false
    versionRange="[0.0.7,)"
    ordering="AFTER"
    side="BOTH"
[[dependencies.${mod_id}]]
    modId="streamsreflowing"
    mandatory=false
    versionRange="[2.13,)"
    ordering="AFTER"
    side="BOTH"
[[dependencies.${mod_id}]]
    modId="tectonic"
    mandatory=true
    versionRange="[3.0,)"
    ordering="AFTER"
    side="BOTH"
[[dependencies.${mod_id}]]
    modId="dynamictrees"
    mandatory=false
    versionRange="*"
    ordering="NONE"
    side="BOTH"
''')
W("pack.mcmeta", {"pack": {"description": "Ancient Peaks data", "pack_format": 15}})

# ------------------------------------------------------------------ terrain: wrap Tectonic's router via Lithostitched
W(f"data/{NS}/worldgen/noise/seed.json", {"firstOctave": 0, "amplitudes": [1.0]})
W(f"data/{NS}/worldgen/density_function/wrapped.json", {"type": "lithostitched:wrapped_marker"})
W(f"data/{NS}/worldgen/density_function/highlands_terrain.json", {
    "type": f"{NS}:highlands", "input": f"{NS}:wrapped",
    "continents": "minecraft:overworld/noise_router/continents", "seed_noise": f"{NS}:seed"})
for tgt in ("final_density", "initial_density_without_jaggedness"):
    W(f"data/{NS}/lithostitched/worldgen_modifier/wrap_{tgt}.json", {
        "type": "lithostitched:wrap_noise_router", "dimension": "minecraft:overworld",
        "target": tgt, "wrapper_function": f"{NS}:highlands_terrain"})
# flood everything below sea level across each island's footprint so the ocean always meets the island
W(f"data/{NS}/worldgen/density_function/island_flood.json", {"type": f"{NS}:island_flood", "input": f"{NS}:wrapped", "seed_noise": f"{NS}:seed"})
W(f"data/{NS}/lithostitched/worldgen_modifier/wrap_fluid_level_floodedness.json", {
    "type": "lithostitched:wrap_noise_router", "dimension": "minecraft:overworld",
    "target": "fluid_level_floodedness", "wrapper_function": f"{NS}:island_flood"})

# ------------------------------------------------------------------ surface rules and cliff palettes for the island biomes
def block(name, **p):
    b = {"Name": f"minecraft:{name}"}
    if p: b["Properties"] = p
    return {"type": "minecraft:block", "result_state": b}
def B(name):
    return block(name, axis="y") if name == "deepslate" else block(name)
cond = lambda c, r: {"type": "minecraft:condition", "if_true": c, "then_run": r}
seq = lambda *r: {"type": "minecraft:sequence", "sequence": list(r)}
NOT = lambda c: {"type": "minecraft:not", "invert": c}
floor = {"type": "minecraft:stone_depth", "offset": 0, "surface_type": "floor", "add_surface_depth": False, "secondary_depth_range": 0}
under_floor = {"type": "minecraft:stone_depth", "offset": 2, "surface_type": "floor", "add_surface_depth": False, "secondary_depth_range": 0}
above_water = {"type": "minecraft:water", "offset": -1, "surface_depth_multiplier": 0, "add_stone_depth": False}
steep = {"type": f"{NS}:steep_any", "threshold": 3}
noise = lambda nz, lo, hi=9.0: {"type": "minecraft:noise_threshold", "noise": f"minecraft:{nz}", "min_threshold": lo, "max_threshold": hi}
patchy = lambda lo, hi=9.0: noise("surface", lo, hi)
y_above = lambda y, m=2: {"type": "minecraft:y_above", "anchor": {"absolute": y}, "surface_depth_multiplier": m, "add_stone_depth": False}
biome_is = lambda *b: {"type": "minecraft:biome", "biome_is": [f"{NS}:{x}" for x in b]}

_sp = [0]
def _grad(p):
    _sp[0] += 1
    if p >= 0.46: F = 2000; T = int(F - (F - 150) / p)
    else: T = -2000; F = int((150 + 2000 * p) / (1 - p))
    return {"type": "minecraft:vertical_gradient", "random_name": f"{NS}:speckle_{_sp[0]}",
            "true_at_and_below": {"absolute": T}, "false_at_and_above": {"absolute": F}}

def speckle_rule(p, then):
    """Per-block random mask (vertical_gradient dithering) - rugged, never forms visible layers."""
    if p >= 0.15: return cond(_grad(p), then)
    q = max(p ** 0.5, 0.15)
    return cond(_grad(q), cond(_grad(q), then))

def rugged(base_noise, bases, specks):
    """No horizontal bands at all: large 2D patches choose the base rock, per-block speckles break it up."""
    out = [speckle_rule(p, B(b)) for b, p in specks]
    for (lo, hi), b in zip(base_noise, bases[:-1]):
        out.append(cond(noise("calcite", lo, hi), B(b)))
    out.append(B(bases[-1]))
    return seq(*out)

def strata(bands, streaks, bottom=40, specks=()):
    """Horizontal bands by height, cut by vertical streaks (2D noise) so cliff faces read like weathered rock."""
    widths = [9, 13, 7, 11, 15, 8, 12, 10, 6, 14]
    out, y, i = [], 320, 0
    while y > bottom:
        y -= widths[i % len(widths)]
        out.append(cond(y_above(y), B(bands[i % len(bands)]))); i += 1
    return seq(*[speckle_rule(p, B(b)) for b, p in specks], *[cond(noise(nz, lo, hi), B(b)) for nz, lo, hi, b in streaks], *out)

LIME = strata(["stone", "andesite", "tuff", "stone", "deepslate", "andesite", "stone", "tuff"],
              [("calcite", -0.018, 0.018, "calcite"), ("gravel", -0.03, 0.03, "deepslate"), ("packed_ice", -0.04, 0.04, "tuff")],
              specks=[("cobblestone", 0.10), ("andesite", 0.08), ("tuff", 0.06)])
WET = strata(["deepslate", "tuff", "cobbled_deepslate", "deepslate", "stone", "tuff"],
             [("calcite", -0.05, 0.05, "mossy_cobblestone"), ("gravel", -0.04, 0.04, "smooth_basalt"), ("surface", 1.05, 9, "moss_block"),
              ("packed_ice", -0.03, 0.03, "cobbled_deepslate")], specks=[("mossy_cobblestone", 0.08), ("cobbled_deepslate", 0.10), ("tuff", 0.06)])
PEAK = rugged([(-9, -0.35), (0.3, 9)], ["andesite", "tuff", "stone"],
             [("cobblestone", 0.22), ("andesite", 0.16), ("gravel", 0.035), ("mossy_cobblestone", 0.03), ("cobblestone", 0.12)])
MOSSY = strata(["stone", "tuff", "andesite", "deepslate", "stone"],
               [("calcite", -0.04, 0.04, "mossy_cobblestone"), ("surface", 0.9, 9, "moss_block"), ("gravel", -0.03, 0.03, "deepslate")],
               specks=[("mossy_cobblestone", 0.08), ("cobblestone", 0.08), ("andesite", 0.06)])

grass = block("grass_block", snowy="false")
spec = {  # biome: (top above water, top under water, subsoil, cliff palette)
    "ancient_highlands": (seq(cond(patchy(1.2), block("moss_block")), cond(patchy(0.9), block("coarse_dirt")), grass), seq(cond(patchy(0.0), block("gravel")), block("moss_block")), block("dirt"), LIME),
    "highland_meadow": (grass, block("clay"), block("dirt"), LIME),
    "highland_forest": (seq(cond(patchy(1.0), block("podzol", snowy="false")), cond(patchy(0.75, 0.95), block("coarse_dirt")), grass), block("gravel"), block("dirt"), MOSSY),
    "highland_plains": (seq(cond(patchy(1.8), block("coarse_dirt")), grass), seq(cond(patchy(0.0), block("gravel")), block("sand")), block("dirt"), LIME),
    "highland_gorge": (seq(cond(patchy(0.2), block("moss_block")), grass), seq(cond(patchy(0.3), block("clay")), cond(patchy(-0.5), block("gravel")), block("sand")), block("dirt"), WET),
    "highland_shore": (seq(cond(NOT(y_above(68, 0)), seq(cond(patchy(0.4), block("gravel")), block("sand"))), grass), block("sand"), block("sand"), LIME),
    "highland_peaks": (seq(cond(noise("packed_ice", -0.18, 0.18), block("packed_ice")), speckle_rule(0.12, block("gravel")), block("snow_block")), block("stone"), PEAK, PEAK),
    "highland_tarn": (seq(cond(patchy(-0.3), block("moss_block")), grass), seq(cond(patchy(0.0), block("clay")), block("moss_block")), block("dirt"), MOSSY),
}
parts = []
for b, (top, wet, sub, cliff) in spec.items():
    parts.append(cond(biome_is(b), seq(
        cond(floor, cond(NOT(steep), seq(cond(above_water, top), wet))),
        cond(under_floor, cond(NOT(steep), sub)),
        cliff)))
W(f"data/{NS}/lithostitched/worldgen_modifier/highlands_surface.json", {
    "type": "lithostitched:add_surface_rule", "levels": ["minecraft:overworld"], "surface_rule": seq(*parts)})

# ------------------------------------------------------------------ our own decorations
for name in ("gorge_river", "gorge_decor"):
    W(f"data/{NS}/worldgen/configured_feature/{name}.json", {"type": f"{NS}:{name}", "config": {}})
    pf(name, f"{NS}:{name}", [])
cf("ledge_plants", {"type": "minecraft:random_patch", "config": {"tries": 48, "xz_spread": 7, "y_spread": 3, "feature": {
    "feature": {"type": "minecraft:simple_block", "config": {"to_place": {"type": "minecraft:weighted_state_provider", "entries": [
        {"data": {"Name": "minecraft:grass"}, "weight": 6}, {"data": {"Name": "minecraft:fern"}, "weight": 4},
        {"data": {"Name": "minecraft:large_fern", "Properties": {"half": "lower"}}, "weight": 1}]}}},
    "placement": [{"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}}]}}})
pf("ledge_plants", f"{NS}:ledge_plants", [count(5), SQ, {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"}, BIOME])
cf("highland_trees", {"type": "minecraft:random_selector", "config": {
    "features": [{"chance": 0.3, "feature": "minecraft:pine_checked"}, {"chance": 0.1, "feature": "minecraft:mega_spruce_checked"}],
    "default": "minecraft:spruce_checked"}})
surface_tree = lambda n: [count(n) if isinstance(n, int) else n, SQ, {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 0},
                          {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}, BIOME]
pf("highland_trees", f"{NS}:highland_trees", surface_tree({"type": "minecraft:count", "count": {"type": "minecraft:weighted_list", "distribution": [{"data": 0, "weight": 6}, {"data": 2, "weight": 3}, {"data": 5, "weight": 1}]}}))
pf("forest_spruce", f"{NS}:highland_trees", surface_tree(4))
pf("grove_mangroves", "minecraft:mangrove", [{"type": "minecraft:count", "count": {"type": "minecraft:weighted_list", "distribution": [{"data": 0, "weight": 5}, {"data": 1, "weight": 4}, {"data": 3, "weight": 2}]}},
    SQ, {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 4}, {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"},
    {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:would_survive", "state": {"Name": "minecraft:mangrove_propagule", "Properties": {"age": "0", "hanging": "false", "stage": "0", "waterlogged": "false"}}}}, BIOME])
pf("grove_giants", "minecraft:dark_oak", surface_tree(1)[:-1] + [{"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:would_survive", "state": {"Name": "minecraft:dark_oak_sapling", "Properties": {"stage": "0"}}}}, BIOME])
pf("azalea_trees", "minecraft:azalea_tree", [{"type": "minecraft:rarity_filter", "chance": 3}, SQ, {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 0},
    {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"},
    {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:would_survive", "state": {"Name": "minecraft:azalea"}}}, BIOME])
pf("dripleaf_pools", "minecraft:clay_pool_with_dripleaves", [count(4), SQ, {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}, BIOME])
cf("moss_ground_plants", {"type": "minecraft:simple_block", "config": {"to_place": {"type": "minecraft:weighted_state_provider", "entries": [
    {"data": {"Name": "minecraft:moss_carpet"}, "weight": 25}, {"data": {"Name": "minecraft:grass"}, "weight": 40},
    {"data": {"Name": "minecraft:fern"}, "weight": 12}, {"data": {"Name": "minecraft:azalea"}, "weight": 1}]}}})
cf("moss_ground", {"type": "minecraft:vegetation_patch", "config": {
    "depth": 1, "extra_bottom_block_chance": 0.0, "extra_edge_column_chance": 0.3,
    "ground_state": {"type": "minecraft:simple_state_provider", "state": {"Name": "minecraft:moss_block"}},
    "replaceable": "#minecraft:moss_replaceable", "surface": "floor", "vegetation_chance": 0.45,
    "vegetation_feature": {"feature": f"{NS}:moss_ground_plants", "placement": []}, "vertical_range": 5,
    "xz_radius": {"type": "minecraft:uniform", "value": {"min_inclusive": 3, "max_inclusive": 6}}}})
pf("moss_carpets", f"{NS}:moss_ground", [count(2), SQ, {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}, BIOME])
pf("grove_vines", "minecraft:vines", [count(90), SQ, {"type": "minecraft:height_range", "height": uniform_h(60, 110)}, BIOME])
cf("mossy_boulder", {"type": "minecraft:forest_rock", "config": {"state": {"Name": "minecraft:mossy_cobblestone"}}})
pf("mossy_boulder", f"{NS}:mossy_boulder", [{"type": "minecraft:rarity_filter", "chance": 3}, SQ, {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"}, BIOME])
pf("cliff_vines", "minecraft:vines", [count(160), SQ, {"type": "minecraft:height_range", "height": uniform_h(70, 320)}, BIOME])
cf("cliff_spring", {"type": "minecraft:spring_feature", "config": {
    "hole_count": 1, "requires_block_below": True, "rock_count": 4,
    "state": {"Name": "minecraft:water", "Properties": {"falling": "true"}},
    "valid_blocks": ["minecraft:stone", "minecraft:andesite", "minecraft:deepslate", "minecraft:tuff", "minecraft:calcite", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:grass_block"]}})
def sapling_ok(s): return {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:would_survive", "state": {"Name": f"minecraft:{s}", "Properties": {"stage": "0"}}}}
GROUND = [SQ, {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 0}, {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}]
pf("plains_spruces", "minecraft:trees_taiga", [{"type": "minecraft:rarity_filter", "chance": 3}] + GROUND + [sapling_ok("spruce_sapling"), BIOME])
pf("plains_oaks", "minecraft:oak_bees_005", [{"type": "minecraft:rarity_filter", "chance": 6}] + GROUND + [sapling_ok("oak_sapling"), BIOME])
pf("plains_bee_oaks", "minecraft:fancy_oak_bees_005", [{"type": "minecraft:rarity_filter", "chance": 9}] + GROUND + [sapling_ok("oak_sapling"), BIOME])
pf("cliff_waterfall", f"{NS}:cliff_spring", [count(12), SQ, {"type": "minecraft:height_range", "height": uniform_h(90, 300)}, BIOME])

# ------------------------------------------------------------------ biome feature lists, ordered consistently with vanilla
import glob, collections, heapq
edges = collections.defaultdict(set); nodes = set()
for f in glob.glob(str(V / "vanilla_biomes/*.json")):
    L = json.load(open(f))["features"]
    if len(L) > 9:
        for a_, b_ in zip(L[9], L[9][1:]): edges[a_].add(b_)
        nodes.update(L[9])
MINE = ["minecraft:glow_lichen", f"{NS}:mossy_boulder", f"{NS}:grove_mangroves", f"{NS}:grove_giants", "minecraft:trees_swamp",
        "minecraft:trees_birch_and_oak", f"{NS}:forest_spruce", f"{NS}:highland_trees", f"{NS}:azalea_trees", f"{NS}:plains_spruces", f"{NS}:plains_oaks", f"{NS}:plains_bee_oaks", "minecraft:trees_plains",
        "minecraft:flower_plains", "minecraft:patch_sunflower", "minecraft:flower_default", "minecraft:flower_swamp",
        "minecraft:patch_grass_plain", "minecraft:patch_grass_forest", "minecraft:patch_grass_normal", "minecraft:patch_tall_grass_2",
        "minecraft:patch_large_fern", f"{NS}:ledge_plants", "minecraft:patch_berry_common", f"{NS}:dripleaf_pools", f"{NS}:moss_carpets",
        "minecraft:patch_waterlily", "minecraft:brown_mushroom_swamp", "minecraft:red_mushroom_swamp", "minecraft:brown_mushroom_normal",
        "minecraft:red_mushroom_normal", "minecraft:patch_sugar_cane_swamp", "minecraft:seagrass_swamp", f"{NS}:grove_vines",
        f"{NS}:cliff_vines", f"{NS}:gorge_decor"]
nodes.update(MINE)
pref = {k: i for i, k in enumerate(MINE)}
indeg = collections.Counter()
for a_ in list(edges):
    for b_ in edges[a_]: indeg[b_] += 1
heap = [(pref.get(k, 999), k) for k in nodes if indeg[k] == 0]; heapq.heapify(heap); order = []
while heap:
    _, k = heapq.heappop(heap); order.append(k)
    for b_ in edges.get(k, ()):
        indeg[b_] -= 1
        if indeg[b_] == 0: heapq.heappush(heap, (pref.get(b_, 999), b_))
assert len(order) == len(nodes), "cycle in vanilla feature order?"
rank = {k: i for i, k in enumerate(order)}
def veg(*names): return sorted(names, key=lambda k: rank[k])

stony = json.loads((V / "vanilla_biomes/stony_peaks.json").read_text())
def feature_list(vegetal, waterfalls):
    f = [list(x) for x in stony["features"]]
    f[1] = []                                                       # no lava lakes or lavafalls on the island
    # island water and gorge decorations run from ChunkGeneratorMixin after all features
    f[8] = [x for x in f[8] if x not in ("minecraft:spring_lava", "minecraft:spring_water")]   # no springs or waterfalls: Streams Reflowing brings the running water
    f[9] = veg(*vegetal)
    return f
monsters = stony["spawners"]["monster"]
def spawners(creatures, extra_monsters=()):
    sp = {k: v for k, v in stony["spawners"].items()}
    sp["creature"] = [{"type": f"minecraft:{t}", "weight": w, "minCount": lo, "maxCount": hi} for t, w, lo, hi in creatures]
    sp["monster"] = monsters + [{"type": f"minecraft:{t}", "weight": w, "minCount": lo, "maxCount": hi} for t, w, lo, hi in extra_monsters]
    return sp
TAIGA_GRASS, TAIGA_FOLIAGE = "#86B783", "#68A464"
def effects(grass_c, foliage_c, water="#3B8ED0", water_fog="#0B3550", music="minecraft:music.overworld.stony_peaks", particle=0.003):
    e = {"sky_color": hexc("#7C8A9E"), "fog_color": hexc("#A4ADB8"), "water_color": hexc(water), "water_fog_color": hexc(water_fog),
         "grass_color": hexc(TAIGA_GRASS), "foliage_color": hexc(TAIGA_FOLIAGE),     # one taiga palette for the whole island
         "mood_sound": {"block_search_extent": 8, "offset": 2.0, "sound": "minecraft:ambient.cave", "tick_delay": 6000},
         "music": {"max_delay": 24000, "min_delay": 12000, "replace_current_music": False, "sound": music}}
    if particle: e["particle"] = {"options": {"type": "minecraft:white_ash"}, "probability": particle}
    return e
# Every vanilla feature an island biome uses is placed through our own copy of it (ancientpeaks:v/<name>): the same
# feature and placement, but a feature of our own, so the order of our lists can never clash with another mod's
# biomes (a "feature order cycle" otherwise stops the world from generating). The biome filter in each copy still
# only lets it place in the biomes that list it.
_VP = V / "vanilla_placed"
_wrapped = set()
def own(name):
    if not name.startswith("minecraft:"): return name
    short = name.split(":", 1)[1]
    if short not in _wrapped:
        W(f"data/{NS}/worldgen/placed_feature/v/{short}.json", json.loads((_VP / f"{short}.json").read_text()))
        _wrapped.add(short)
    return f"{NS}:v/{short}"
def biome(name, temp, down, eff, feats, spawn):
    feats = [[own(x) for x in step] for step in feats]
    W(f"data/{NS}/worldgen/biome/{name}.json", {"has_precipitation": True, "temperature": temp, "downfall": down, "effects": eff,
        "spawners": spawn, "spawn_costs": {}, "carvers": {"air": ["minecraft:cave", "minecraft:cave_extra_underground"]}, "features": feats})
G = f"{NS}:gorge_decor"
biome("ancient_highlands", 0.7, 0.85, effects("#A8983A", "#5C6A2C"),
      feature_list(["minecraft:glow_lichen", f"{NS}:mossy_boulder", f"{NS}:highland_trees", f"{NS}:ledge_plants", f"{NS}:cliff_vines"], True),
      spawners([("goat", 5, 1, 3), ("sheep", 6, 2, 4)]))
biome("highland_meadow", 0.8, 0.6, effects("#9AAE4A", "#6A8A34", particle=0.0015),
      feature_list(["minecraft:glow_lichen", "minecraft:trees_plains", "minecraft:flower_plains", "minecraft:patch_sunflower",
                    "minecraft:patch_grass_plain", "minecraft:patch_tall_grass_2"], False),
      spawners([("horse", 5, 2, 6), ("sheep", 12, 2, 4), ("rabbit", 4, 2, 3), ("donkey", 1, 1, 2)]))
biome("highland_forest", 0.7, 0.8, effects("#6F8F3A", "#4F7A2C", music="minecraft:music.overworld.forest"),
      feature_list(["minecraft:glow_lichen", f"{NS}:mossy_boulder", "minecraft:trees_birch_and_oak", f"{NS}:forest_spruce", "minecraft:flower_default",
                    "minecraft:patch_grass_forest", "minecraft:patch_large_fern", "minecraft:patch_berry_common",
                    "minecraft:brown_mushroom_normal", "minecraft:red_mushroom_normal"], False),
      spawners([("wolf", 5, 4, 4), ("rabbit", 4, 2, 3), ("pig", 8, 2, 4), ("chicken", 6, 2, 4), ("fox", 4, 2, 3)]))
biome("highland_plains", 0.8, 0.5, effects("#8FAE48", "#6A9A34", particle=0.0, music="minecraft:music.overworld.meadow"),
      feature_list(["minecraft:glow_lichen", f"{NS}:plains_spruces", f"{NS}:plains_oaks", f"{NS}:plains_bee_oaks", "minecraft:flower_plains", "minecraft:flower_default",
                    "minecraft:flower_flower_forest",
                    "minecraft:patch_sunflower", "minecraft:patch_grass_plain", "minecraft:patch_grass_taiga_2", "minecraft:patch_tall_grass_2",
                    "minecraft:patch_large_fern", "minecraft:patch_grass_savanna", "minecraft:patch_tall_grass", "minecraft:flower_meadow",
                    "minecraft:patch_grass_forest", "minecraft:patch_berry_common"], False),
      spawners([("horse", 5, 2, 6), ("sheep", 12, 2, 4), ("cow", 8, 2, 4), ("pig", 8, 2, 4), ("chicken", 8, 2, 4), ("donkey", 1, 1, 2)]))
biome("highland_gorge", 0.7, 0.9, effects("#7E8C3A", "#5C6A2C", particle=0.0),
      feature_list(["minecraft:glow_lichen", f"{NS}:ledge_plants", f"{NS}:moss_carpets", f"{NS}:cliff_vines"], True), spawners([]))
biome("highland_shore", 0.7, 0.8, effects("#A8983A", "#5C6A2C"),
      feature_list(["minecraft:glow_lichen", "minecraft:patch_grass_normal"], False), spawners([]))
biome("highland_peaks", -0.4, 0.9, effects("#A8983A", "#5C6A2C", music="minecraft:music.overworld.frozen_peaks", particle=0.0),
      feature_list(["minecraft:glow_lichen"], False), spawners([("goat", 5, 1, 3), ("rabbit", 3, 2, 3)]))
biome("highland_tarn", 0.8, 0.9, effects("#7FA83E", "#5E9A34", water="#3AA6C4", water_fog="#0C3A48", music="minecraft:music.overworld.lush_caves", particle=0.0),
      feature_list(["minecraft:glow_lichen", f"{NS}:azalea_trees", "minecraft:patch_grass_normal", f"{NS}:moss_carpets",
                    "minecraft:patch_waterlily"], False),
      spawners([("sheep", 5, 2, 3), ("frog", 4, 2, 3)]))
W("data/streamsreflowing/tags/worldgen/biome/no_streams.json", {"replace": False, "values": [f"{NS}:highland_tarn", f"{NS}:highland_plains"]})  # streams end at the plains edge in swallow holes (StreamEnds)
W("assets/ancientpeaks/lang/en_us.json", {
    "biome.ancientpeaks.ancient_highlands": "Ancient Highlands", "biome.ancientpeaks.highland_meadow": "Highland Meadow",
    "biome.ancientpeaks.highland_forest": "Highland Forest", "biome.ancientpeaks.highland_plains": "Highland Plains",
    "biome.ancientpeaks.highland_gorge": "Highland River", "biome.ancientpeaks.highland_shore": "Highland Shore",
    "biome.ancientpeaks.highland_peaks": "Glacier Peaks", "biome.ancientpeaks.highland_tarn": "Mountain Tarn"})

# ------------------------------------------------------------------ ruins carried over from 0.1, retargeted
for sub in ["structures", "loot_tables", "worldgen/processor_list", "worldgen/template_pool", "worldgen/structure", "worldgen/structure_set"]:
    src = OLD / sub
    if src.exists(): shutil.copytree(src, R / f"data/{NS}/{sub}", dirs_exist_ok=True)
W(f"data/{NS}/tags/worldgen/biome/has_structure/castle_ruin.json", {"replace": False, "values": [BIOME_ID, f"{NS}:highland_meadow"]})
ss = json.loads((R / f"data/{NS}/worldgen/structure_set/castle_ruins.json").read_text())
ss["placement"].update({"spacing": 12, "separation": 4}); W(f"data/{NS}/worldgen/structure_set/castle_ruins.json", ss)
# ------------------------------------------------------------------ Dynamic Trees compatibility (optional)
# Dynamic Trees reads a "trees/" pack from every mod jar; without Dynamic Trees these files are simply ignored.
def DT(rel, obj):
    p = R / f"trees/{NS}/{rel}"; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(json.dumps(obj, indent=2))
def dt_biome(name, species, density, chance, **extra):
    a = {"species": species, "density": density, "chance": chance}; a.update(extra)
    return {"select": {"name": f"{NS}:{name}"}, "apply": a}
DT("world_gen/default.json", [
    dt_biome("highland_forest", {"random": {"spruce": 3, "oak": 2, "birch": 1}}, [0.9], 0.9, forestness=0.8),
    dt_biome("ancient_highlands", {"random": {"spruce": 4, "mega_spruce": 1}}, [0.4], 0.5),
    dt_biome("highland_plains", {"random": {"spruce": 3, "oak": 2, "birch": 1}}, [0.35], 0.4),
    dt_biome("highland_meadow", {"random": {"oak": 3, "birch": 1}}, [0.1], 0.1),
    dt_biome("highland_tarn", "azalea", [0.3], 0.3),
    {"select": {"name": f"{NS}:highland_(gorge|shore|peaks)"}, "apply": {"species": "null", "density": [1.0], "chance": 1.0}},
])
DT("world_gen/feature_cancellers.json", [
    {"select": {"name": f"{NS}:.*"}, "cancellers": {"type": "tree", "namespace": ns}} for ns in ("minecraft", NS)
] + [{"select": {"name": f"{NS}:.*"}, "cancellers": {"type": "rooted_tree", "namespace": "minecraft"}}])
print("data written")
