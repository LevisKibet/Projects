"""Loot tables for the chests and barrels in Logres' buildings (see LootProcessor).

    python3 tools/gen_loot.py
"""
import json, pathlib
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/data/ancientpeaks_lore/loot_tables/chests"

def item(name, w, lo=1, hi=1, ench=None, treasure=False, dmg=None):
    e = {"type": "minecraft:item", "name": name, "weight": w, "functions": []}
    if hi > 1 or lo > 1: e["functions"].append({"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}})
    if ench: e["functions"].append({"function": "minecraft:enchant_with_levels", "levels": {"type": "minecraft:uniform", "min": ench[0], "max": ench[1]}, "treasure": treasure})
    if dmg: e["functions"].append({"function": "minecraft:set_damage", "damage": {"type": "minecraft:uniform", "min": dmg[0], "max": dmg[1]}})
    if not e["functions"]: del e["functions"]
    return e

def tag(t, w, lo=1, hi=1):
    e = {"type": "minecraft:tag", "name": t, "expand": True, "weight": w}
    if hi > 1 or lo > 1: e["functions"] = [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}]
    return e

def table(name, w): return {"type": "minecraft:loot_table", "name": name, "weight": w}
def empty(w): return {"type": "minecraft:empty", "weight": w}
def pool(rolls, entries, bonus=0):
    p = {"rolls": rolls if isinstance(rolls, (int, float)) else {"type": "minecraft:uniform", "min": rolls[0], "max": rolls[1]}, "entries": entries}
    if bonus: p["bonus_rolls"] = bonus
    return p

IRON = [("minecraft:iron_sword", 10), ("minecraft:iron_axe", 7), ("minecraft:iron_pickaxe", 7), ("minecraft:iron_shovel", 4),
        ("minecraft:iron_helmet", 6), ("minecraft:iron_chestplate", 4), ("minecraft:iron_leggings", 4), ("minecraft:iron_boots", 6),
        ("minecraft:chainmail_chestplate", 4), ("minecraft:chainmail_helmet", 4), ("minecraft:chainmail_boots", 3), ("minecraft:bow", 6),
        ("minecraft:crossbow", 4), ("minecraft:shield", 4), ("minecraft:stone_sword", 5), ("minecraft:stone_axe", 4)]
DIAMOND = [("minecraft:diamond_sword", 1), ("minecraft:diamond_pickaxe", 1), ("minecraft:diamond_helmet", 1)]
# enchanting: enchant_with_levels on a plain book turns it into a real enchanted book (on enchanted_book it does nothing)
def ebook(w, lo, hi): return item("minecraft:book", w, ench=(lo, hi))

# CHESTS hold the metal: ingots, tools, weapons and armour - worn, lightly enchanted, rarely more than one piece
gear = pool(1, [item(n, w, ench=(4, 14), dmg=(0.35, 0.85)) for n, w in IRON] +
               [item(n, w, ench=(10, 20), dmg=(0.3, 0.7)) for n, w in DIAMOND] +
               [ebook(4, 8, 20), empty(45)])
mats = pool([1, 2], [item("minecraft:iron_ingot", 14, 1, 4), item("minecraft:gold_ingot", 7, 1, 3), item("minecraft:copper_ingot", 8, 2, 5),
                     item("minecraft:iron_nugget", 10, 3, 9), item("minecraft:gold_nugget", 7, 2, 7), item("minecraft:raw_iron", 6, 1, 3),
                     item("minecraft:raw_copper", 5, 2, 5), item("minecraft:coal", 10, 2, 6), item("minecraft:lapis_lazuli", 4, 2, 5),
                     item("minecraft:redstone", 4, 2, 5), item("minecraft:emerald", 3, 1, 2), item("minecraft:diamond", 1),
                     item("minecraft:amethyst_shard", 3, 1, 3), item("minecraft:flint", 5, 1, 4)])
misc = pool([0, 1], [item("minecraft:arrow", 8, 3, 10), item("minecraft:leather", 6, 1, 4), item("minecraft:string", 5, 1, 4),
                     item("minecraft:book", 3, 1, 2), item("minecraft:saddle", 1), item("minecraft:name_tag", 1),
                     item("minecraft:torch", 6, 2, 8), item("minecraft:lead", 2), empty(20)])
# now and then a share of a vanilla table other mods add their loot to
compat = pool(1, [empty(85), table("minecraft:chests/simple_dungeon", 4), table("minecraft:chests/village/village_weaponsmith", 5),
                  table("minecraft:chests/village/village_toolsmith", 4), table("minecraft:chests/abandoned_mineshaft", 2)])
# BARRELS hold the stores: food and drink, potions
def potion(p, w): return {"type": "minecraft:item", "name": "minecraft:potion", "weight": w, "functions": [{"function": "minecraft:set_potion", "id": p}]}
food = pool([1, 3], [item("minecraft:bread", 14, 1, 4), item("minecraft:baked_potato", 12, 1, 4), item("minecraft:potato", 8, 2, 5),
                     item("minecraft:carrot", 8, 2, 5), item("minecraft:beetroot", 6, 2, 5), item("minecraft:wheat", 8, 2, 6),
                     item("minecraft:apple", 8, 1, 3), item("minecraft:cooked_beef", 6, 1, 3), item("minecraft:cooked_mutton", 5, 1, 3),
                     item("minecraft:cooked_cod", 5, 1, 3), item("minecraft:dried_kelp", 5, 2, 6), item("minecraft:pumpkin_pie", 3, 1, 2),
                     item("minecraft:honey_bottle", 3, 1, 2), item("minecraft:milk_bucket", 2), item("minecraft:sugar", 4, 1, 4),
                     item("minecraft:golden_carrot", 2, 1, 2)])
potions = pool(1, [potion("minecraft:healing", 4), potion("minecraft:regeneration", 2), potion("minecraft:swiftness", 2),
                   potion("minecraft:night_vision", 2), potion("minecraft:fire_resistance", 2), potion("minecraft:water_breathing", 1),
                   item("minecraft:glass_bottle", 4, 1, 3), empty(55)])

def W(name, pools):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.json").write_text(json.dumps({"type": "minecraft:chest", "pools": pools}, indent=2))

W("logres", [gear, mats, misc, compat])
# the same, with a lore book from the place's own group - rarely (books are a find, not a given)
def books(group, chance):
    return pool(1, [table(f"ancientpeaks_lore:books/{group}", max(1, int(chance * 800))), table("ancientpeaks_lore:books/all", max(1, int(chance * 200))),
                    empty(int((1 - chance) * 1000))])
# the castle (Camelot): better than the rest of Logres - mid-enchanted diamond, well-enchanted iron, now and then netherite
CASTLE_IRON = [("minecraft:iron_sword", 8), ("minecraft:iron_axe", 5), ("minecraft:iron_helmet", 6), ("minecraft:iron_chestplate", 6),
               ("minecraft:iron_leggings", 6), ("minecraft:iron_boots", 6), ("minecraft:bow", 5), ("minecraft:crossbow", 3)]
CASTLE_DIAMOND = [("minecraft:diamond_sword", 4), ("minecraft:diamond_axe", 2), ("minecraft:diamond_helmet", 3), ("minecraft:diamond_chestplate", 2),
                  ("minecraft:diamond_leggings", 2), ("minecraft:diamond_boots", 3), ("minecraft:diamond_pickaxe", 2)]
castle_gear = pool(1, [item(n, w, ench=(24, 30), dmg=(0.2, 0.6)) for n, w in CASTLE_IRON] +
                      [item(n, w, ench=(14, 22), dmg=(0.2, 0.6)) for n, w in CASTLE_DIAMOND] + [ebook(6, 18, 30), empty(30)])
castle_rare = pool(1, [item("minecraft:netherite_ingot", 2), item("minecraft:diamond", 8, 1, 2), item("minecraft:golden_apple", 4), empty(86)])
W("logres_guard", [castle_gear, mats, misc, compat, castle_rare, books("guard", 0.05)])
# special rooms (their chests keep these tables - LootProcessor leaves ancientpeaks_lore:chests/castle_* alone)
W("castle_treasure", [
    pool([2, 3], [item(n, w, ench=(14, 22), dmg=(0.05, 0.3)) for n, w in CASTLE_DIAMOND] + [item(n, w, ench=(26, 30), dmg=(0.05, 0.3)) for n, w in CASTLE_IRON]),
    pool([2, 4], [item("minecraft:gold_ingot", 12, 2, 7), item("minecraft:gold_block", 3, 1, 2), item("minecraft:emerald", 8, 2, 6),
                  item("minecraft:diamond", 6, 1, 3), item("minecraft:gold_nugget", 8, 4, 12), item("minecraft:amethyst_shard", 4, 2, 5)]),
    pool(1, [item("minecraft:netherite_ingot", 45, 1, 2), empty(55)]),
    pool(1, [ebook(10, 22, 30), item("minecraft:golden_apple", 6, 1, 2), item("minecraft:enchanted_golden_apple", 1), empty(8)])])
W("castle_armoury", [
    pool([1, 2], [item(n, w, ench=(24, 30), dmg=(0.2, 0.6)) for n, w in CASTLE_IRON] + [item("minecraft:shield", 5, dmg=(0.2, 0.6))]),
    pool(1, [item(n, w, ench=(14, 20), dmg=(0.3, 0.7)) for n, w in CASTLE_DIAMOND] + [empty(60)]),
    pool([1, 2], [item("minecraft:arrow", 10, 6, 16), item("minecraft:iron_ingot", 8, 2, 5), item("minecraft:spectral_arrow", 3, 2, 6),
                  item("minecraft:string", 4, 2, 5), item("minecraft:leather", 4, 1, 4)])])
W("castle_library", [
    pool([1, 3], [ebook(8, 15, 30), item("minecraft:book", 10, 1, 3), item("minecraft:paper", 8, 2, 6), item("minecraft:writable_book", 3),
                  item("minecraft:ink_sac", 4, 1, 3), item("minecraft:feather", 4, 1, 3), item("minecraft:experience_bottle", 4, 1, 3),
                  item("minecraft:lapis_lazuli", 5, 2, 6), item("minecraft:candle", 4, 1, 3)]),
    pool(1, [table("ancientpeaks_lore:books/guard", 20), table("ancientpeaks_lore:books/all", 10), empty(70)])])
W("castle_quarters", [pool([1, 3], food["entries"]), pool([0, 2], mats["entries"]), misc,
                      pool(1, [item(n, w, ench=(4, 14), dmg=(0.4, 0.85)) for n, w in IRON] + [empty(70)])])
for g, c in (("village", 0.03), ("chapel", 0.08), ("harbour", 0.05), ("all", 0.03)):
    W(f"logres_{g}", [gear, mats, misc, compat, books(g, c)])
# a raided supply cart: what the raiders left - some stores, a little metal, and sometimes the book that tells of it
W("logres_cart", [pool([1, 2], food["entries"]), pool([0, 1], mats["entries"]), pool(1, gear["entries"][:len(IRON)] + [empty(80)]),
                  pool(1, [table("ancientpeaks_lore:books/cart", 25), empty(75)])])
# barrels: a household's or a stall's stores - food, drink and now and then a potion
W("logres_stores", [food, potions, pool(1, [table("minecraft:chests/village/village_plains_house", 8), empty(92)])])

# Merlin's cabin: a scholar's chests (no lore books - his journal is on the lectern upstairs) and his stores
arcane = pool([1, 3], [item("minecraft:lapis_lazuli", 10, 2, 6), item("minecraft:amethyst_shard", 8, 1, 4), item("minecraft:glowstone_dust", 7, 1, 4),
                       item("minecraft:redstone", 6, 2, 5), item("minecraft:ender_pearl", 3, 1, 1), item("minecraft:experience_bottle", 5, 1, 3),
                       item("minecraft:paper", 6, 2, 6), item("minecraft:book", 6, 1, 2), item("minecraft:feather", 4, 1, 4), item("minecraft:ink_sac", 4, 1, 3),
                       item("minecraft:blaze_powder", 2, 1, 2), item("minecraft:nether_wart", 3, 1, 4), item("minecraft:candle", 5, 1, 3),
                       item("minecraft:clock", 1), item("minecraft:compass", 2), ebook(4, 15, 28)])
remedies = pool(1, [potion("minecraft:healing", 8), potion("minecraft:strong_healing", 3), potion("minecraft:regeneration", 4),
                    item("minecraft:golden_apple", 3), item("minecraft:glistering_melon_slice", 3, 1, 2), empty(12)])
W("logres_merlin", [arcane, pool(1, gear["entries"][:len(IRON)] + [empty(70)])])
W("logres_merlin_stores", [food, remedies])
print("loot tables written")
