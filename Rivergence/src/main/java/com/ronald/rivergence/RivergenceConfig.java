package com.ronald.rivergence;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/rivergence-common.toml */
public final class RivergenceConfig {
    private RivergenceConfig() {}

    public static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue ENABLED, STRUCTURES_AVOID, REPLACE_RIVERS, NO_SPRINGS, FLORA, FAUNA, BAYS, WEATHER, CANALS, SCHOOLS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Settings that shape the land (enabled, structures_avoid_water, replace_vanilla_rivers, remove_vanilla_springs, bays,",
                "canals) are fixed into each world the first time it's opened (rivergence-generation.properties in the world folder),",
                "so changing them here only affects worlds created afterwards; existing worlds never get seams.").push("general");
        ENABLED = b.comment("Rivergence's water: streams, rivers and lakes in place of vanilla's. Only affects terrain generated after the change.")
                .define("enabled", true);
        STRUCTURES_AVOID = b.comment("Villages, outposts, temples and huts aren't built on top of rivers and lakes (they're built elsewhere instead).")
                .define("structures_avoid_water", true);
        REPLACE_RIVERS = b.comment("Vanilla's river valleys are filled in, so the only rivers are Rivergence's (which follow the same valleys).")
                .define("replace_vanilla_rivers", true);
        NO_SPRINGS = b.comment("No vanilla water springs pouring out of cliffs and hillsides (Rivergence's streams rise from springs of their own).")
                .define("remove_vanilla_springs", true);
        FLORA = b.comment("Plants in and round the water: seagrass, kelp, lily pads, moss, dripleaf, azaleas, glow berries and more.")
                .define("flora", true);
        FAUNA = b.comment("Fish in rivers and streams (salmon and cod), axolotls and fish in lakes, ponds, springs and plunge pools.")
                .define("fauna", true);
        BAYS = b.comment("Bays and coves cut into low coasts, with beaches round them. Switched off by itself when a terrain mod that reshapes coastlines (Tectonic) is installed.")
                .define("bays", true);
        CANALS = b.comment("Canals: straight stone-lined channels cut from a stream or river out toward a village's fields, planted with rice when Farmer's Delight is installed.")
                .define("canals", true);
        WEATHER = b.comment("Weather that belongs to the water (on your screen only): mist over lakes and rivers at dawn, fog settling in river valleys, spray at waterfalls, drizzle over wetlands and passing showers over big water.")
                .define("water_weather", true);
        SCHOOLS = b.comment("Schools of small fish drawn in oceans, lakes and big rivers (on your screen only, nothing to catch): they scatter as you come near and part round you if you swim among them. Now and then a pair of koi circles in a plunge pool.")
                .define("fish_schools", true);
        b.pop();
        com.ronald.rivergence.snow.SnowConfig.define(b);
        SPEC = b.build();
        com.ronald.rivergence.snow.SnowConfig.SPEC = SPEC;
    }

    static boolean on(ForgeConfigSpec.BooleanValue v, boolean d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
    public static boolean enabled() { return com.ronald.rivergence.world.GenLock.get("enabled", on(ENABLED, true)); }
    public static boolean replaceVanillaRivers() { return com.ronald.rivergence.world.GenLock.get("replace_vanilla_rivers", on(REPLACE_RIVERS, true)); }
    public static boolean removeVanillaSprings() { return com.ronald.rivergence.world.GenLock.get("remove_vanilla_springs", on(NO_SPRINGS, true)); }
    public static boolean flora() { return on(FLORA, true); }
    public static boolean fauna() { return on(FAUNA, true); }
    public static boolean bays() {
        if (Compat.tectonic()) return false;
        return com.ronald.rivergence.world.GenLock.get("bays", on(BAYS, true));
    }
    public static boolean canals() { return com.ronald.rivergence.world.GenLock.get("canals", on(CANALS, true)); }
    public static boolean weather() { return on(WEATHER, true); }
    public static boolean schools() { return on(SCHOOLS, true); }
    public static boolean structuresAvoidWater() { return com.ronald.rivergence.world.GenLock.get("structures_avoid_water", on(STRUCTURES_AVOID, true)); }

    /** The settings that shape a world's land, as they are now (fixed into a new world by GenLock). */
    public static void recordGeneration(java.util.Properties p) {
        p.setProperty("enabled", String.valueOf(on(ENABLED, true)));
        p.setProperty("replace_vanilla_rivers", String.valueOf(on(REPLACE_RIVERS, true)));
        p.setProperty("remove_vanilla_springs", String.valueOf(on(NO_SPRINGS, true)));
        p.setProperty("bays", String.valueOf(on(BAYS, true) && !Compat.tectonic()));
        p.setProperty("canals", String.valueOf(on(CANALS, true)));
        p.setProperty("structures_avoid_water", String.valueOf(on(STRUCTURES_AVOID, true)));
    }
}
