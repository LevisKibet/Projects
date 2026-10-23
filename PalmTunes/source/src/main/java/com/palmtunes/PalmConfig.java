package com.palmtunes;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config ({@code config/palmtunes-client.toml}). Everything about the pose, camera and panel
 * placement is tunable here because it is much faster to nudge a number and re-open the panel
 * than to recompile.
 */
public final class PalmConfig {
    public static final ForgeConfigSpec SPEC;
    public static final PalmConfig CLIENT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CLIENT = new PalmConfig(builder);
        SPEC = builder.build();
    }

    // Media
    public final ForgeConfigSpec.IntValue pollIntervalMs;
    public final ForgeConfigSpec.BooleanValue fetchRemoteArt;

    // Camera (distances in blocks, relative to the player's body direction)
    public final ForgeConfigSpec.DoubleValue cameraForward;
    public final ForgeConfigSpec.DoubleValue cameraSide;
    public final ForgeConfigSpec.DoubleValue cameraHeight;
    public final ForgeConfigSpec.DoubleValue lookAhead;
    public final ForgeConfigSpec.DoubleValue lookSide;
    public final ForgeConfigSpec.DoubleValue lookHeight;
    public final ForgeConfigSpec.IntValue transitionTicks;

    // Arm pose (radians)
    public final ForgeConfigSpec.DoubleValue armPitch;
    public final ForgeConfigSpec.DoubleValue armYaw;
    public final ForgeConfigSpec.DoubleValue armRoll;
    public final ForgeConfigSpec.DoubleValue armLength;

    // HUD pulse
    public final ForgeConfigSpec.BooleanValue hudEnabled;
    public final ForgeConfigSpec.BooleanValue hudSongToast;
    public final ForgeConfigSpec.DoubleValue hudToastSeconds;
    public final ForgeConfigSpec.BooleanValue hudIdleHint;
    public final ForgeConfigSpec.DoubleValue hudScale;

    // Audio
    public final ForgeConfigSpec.BooleanValue muteGameMusic;

    // Cinematic
    public final ForgeConfigSpec.BooleanValue cinematicEnabled;
    public final ForgeConfigSpec.IntValue cinematicAfkSeconds;
    public final ForgeConfigSpec.IntValue cinematicHostileRadius;
    public final ForgeConfigSpec.IntValue cinematicShotSeconds;
    public final ForgeConfigSpec.BooleanValue cinematicMotionBlur;
    public final ForgeConfigSpec.DoubleValue cinematicBlurStrength;
    public final ForgeConfigSpec.BooleanValue cinematicDepthOfField;

    // Panel
    public final ForgeConfigSpec.DoubleValue panelWidth;
    public final ForgeConfigSpec.DoubleValue panelGap;
    public final ForgeConfigSpec.DoubleValue panelOffsetX;
    public final ForgeConfigSpec.DoubleValue panelYaw;
    public final ForgeConfigSpec.DoubleValue panelPush;
    public final ForgeConfigSpec.DoubleValue panelDepthBias;
    public final ForgeConfigSpec.BooleanValue showPalmGlow;
    public final ForgeConfigSpec.BooleanValue bodyAnchor;

    private PalmConfig(ForgeConfigSpec.Builder b) {
        b.push("media");
        pollIntervalMs = b.comment("How often the active media session is polled while the panel is open (milliseconds).")
                .defineInRange("pollIntervalMs", 1000, 250, 5000);
        fetchRemoteArt = b.comment("Download cover art from the web when a player only provides a URL (Spotify does).",
                        "Turn off to use only art that is already on your machine.")
                .define("fetchRemoteArt", true);
        b.pop();

        b.push("camera");
        cameraForward = b.comment("How far in FRONT of the player the camera sits (negative = behind).",
                        "The default is an over-the-shoulder shot from behind.")
                .defineInRange("distanceInFront", -0.8, -8.0, 8.0);
        cameraSide = b.comment("How far to the player's right the camera sits (negative = left side).")
                .defineInRange("sideOffset", 0.6, -4.0, 4.0);
        cameraHeight = b.comment("Camera height relative to the player's eyes.")
                .defineInRange("heightOffset", 0.1, -2.0, 3.0);
        lookAhead = b.comment("The camera aims at a point this far in front of the player's eyes.")
                .defineInRange("lookAhead", 1.0, -3.0, 3.0);
        lookSide = b.comment("Aim point offset towards the player's right. Larger values push the player further to the right of the screen.")
                .defineInRange("lookSide", 0.75, -3.0, 3.0);
        lookHeight = b.comment("Aim point height relative to the player's eyes.")
                .defineInRange("lookHeight", 0.0, -3.0, 3.0);
        transitionTicks = b.comment("Length of the camera and arm animation, in ticks (20 = 1 second).")
                .defineInRange("transitionTicks", 18, 1, 60);
        b.pop();

        b.push("arm");
        armPitch = b.comment("Right arm raise. -1.5708 is straight forward, 0 is hanging down.")
                .defineInRange("pitch", -1.30, -3.2, 3.2);
        armYaw = b.comment("Right arm sideways swing. Negative moves the hand towards the body's centre.")
                .defineInRange("swingOut", 0.32, -3.2, 3.2);
        armRoll = b.comment("Twist along the arm. -1.5708 turns the palm to face up.")
                .defineInRange("roll", -1.5708, -3.2, 3.2);
        armLength = b.comment("How much longer the raised right arm is, so the hand reaches out towards the panel. 1.0 is normal.")
                .defineInRange("length", 1.3, 1.0, 2.0);
        b.pop();

        b.push("hud");
        hudEnabled = b.comment("Show the pulsing music bars at the top of the screen while the panel is closed.",
                        "This keeps checking your media player in the background (about once every 1.5 s).")
                .define("enabled", true);
        hudSongToast = b.comment("When a new song starts, show its cover, title and artist briefly under the bars.")
                .define("songToast", true);
        hudToastSeconds = b.comment("How long the title and artist stay unfolded, in seconds.")
                .defineInRange("toastSeconds", 4.5, 1.0, 20.0);
        hudIdleHint = b.comment("Show a hint when no music app can be found.")
                .define("idleHint", true);
        hudScale = b.comment("Size of the pulse HUD.")
                .defineInRange("scale", 1.0, 0.5, 3.0);
        b.pop();

        b.push("audio");
        muteGameMusic = b.comment("Silence Minecraft's own background music while music is playing on your computer.",
                        "Your volume sliders are not changed; the game just does not start its tracks.")
                .define("muteGameMusic", true);
        b.pop();

        b.push("cinematic");
        cinematicEnabled = b.comment("After you stop touching the game for a while, switch to a slow cinematic camera.")
                .define("enabled", true);
        cinematicAfkSeconds = b.comment("Seconds without any input before the cinematic camera starts.")
                .defineInRange("afkSeconds", 45, 3, 3600);
        cinematicHostileRadius = b.comment("The cinematic never starts, and ends at once, if a hostile mob is this close (blocks).")
                .defineInRange("hostileRadius", 20, 4, 64);
        cinematicShotSeconds = b.comment("Shortest a shot lasts, in seconds. Each shot lasts between this and twice this.")
                .defineInRange("minShotSeconds", 30, 10, 300);
        cinematicMotionBlur = b.comment("Motion blur around the subject while the camera circles or follows a single flower, lantern or animal.")
                .define("motionBlur", true);
        cinematicBlurStrength = b.comment("How strong that blur is. 1.0 is the default, 0 turns it off.")
                .defineInRange("blurStrength", 1.0, 0.0, 3.0);
        cinematicDepthOfField = b.comment("Softly blur the background (depth of field) while the camera focuses on a single subject.")
                .define("depthOfField", true);
        b.pop();

        b.push("panel");
        panelWidth = b.comment("Width of the glass panel in blocks.")
                .defineInRange("widthBlocks", 2.6, 0.5, 5.0);
        panelGap = b.comment("Gap between the palm and the bottom of the panel, in blocks.")
                .defineInRange("gapAbovePalm", -0.3, -2.0, 2.0);
        panelOffsetX = b.comment("Slides the panel sideways, in blocks, as seen from the camera. Positive is right.")
                .defineInRange("sideShift", 0.55, -2.0, 2.0);
        panelPush = b.comment("Pushes the panel away from the camera, in blocks, so it floats ahead of the hand.")
                .defineInRange("push", 0.5, -1.0, 3.0);
        panelYaw = b.comment("Turns the panel around its vertical axis, in degrees, so it is seen at a slant",
                        "instead of face-on. Negative brings the right edge towards the camera, positive the left.")
                .defineInRange("angle", -30.0, -80.0, 80.0);
        panelDepthBias = b.comment("Pulls the panel towards the camera so it never clips into the arm.",
                        "The picture does not change size; only depth does.")
                .defineInRange("depthBias", 0.08, 0.0, 0.5);
        showPalmGlow = b.comment("Draw a soft light on the palm under the panel.")
                .define("palmGlow", true);
        b.pop();

        b.push("compat");
        bodyAnchor = b.comment("Place the panel from the player's body position instead of the player model's arm.",
                        "PalmTunes does this by itself when another mod (Epic Fight, for example) replaces the player renderer;",
                        "turn this on to force it if the panel floats in the wrong place with a custom player model.")
                .define("bodyAnchor", false);
        b.pop();
    }
}
