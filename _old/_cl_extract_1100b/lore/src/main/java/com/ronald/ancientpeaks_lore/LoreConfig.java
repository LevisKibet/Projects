package com.ronald.ancientpeaks_lore;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/ancientpeaks_lore-common.toml: the map to Merlin's cabin, rarely for sale. */
public final class LoreConfig {
    private LoreConfig() {}

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue TRADER, CARTOGRAPHER;
    public static final ForgeConfigSpec.DoubleValue TRADER_CHANCE, CARTOGRAPHER_CHANCE;
    public static final ForgeConfigSpec.IntValue TRADER_PRICE, CARTOGRAPHER_PRICE, USES;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("The Map to Merlin's Cabin: rarely for sale.").push("merlin_map");
        TRADER = b.comment("The wandering trader can offer it as one of its rare trades.").define("wandering_trader", true);
        TRADER_CHANCE = b.comment("Chance it turns up when the wandering trader's rare offer is picked (0 to 1).")
                .defineInRange("wandering_trader_chance", 0.3, 0.0, 1.0);
        TRADER_PRICE = b.comment("Emeralds the wandering trader asks (plus a compass).").defineInRange("wandering_trader_price", 14, 1, 64);
        CARTOGRAPHER = b.comment("A master cartographer can offer it among their last trades.").define("cartographer", true);
        CARTOGRAPHER_CHANCE = b.comment("Chance it turns up among a master cartographer's trades (0 to 1).")
                .defineInRange("cartographer_chance", 0.15, 0.0, 1.0);
        CARTOGRAPHER_PRICE = b.comment("Emeralds the cartographer asks (plus a compass).").defineInRange("cartographer_price", 18, 1, 64);
        USES = b.comment("How many maps each of them will sell before the offer runs out.").defineInRange("uses", 1, 1, 12);
        b.pop();
        SPEC = b.build();
    }

    static boolean on(ForgeConfigSpec.BooleanValue v, boolean d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
    static double num(ForgeConfigSpec.DoubleValue v, double d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
    static int num(ForgeConfigSpec.IntValue v, int d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
}
