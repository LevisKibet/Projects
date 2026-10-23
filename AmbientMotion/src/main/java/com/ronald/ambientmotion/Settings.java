package com.ronald.ambientmotion;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings, kept in config/ambientmotion-client.toml and changed from the settings screen. */
public final class Settings {
    private Settings() {}

    public enum DofMode {
        OFF("Off"), CLOSE_UP("Close-up focus"), TILT_SHIFT("Tilt-shift");
        public final String label;
        DofMode(String label) { this.label = label; }
        public DofMode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MOTION_BLUR, DOF_STRENGTH;
    public static final ForgeConfigSpec.DoubleValue FOCUS_RANGE;
    public static final ForgeConfigSpec.EnumValue<DofMode> DOF_MODE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("motion_blur");
        MOTION_BLUR = b.comment("Camera motion blur when you turn, 0 (off) to 100. Only turning blurs; walking and slow looks never do.")
                .defineInRange("strength", 25, 0, 100);
        b.pop().push("depth_of_field");
        DOF_MODE = b.comment("OFF, CLOSE_UP (look at something right next to you and the background softens) or TILT_SHIFT (miniature look).")
                .defineEnum("mode", DofMode.CLOSE_UP);
        DOF_STRENGTH = b.comment("How soft the background gets, 0 to 100.").defineInRange("strength", 60, 0, 100);
        FOCUS_RANGE = b.comment("Close-up focus: how near (in blocks, from you to the thing you look at) it must be for the background to soften.")
                .defineInRange("focus_range", 1.0, 0.25, 4.0);
        b.pop();
        SPEC = b.build();
    }

    static int get(ForgeConfigSpec.IntValue v, int fallback) {
        try { return v.get(); } catch (IllegalStateException notLoaded) { return fallback; }
    }
    public static float motionBlur() { return get(MOTION_BLUR, 0) / 100f; }
    public static float dofStrength() { return get(DOF_STRENGTH, 0) / 100f; }
    public static double focusRange() { try { return FOCUS_RANGE.get(); } catch (IllegalStateException e) { return 1.0; } }
    public static DofMode dofMode() { try { return DOF_MODE.get(); } catch (IllegalStateException e) { return DofMode.OFF; } }
}
