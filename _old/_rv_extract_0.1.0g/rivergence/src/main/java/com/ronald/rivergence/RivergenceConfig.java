package com.ronald.rivergence;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/rivergence-common.toml */
public final class RivergenceConfig {
    private RivergenceConfig() {}

    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue ENABLED, STRUCTURES_AVOID, REPLACE_RIVERS, NO_SPRINGS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("general");
        ENABLED = b.comment("Rivergence's water: streams, rivers and lakes in place of vanilla's. Only affects terrain generated after the change.")
                .define("enabled", true);
        STRUCTURES_AVOID = b.comment("Villages, outposts, temples and huts aren't built on top of rivers and lakes (they're built elsewhere instead).")
                .define("structures_avoid_water", true);
        REPLACE_RIVERS = b.comment("Vanilla's river valleys are filled in, so the only rivers are Rivergence's (which follow the same valleys).")
                .define("replace_vanilla_rivers", true);
        NO_SPRINGS = b.comment("No vanilla water springs pouring out of cliffs and hillsides (Rivergence's streams rise from springs of their own).")
                .define("remove_vanilla_springs", true);
        b.pop();
        SPEC = b.build();
    }

    static boolean on(ForgeConfigSpec.BooleanValue v, boolean d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
    public static boolean enabled() { return on(ENABLED, true); }
    public static boolean replaceVanillaRivers() { return on(REPLACE_RIVERS, true); }
    public static boolean removeVanillaSprings() { return on(NO_SPRINGS, true); }
    public static boolean structuresAvoidWater() { return on(STRUCTURES_AVOID, true); }
}
