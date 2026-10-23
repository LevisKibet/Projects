package com.ronald.rivergence;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/rivergence-common.toml */
public final class RivergenceConfig {
    private RivergenceConfig() {}

    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue ENABLED, STRUCTURES_AVOID;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("general");
        ENABLED = b.comment("Rivergence's water: streams, rivers and lakes in place of vanilla's. Only affects terrain generated after the change.")
                .define("enabled", true);
        STRUCTURES_AVOID = b.comment("Villages, outposts, temples and huts aren't built on top of rivers and lakes (they're built elsewhere instead).")
                .define("structures_avoid_water", true);
        b.pop();
        SPEC = b.build();
    }

    static boolean on(ForgeConfigSpec.BooleanValue v, boolean d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
    public static boolean enabled() { return on(ENABLED, true); }
    public static boolean structuresAvoidWater() { return on(STRUCTURES_AVOID, true); }
}
