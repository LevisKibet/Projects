# Ancient Peaks structures

Each folder here is one structure. Building the mod (`./build_all.sh`) turns these folders into worldgen data.

    structures/
      plains_village/
        structure.json      where and how it is placed
        house_a.nbt         start pieces: one is picked at random each time
        house_b.schem
        streets/            optional extra jigsaw pools -> "ancientpeaks:modular/plains_village/streets"
          street_1.nbt

## Schematics
- `.nbt` saved with a Structure Block (Save mode) is best. Air blocks in it clear terrain; use Structure Void to keep the ground.
- `.schem` from WorldEdit (`//schem save`) also works. Air is skipped, so the ground under and around it is kept.
- A folder with no schematic gets a small placeholder (a stone ring with a lantern pillar and a sign) so you can find the spot in game.
- Files whose names start with `placeholder` are ignored as soon as a real schematic is present.

## structure.json
    {
      "anchor": "plains_village",   // where it goes (list below)
      "offset": [0, 0, 0],          // x and z shift from the anchor; y = blocks above the ground (negative sinks it)
      "terrain": "beard_thin",      // how the ground blends: none | beard_thin | beard_box | bury | encapsulate
      "jigsaw_depth": 1,            // >1 lets jigsaw blocks in the pieces pull in more pieces (villages)
      "weights": {"house_a": 3},    // optional: how often each start piece is picked (default 1)
      "processors": "minecraft:empty",
      "enabled": true
    }

## Anchors
- `plains_village`, `plains_castle`, `plains_center`: on the island's lowland plains
- `meadow_plateau`: the big flat shelf high on the main mountain
- `great_arch`, `main_peak`
- `gateway`: beside every Highland Gateway portal in the overworld, and beside the portal on the island's coast

`/ancientpeaks island` prints the anchor coordinates of the nearest island.
