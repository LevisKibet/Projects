package com.palmtunes.client;

import com.palmtunes.PalmConfig;
import com.palmtunes.media.MediaManager;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Client-thread state machine behind the palm panel: is it open, how far along is the
 * open/close animation, and which camera mode to hand back afterwards.
 */
public final class PalmController {
    private static boolean wanted;
    private static float progress;
    private static float prevProgress;
    private static CameraType previousCamera = CameraType.FIRST_PERSON;
    private static boolean cameraOverridden;

    private PalmController() {
    }

    /** True while the panel is open or still animating shut. */
    public static boolean isVisible() {
        return progress > 0.001f || prevProgress > 0.001f;
    }

    public static boolean isOpen() {
        return wanted;
    }

    /** True from the moment the panel opens until the camera has been handed back completely. */
    public static boolean isOverriding() {
        return cameraOverridden;
    }

    /** True if the player was in first person when the panel opened (the camera then flies out of the eyes). */
    public static boolean startedFirstPerson() {
        return previousCamera.isFirstPerson();
    }

    /** Raw linear 0..1 progress for the given partial tick. */
    public static float linear(float partialTick) {
        return Mth.clamp(Mth.lerp(partialTick, prevProgress, progress), 0f, 1f);
    }

    /** Smootherstep (zero velocity and acceleration at both ends) of the whole animation. */
    public static float eased(float partialTick) {
        return smoother(linear(partialTick));
    }

    /** Eased value of the sub-range [from, to] of the animation, so parts of it can lead or trail. */
    public static float eased(float partialTick, float from, float to) {
        return smoother((linear(partialTick) - from) / (to - from));
    }

    private static float smoother(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    public static void toggle() {
        if (wanted) {
            close();
        } else {
            open();
        }
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || wanted) {
            return;
        }
        Cinematic.stopImmediately();
        wanted = true;
        if (!cameraOverridden) {
            previousCamera = mc.options.getCameraType();
            cameraOverridden = true;
        }
        if (mc.options.getCameraType().isFirstPerson()) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        MediaManager.get().setActive(true);
        mc.setScreen(new PalmScreen());
    }

    public static void close() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PalmScreen) {
            mc.setScreen(null); // triggers onScreenClosed()
        } else {
            onScreenClosed();
        }
    }

    /** Called by the screen when it goes away for any reason (ESC, key, death, disconnect...). */
    static void onScreenClosed() {
        if (!wanted) {
            return;
        }
        wanted = false;
        MediaManager.get().setActive(false);
    }

    /** Called once per client tick. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        prevProgress = progress;
        float step = 1f / Math.max(1, PalmConfig.CLIENT.transitionTicks.get());
        progress = Mth.clamp(progress + (wanted ? step : -step), 0f, 1f);

        if (!wanted && progress <= 0f && prevProgress <= 0f && cameraOverridden) {
            mc.options.setCameraType(previousCamera);
            cameraOverridden = false;
        }
    }

    static void reset() {
        if (wanted) {
            MediaManager.get().setActive(false);
        }
        wanted = false;
        progress = 0f;
        prevProgress = 0f;
        cameraOverridden = false;
    }
}
