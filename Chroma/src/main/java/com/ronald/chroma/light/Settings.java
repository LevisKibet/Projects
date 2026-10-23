package com.ronald.chroma.light;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/chroma-client.toml */
public final class Settings {
    private Settings() {}
    static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED, BLOCKS, DYNAMIC, REAL_LIGHT, AUTO_COLORS, GLASS, AMETHYST;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> COLORS;
    public static final ForgeConfigSpec.IntValue STRENGTH, MAX_LIGHTS, SCAN_RADIUS, UPDATE_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        ENABLED = b.comment("Coloured light on or off.").define("enabled", true);
        STRENGTH = b.comment("How strongly lights colour the world, 0 to 100.").defineInRange("strength", 70, 0, 100);
        BLOCKS = b.comment("Light-giving blocks (torches, soul fire, lanterns, glowstone, modded lights...) colour the light around them.").define("block_lights", true);
        DYNAMIC = b.comment("Held lights, burning mobs and glowing creatures cast light as they move.").define("dynamic_lights", true);
        REAL_LIGHT = b.comment("Moving lights really light up their surroundings (like a torch would), even in the dark - not just colour them.").define("real_light", true);
        UPDATE_TICKS = b.comment("Moving lights: how often (in ticks) the lit area follows them. Lower is smoother, higher is faster.").defineInRange("update_ticks", 2, 1, 10);
        MAX_LIGHTS = b.comment("Most lights drawn at once (the nearest win). Fewer is faster.").defineInRange("max_lights", 32, 4, 64);
        SCAN_RADIUS = b.comment("How far (in blocks) to look for light-giving blocks.").defineInRange("block_radius", 24, 8, 48);
        AUTO_COLORS = b.comment("Blocks Chroma Light doesn't know (modded ones too) glow the colour of their own texture.").define("auto_colors", true);
        GLASS = b.comment("Light shining through stained glass takes on the glass colour.").define("glass_filters_light", true);
        AMETHYST = b.comment("Amethyst blocks, buds and clusters glow purple (a little light of their own).").define("amethyst_glow", true);
        COLORS = b.comment("Your own light colours: \"block id=colour\" or \"modid:*=colour\" for a whole mod. Colour is a hex code (#ff8800) or a name",
                        "(red, orange, gold, yellow, green, lime, cyan, blue, purple, magenta, pink, teal, white).",
                        "Add @level to make a block glow even if it normally doesn't, e.g. \"somemod:crystal=#40ffd0@7\".")
                .defineListAllowEmpty(java.util.List.of("colors"), () -> java.util.List.of("minecraft:sea_lantern=#9fe8ff"), o -> o instanceof String str && str.contains("="));
        SPEC = b.build();
    }

    static boolean on(ForgeConfigSpec.BooleanValue v) { try { return v.get(); } catch (IllegalStateException e) { return false; } }
    static int num(ForgeConfigSpec.IntValue v, int d) { try { return v.get(); } catch (IllegalStateException e) { return d; } }
    public static boolean enabled() { return on(ENABLED); }
    public static boolean blocks() { return on(BLOCKS); }
    public static boolean dynamic() { return on(DYNAMIC); }
    public static float strength() { return num(STRENGTH, 70) / 100f; }
    public static int maxLights() { return num(MAX_LIGHTS, 32); }
    public static boolean realLight() { return on(REAL_LIGHT); }
    public static int updateTicks() { return num(UPDATE_TICKS, 2); }
    public static boolean autoColors() { return on(AUTO_COLORS); }
    public static boolean amethystGlow() { return on(AMETHYST); }
    public static boolean glass() { return on(GLASS); }
    public static java.util.List<? extends String> colorList() { try { return COLORS.get(); } catch (IllegalStateException e) { return java.util.List.of(); } }
    public static int scanRadius() { return num(SCAN_RADIUS, 24); }
}
