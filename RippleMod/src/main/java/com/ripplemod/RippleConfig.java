package com.ripplemod;

import net.minecraftforge.common.ForgeConfigSpec;

public class RippleConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENTERING_WATER;
    public static final ForgeConfigSpec.BooleanValue SPRAY;
    public static final ForgeConfigSpec.BooleanValue WAKES;
    public static final ForgeConfigSpec.BooleanValue ELYTRA;
    public static final ForgeConfigSpec.BooleanValue WATERFALLS;
    public static final ForgeConfigSpec.BooleanValue SHORE_WAVES;
    public static final ForgeConfigSpec.IntValue SHORE_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_PATCHES;
    public static final ForgeConfigSpec.IntValue RANGE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("ripples");
        ENTERING_WATER = b.comment("Rings when something lands in water.")
                .define("entering_water", true);
        SPRAY = b.comment("Water thrown up when something drops into water, sized by the object.")
                .define("splash_spray", true);
        WAKES = b.comment("V-shaped wakes behind things moving through water, and boat paddle ripples.")
                .define("wakes", true);
        ELYTRA = b.comment("Wakes on the water below when flying low with an elytra.")
                .define("elytra", true);
        WATERFALLS = b.comment("Occasional ripples where a waterfall lands in water.")
                .define("waterfalls", true);
        SHORE_WAVES = b.comment("Wet sand and washing foam on beaches where land is level with the water.")
                .define("shore_waves", true);
        SHORE_RANGE = b.comment("How far away, in blocks, beaches show shore waves.")
                .defineInRange("shore_range", 32, 8, 48);
        MAX_PATCHES = b.comment("How many patches of simulated water can be active at once. Lower is cheaper.")
                .defineInRange("max_active_patches", 10, 1, 24);
        RANGE = b.comment("Only things within this many blocks of you make ripples.")
                .defineInRange("entity_range", 32, 8, 96);
        b.pop();
        SPEC = b.build();
    }
}
