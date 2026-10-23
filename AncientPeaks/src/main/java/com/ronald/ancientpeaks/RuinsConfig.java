package com.ronald.ancientpeaks;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/ancientpeaks-common.toml: the Highland Gateways, the island map trade and where the island lies. */
public final class RuinsConfig {
    private RuinsConfig() {}

    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue GATEWAYS, TRADER, CARTOGRAPHER;
    static final ForgeConfigSpec.DoubleValue TRADER_CHANCE, CARTOGRAPHER_CHANCE;
    static final ForgeConfigSpec.IntValue TRADER_PRICE, CARTOGRAPHER_PRICE, USES, DISTANCE_MIN, DISTANCE_MAX;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("gateways");
        GATEWAYS = b.comment("Highland Gateways: ancient portals in the overworld that lead to the island (needs the Immersive Gateways mod).",
                        "Set to false to skip them: no gateway ruins generate and the island is reached by sea.",
                        "Only affects terrain generated after the change.")
                .define("enabled", true);
        b.pop();

        b.comment("The Chart of the Ancient Highlands: a map to the island, rarely for sale.").push("island_map");
        TRADER = b.comment("The wandering trader can offer it as one of its rare trades.").define("wandering_trader", true);
        TRADER_CHANCE = b.comment("Chance it turns up when the wandering trader's rare offer is picked (0 to 1).")
                .defineInRange("wandering_trader_chance", 0.25, 0.0, 1.0);
        TRADER_PRICE = b.comment("Emeralds the wandering trader asks (plus a compass).").defineInRange("wandering_trader_price", 40, 1, 64);
        CARTOGRAPHER = b.comment("A master cartographer can offer it among their last trades.").define("cartographer", true);
        CARTOGRAPHER_CHANCE = b.comment("Chance it turns up among a master cartographer's trades (0 to 1).")
                .defineInRange("cartographer_chance", 0.15, 0.0, 1.0);
        CARTOGRAPHER_PRICE = b.comment("Emeralds the cartographer asks (plus a compass).").defineInRange("cartographer_price", 32, 1, 64);
        USES = b.comment("How many maps each of them will sell before the offer runs out.").defineInRange("uses", 1, 1, 12);
        b.pop();

        b.comment("Where the island lies. It is placed once, when a world is created: changing this only matters for new worlds",
                "(in an existing world it would move the island away from the land already generated).").push("island");
        DISTANCE_MIN = b.comment("Nearest the island's centre may be to spawn, in blocks.").defineInRange("distance_min", 9000, 2000, 29000);
        DISTANCE_MAX = b.comment("Farthest the island's centre may be from spawn, in blocks (at least 1000 more than the nearest).")
                .defineInRange("distance_max", 10000, 3000, 30000);
        b.pop();
        SPEC = b.build();
    }

    static boolean on(ForgeConfigSpec.BooleanValue v, boolean d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
    static double num(ForgeConfigSpec.DoubleValue v, double d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
    static int num(ForgeConfigSpec.IntValue v, int d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }

    /** {nearest, farthest} distance of the island's centre from spawn, kept at least 1000 apart. */
    public static int[] islandRing() {
        int lo = num(DISTANCE_MIN, 9000), hi = Math.max(num(DISTANCE_MAX, 10000), lo + 1000);
        return new int[]{lo, hi};
    }
}
