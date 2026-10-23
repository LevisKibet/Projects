package com.ronald.rivergence.snow;

import net.minecraftforge.common.ForgeConfigSpec;

/** The [snow] section of config/rivergence-common.toml: Rivergence's snow (soft, smooth, drifting snow you wade through and leave trails in). */
public final class SnowConfig {
    private SnowConfig() {}

    // the settings are built as part of Rivergence's common config: make sure that's happened whichever is touched first
    static { Object o = com.ronald.rivergence.RivergenceConfig.SPEC; }

    public enum Detail {
        LOW(2, 4), MEDIUM(4, 8), HIGH(4, 16), ULTRA(8, 16);
        public final int coarse, fine;
        Detail(int coarse, int fine) { this.coarse = coarse; this.fine = fine; }
    }

    public static ForgeConfigSpec SPEC;
    public static ForgeConfigSpec.BooleanValue ENABLED, SMOOTH, OVERHANGS, DRIFTS, TREE_SNOW, SNOWFALL, WADING, TRAILS, MOB_TRAILS;
    public static ForgeConfigSpec.IntValue MAX_DEPTH, MAX_TRAILS;
    public static ForgeConfigSpec.EnumValue<Detail> DETAIL;

    /** Called while Rivergence's common config is built, so the snow settings sit in the same file and screen. */
    public static void define(ForgeConfigSpec.Builder b) {
        b.push("snow");
        ENABLED = b.comment("Rivergence's snow as a whole. Off: snow is vanilla's again (drifts already generated stay as they are).").define("enabled", true);
        SMOOTH = b.comment("Snow drawn as one smooth surface instead of stepped layers.").define("smooth", true);
        DETAIL = b.comment("How finely the snow's surface and trails are drawn. LOW is lightest and blockiest; HIGH and ULTRA are smoothest (ULTRA also smooths untouched snow more finely).")
                .defineEnum("detail", Detail.MEDIUM);
        OVERHANGS = b.comment("Snow spills over the edges of ledges and leaves and hangs down their sides.").define("overhangs", true);
        DRIFTS = b.comment("Deeper snow in cold biomes, piled up against walls and in hollows.").define("drifts", true);
        MAX_DEPTH = b.comment("The deepest a drift gets, in snow layers (8 would be a full block).").defineInRange("max_depth", 4, 1, 7);
        TREE_SNOW = b.comment("Snow settles on the lower boughs of trees and under them, not only on top.").define("tree_snow", true);
        SNOWFALL = b.comment("Snowfall builds the drifts back up over time.").define("snowfall_builds_drifts", true);
        WADING = b.comment("You sink into soft snow and wade through it, a little slower the deeper it is.").define("wading", true);
        TRAILS = b.comment("Wading through snow ploughs a soft groove through it (drawn on your screen only).").define("trails", true);
        MOB_TRAILS = b.comment("Animals and mobs leave grooves too, not only players.").define("mob_trails", true);
        MAX_TRAILS = b.comment("How many blocks of trail are remembered at once (the oldest go first). Lower is lighter.").defineInRange("max_trail_blocks", 8000, 500, 100000);
        b.pop();
    }

    private static boolean on(ForgeConfigSpec.BooleanValue v) { try { return v == null || v.get(); } catch (IllegalStateException e) { return true; } }
    public static boolean wading() { return enabled() && on(WADING); }
    public static boolean enabled() { return on(ENABLED); }
    public static boolean smooth() { return enabled() && on(SMOOTH); }
    public static Detail detail() { try { return DETAIL.get(); } catch (IllegalStateException e) { return Detail.MEDIUM; } }
}
