package com.ronald.freshpowder;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final ForgeConfigSpec COMMON_SPEC, CLIENT_SPEC;
    public static final ForgeConfigSpec.BooleanValue DRIFTS, TREE_SNOW, SNOWFALL, WADING;
    public static final ForgeConfigSpec.IntValue MAX_DEPTH;
    public static final ForgeConfigSpec.BooleanValue TRAILS, MOB_TRAILS;
    public static final ForgeConfigSpec.IntValue MAX_TRAILS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("snow");
        DRIFTS = b.comment("Deeper snow in cold biomes, piled up against walls and in hollows.").define("drifts", true);
        MAX_DEPTH = b.comment("The deepest a drift gets, in snow layers (8 would be a full block).").defineInRange("max_depth", 4, 1, 7);
        TREE_SNOW = b.comment("Snow settles on the lower boughs of trees and under them, not only on top.").define("tree_snow", true);
        SNOWFALL = b.comment("Snowfall builds the drifts back up over time (and fills in old trails).").define("snowfall_builds_drifts", true);
        WADING = b.comment("Deep snow slows you a little as you wade through it.").define("wading", true);
        b.pop();
        COMMON_SPEC = b.build();

        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();
        c.push("trails");
        TRAILS = c.comment("Walking through snow ploughs a soft groove through it.").define("trails", true);
        MOB_TRAILS = c.comment("Animals and mobs leave grooves too, not only players.").define("mob_trails", true);
        MAX_TRAILS = c.comment("How many blocks of trail are remembered at once (the oldest go first). Lower is cheaper.").defineInRange("max_trail_blocks", 20000, 500, 200000);
        c.pop();
        CLIENT_SPEC = c.build();
    }

    private Config() {}
}
