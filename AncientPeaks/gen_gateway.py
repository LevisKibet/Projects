"""Highland Gateways in the overworld: Immersive Gateways' own ancient portals, placed by our structure set,
which Ancient Peaks links to the islands."""
import json, pathlib
R = pathlib.Path(__file__).parent / "src/main/resources"
NS = "ancientpeaks"
def W(rel, obj):
    p = R / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(json.dumps(obj, indent=2))
# plains/0 is the portal with an outpost-like hut beside it; our own companion structure stands there instead
elems = [{"weight": 1, "element": {"element_type": "minecraft:legacy_single_pool_element", "location": f"immersive_gateways:{k}",
          "processors": f"{NS}:gateway_ground", "projection": "rigid"}} for k in ("plains/1", "plains/2", "mountain/0", "mountain/1")]
# Immersive Gateways' templates are boxes whose corners are plain air: placed as-is they cut a square pit into the
# ground. Ignoring that air keeps the terrain (the cave_air inside the ruin still clears the portal itself).
GROUND = [{"processor_type": "minecraft:rule", "rules": [{"location_predicate": {"predicate_type": "always_true"},
           "input_predicate": {"predicate_type": "block_match", "block": "minecraft:structure_block"}, "output_state": {"Name": "minecraft:cave_air"}}]},
          {"processor_type": "minecraft:block_ignore", "blocks": [{"Name": "minecraft:air"}]}]
W(f"data/{NS}/worldgen/processor_list/gateway_ground.json", GROUND)
# the same fix for Immersive Gateways' own portals, and blend them into the slope
W("data/immersive_gateways/worldgen/processor_list/default.json", GROUND)
import zipfile
with zipfile.ZipFile(pathlib.Path(__file__).parent / "libs/immersive_gateways-forge-0.0.7.jar") as z:
    for n in z.namelist():
        if n.startswith("data/immersive_gateways/worldgen/structure/") and n.endswith(".json"):
            s = json.loads(z.read(n)); s["terrain_adaptation"] = "beard_thin"
            W(n, s)
# our own structure type, so it can stand down when the lore mod (which brings its own gateways) is installed
W(f"data/{NS}/worldgen/structure/highland_gateway.json", {
    "type": f"{NS}:anchored", "anchor": "chunk", "offset": [0, -9, 0], "yield_to_lore": False,
    "biomes": f"#{NS}:has_structure/highland_gateway", "step": "surface_structures", "terrain_adaptation": "beard_thin",
    "spawn_overrides": {}, "size": 1, "max_distance_from_center": 80,
    "start_pool": {"elements": elems, "fallback": "minecraft:empty"}})
W(f"data/{NS}/worldgen/structure_set/highland_gateways.json", {"structures": [{"structure": f"{NS}:highland_gateway", "weight": 1}],
    "placement": {"type": "minecraft:random_spread", "salt": 734615021, "spacing": 44, "separation": 14}})
W(f"data/{NS}/tags/worldgen/biome/has_structure/highland_gateway.json", {"replace": False, "values": [
    "minecraft:plains", "minecraft:sunflower_plains", "minecraft:meadow", "minecraft:forest", "minecraft:flower_forest", "minecraft:birch_forest",
    "minecraft:old_growth_birch_forest", "minecraft:dark_forest", "minecraft:taiga", "minecraft:old_growth_pine_taiga",
    "minecraft:old_growth_spruce_taiga", "minecraft:savanna", "minecraft:windswept_hills", "minecraft:windswept_forest",
    "minecraft:snowy_plains", "minecraft:cherry_grove", "minecraft:grove"]})
print("gateway structure ok")
