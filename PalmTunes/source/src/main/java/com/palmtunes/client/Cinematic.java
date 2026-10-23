package com.palmtunes.client;

import com.palmtunes.PalmConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The idle cinematic: after the player stops touching the game for a while, the camera drifts away from
 * them and films points of interest. It ends when the player does anything, or a hostile mob gets close.
 */
public final class Cinematic {
    private static final int ENTER_TICKS = 30;
    private static final int EXIT_TICKS = 14;
    private static final float FADE_SECONDS = 1.1f;

    private static boolean wanted;
    private static boolean overriding;
    private static float progress;
    private static float prevProgress;
    private static CameraType previousCamera = CameraType.FIRST_PERSON;
    private static boolean startedFirstPerson;

    private static int idleTicks;
    private static int cooldown;
    private static double lastMouseX = Double.NaN;
    private static double lastMouseY;
    private static final RandomSource RNG = RandomSource.create();

    // shot state (render thread)
    private static CinematicShots.Shot shot;
    private static float shotTime;
    private static CinematicShots.Pose lastPose;
    private static int fadeState;           // 0 none, 1 fading out, 2 fading in
    private static float fadeTime;
    private static float fadeAlpha;
    private static long lastNanos;
    private static float fovScale = 1f;
    private static float focusAmount;
    private static float focusDistance = 4f;

    private Cinematic() {
    }

    public static boolean isOverriding() {
        return overriding;
    }

    /** True once the cinematic has taken over the screen, so the HUD should be out of the way. */
    public static boolean hidesHud() {
        return overriding;
    }

    /** 0..1 black overlay used to cut between shots. */
    public static float fadeAlpha() {
        return fadeAlpha;
    }

    public static float fovScale() {
        return fovScale;
    }

    public static float focusAmount() {
        return focusAmount * Mth.clamp(progress, 0f, 1f);
    }

    public static float focusDistance() {
        return focusDistance;
    }

    /** Any key, mouse button or scroll. */
    public static void noteInput() {
        idleTicks = 0;
        if (wanted) {
            stop();
        }
    }

    /** Starts straight away, for testing (`/palmtunes cinematic`). */
    public static void forceStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null && !wanted && !PalmController.isOverriding()) {
            start(mc);
        }
    }

    public static void stop() {
        wanted = false;
    }

    public static void stopImmediately() {
        Minecraft mc = Minecraft.getInstance();
        wanted = false;
        progress = 0f;
        prevProgress = 0f;
        if (overriding) {
            overriding = false;
            if (mc.options != null) {
                mc.options.setCameraType(previousCamera);
            }
        }
        shot = null;
        fadeState = 0;
        fadeAlpha = 0f;
    }

    private static void start(Minecraft mc) {
        wanted = true;
        if (!overriding) {
            previousCamera = mc.options.getCameraType();
            startedFirstPerson = previousCamera.isFirstPerson();
            overriding = true;
        }
        if (mc.options.getCameraType().isFirstPerson()) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        shot = null;
        lastPose = null;
        fadeState = 0;
        fadeAlpha = 0f;
        fovScale = 1f;
        focusAmount = 0f;
        CinematicShots.reset();
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !PalmConfig.CLIENT.cinematicEnabled.get()) {
            idleTicks = 0;
            if (overriding) {
                stopImmediately();
            }
            return;
        }

        boolean busy = mc.screen != null || PalmController.isOverriding() || !player.isAlive();
        if (busy) {
            idleTicks = 0;
            if (overriding) {
                stopImmediately();
            }
            lastMouseX = Double.NaN;
            return;
        }

        // Activity that does not arrive as key events: moving the mouse, walking, being hit.
        double mx = mc.mouseHandler.xpos();
        double my = mc.mouseHandler.ypos();
        boolean moved = !Double.isNaN(lastMouseX) && (Math.abs(mx - lastMouseX) > 0.5 || Math.abs(my - lastMouseY) > 0.5);
        lastMouseX = mx;
        lastMouseY = my;
        boolean acting = moved || player.hurtTime > 0 || player.input.jumping || player.input.shiftKeyDown
                || Math.abs(player.input.forwardImpulse) > 0.01f || Math.abs(player.input.leftImpulse) > 0.01f;
        if (acting) {
            idleTicks = 0;
            if (wanted) {
                stop();
            }
        } else {
            idleTicks++;
        }
        if (cooldown > 0) {
            cooldown--;
        }

        double radius = PalmConfig.CLIENT.cinematicHostileRadius.get();
        if (wanted) {
            if (player.tickCount % 5 == 0 && CinematicShots.hostileNear(mc.level, player, radius)) {
                stop();
                cooldown = 200;
                idleTicks = 0;
            }
        } else if (!overriding && cooldown <= 0
                && idleTicks >= PalmConfig.CLIENT.cinematicAfkSeconds.get() * 20
                && player.tickCount % 5 == 0
                && !CinematicShots.hostileNear(mc.level, player, radius)) {
            start(mc);
        }

        prevProgress = progress;
        float step = wanted ? 1f / ENTER_TICKS : -1f / EXIT_TICKS;
        progress = Mth.clamp(progress + step, 0f, 1f);
        if (!wanted && overriding && progress <= 0f && prevProgress <= 0f) {
            overriding = false;
            mc.options.setCameraType(previousCamera);
            shot = null;
            focusAmount = 0f;
            fovScale = 1f;
        }
    }

    private static float smoother(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    /** Called from the camera mixin every frame while the camera is detached. */
    public static PalmCameraController.Result compute(Camera camera, Entity entity, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (!overriding || entity != mc.player || mc.player == null || mc.level == null) {
            return null;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1f / 60f : Mth.clamp((now - lastNanos) / 1.0e9f, 0f, 0.1f);
        lastNanos = now;

        boolean snap = false;
        if (!wanted) {
            fadeState = 0;
            fadeAlpha = Math.max(0f, fadeAlpha - dt * 3f);
        } else if (fadeState == 0) {
            if (shot == null) {
                shot = CinematicShots.choose(mc.level, mc.player, RNG, PalmConfig.CLIENT.cinematicShotSeconds.get());
                shotTime = 0f;
                snap = true;
            } else if (shotTime >= shot.duration || !shot.valid(mc.level)) {
                fadeState = 1;                    // fade out
                fadeTime = 0f;
            }
        }
        if (wanted && fadeState == 1) {
            fadeTime += dt;
            fadeAlpha = smoother(fadeTime / FADE_SECONDS);
            if (fadeTime >= FADE_SECONDS) {
                shot = CinematicShots.choose(mc.level, mc.player, RNG, PalmConfig.CLIENT.cinematicShotSeconds.get());
                shotTime = 0f;
                snap = true;
                fadeState = 2;                    // fade in on the new shot
                fadeTime = 0f;
                fadeAlpha = 1f;
            }
        } else if (wanted && fadeState == 2) {
            fadeTime += dt;
            fadeAlpha = 1f - smoother(fadeTime / FADE_SECONDS);
            if (fadeTime >= FADE_SECONDS) {
                fadeState = 0;
                fadeAlpha = 0f;
            }
        }
        if (shot == null) {
            return null;
        }
        if (wanted && fadeState != 1) {
            shotTime += dt;
        }
        CinematicShots.Pose pose = shot.pose(mc.level, mc.player, shotTime, dt, pt);
        lastPose = pose;

        float k = snap ? 1f : 1f - (float) Math.exp(-dt * 2.0);
        fovScale += (pose.fovScale() - fovScale) * k;
        focusAmount += ((pose.focus() ? 1f : 0f) - focusAmount) * k;
        focusDistance = (float) Math.max(0.5, pose.pos().distanceTo(pose.aim()));

        float p = smoother(Mth.lerp(pt, prevProgress, progress));
        return PalmCameraController.blend(camera, entity, pt, p, startedFirstPerson, pose.pos(), pose.aim());
    }
}
