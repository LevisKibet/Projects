package com.palmtunes.client;

import com.palmtunes.media.MediaCommand;
import com.palmtunes.media.MediaManager;
import com.palmtunes.media.MediaSnapshot;
import com.palmtunes.media.PlaybackState;
import net.minecraft.Util;

/**
 * Bridges the 3D panel and the 2D mouse. The renderer publishes where the panel landed on screen;
 * the screen turns mouse coordinates back into panel coordinates and runs commands.
 * Client thread only.
 */
public final class PalmInteraction {
    // Where the panel was drawn last frame, in GUI-scaled pixels.
    private static boolean rectValid;
    // Screen -> unit square, as a projective (homography) matrix, row-major.
    private static float m00, m01, m02, m10, m11, m12, m20, m21, m22;

    private static float mouseX = -1f;
    private static float mouseY = -1f;

    private static PalmLayout.Hit hover = PalmLayout.Hit.NONE;
    private static PalmLayout.Hit flash = PalmLayout.Hit.NONE;
    private static long flashUntilMs;

    private static boolean scrubbing;
    private static float scrubFraction;

    private static Boolean optimisticPlaying;
    private static long optimisticUntilMs;

    private PalmInteraction() {
    }

    // ---- published by the renderer --------------------------------------------------------

    /** The panel's corners on screen: top-left, top-right, bottom-right, bottom-left, in GUI pixels. */
    static void publishQuad(float[][] p) {
        float x0 = p[0][0], y0 = p[0][1], x1 = p[1][0], y1 = p[1][1];
        float x2 = p[2][0], y2 = p[2][1], x3 = p[3][0], y3 = p[3][1];
        float dx1 = x1 - x2, dx2 = x3 - x2, sx = x0 - x1 + x2 - x3;
        float dy1 = y1 - y2, dy2 = y3 - y2, sy = y0 - y1 + y2 - y3;
        float den = dx1 * dy2 - dx2 * dy1;
        if (Math.abs(den) < 1.0e-6f) {
            invalidateRect();
            return;
        }
        float g = (sx * dy2 - dx2 * sy) / den;
        float h = (dx1 * sy - sx * dy1) / den;
        float a = x1 - x0 + g * x1, b = x3 - x0 + h * x3, c = x0;
        float d = y1 - y0 + g * y1, e = y3 - y0 + h * y3, f = y0;
        // inverse of [[a b c][d e f][g h 1]] (adjugate; the scale cancels when dividing)
        m00 = e - f * h;  m01 = c * h - b;  m02 = b * f - c * e;
        m10 = f * g - d;  m11 = a - c * g;  m12 = c * d - a * f;
        m20 = d * h - e * g; m21 = b * g - a * h; m22 = a * e - b * d;
        rectValid = true;
        updateHover();
    }

    static void invalidateRect() {
        rectValid = false;
        hover = PalmLayout.Hit.NONE;
    }

    // ---- mouse ----------------------------------------------------------------------------

    static void mouseMoved(double mx, double my) {
        mouseX = (float) mx;
        mouseY = (float) my;
        updateHover();
    }

    private static void updateHover() {
        if (!rectValid || mouseX < 0) {
            hover = PalmLayout.Hit.NONE;
            return;
        }
        hover = PalmLayout.hitTest(toVirtualX(mouseX, mouseY), toVirtualY(mouseX, mouseY));
    }

    private static float unitU(float mx, float my) {
        float w = m20 * mx + m21 * my + m22;
        return (m00 * mx + m01 * my + m02) / w;
    }

    private static float unitV(float mx, float my) {
        float w = m20 * mx + m21 * my + m22;
        return (m10 * mx + m11 * my + m12) / w;
    }

    private static float toVirtualX(float mx, float my) {
        return unitU(mx, my) * PalmLayout.W;
    }

    private static float toVirtualY(float mx, float my) {
        return unitV(mx, my) * PalmLayout.H;
    }

    static boolean mouseDown(double mx, double my, int button) {
        if (button != 0 || !rectValid) {
            return false;
        }
        float vx = toVirtualX((float) mx, (float) my);
        float vy = toVirtualY((float) mx, (float) my);
        PalmLayout.Hit hit = PalmLayout.hitTest(vx, vy);
        switch (hit) {
            case PREVIOUS -> run(PalmLayout.Hit.PREVIOUS, MediaCommand.PREVIOUS);
            case NEXT -> run(PalmLayout.Hit.NEXT, MediaCommand.NEXT);
            case SHUFFLE -> run(PalmLayout.Hit.SHUFFLE, MediaCommand.TOGGLE_SHUFFLE);
            case REPEAT -> run(PalmLayout.Hit.REPEAT, MediaCommand.CYCLE_REPEAT);
            case PLAY_PAUSE -> {
                togglePlayPause();
                flash(PalmLayout.Hit.PLAY_PAUSE);
            }
            case SEEK -> {
                if (MediaManager.get().snapshot().info() != null && MediaManager.get().snapshot().info().hasKnownDuration()) {
                    scrubbing = true;
                    scrubFraction = PalmLayout.barFraction(vx);
                }
            }
            case CLOSE -> PalmController.close();
            default -> {
                return false;
            }
        }
        return true;
    }

    static boolean mouseDrag(double mx, double my) {
        if (!scrubbing || !rectValid) {
            return false;
        }
        scrubFraction = PalmLayout.barFraction(toVirtualX((float) mx, (float) my));
        return true;
    }

    static boolean mouseUp() {
        if (!scrubbing) {
            return false;
        }
        scrubbing = false;
        MediaSnapshot snap = MediaManager.get().snapshot();
        if (snap.info() != null && snap.info().hasKnownDuration()) {
            MediaManager.get().send(MediaCommand.SEEK, Math.round(scrubFraction * snap.info().durationMs()));
        }
        return true;
    }

    // ---- commands -------------------------------------------------------------------------

    static void run(PalmLayout.Hit button, MediaCommand command) {
        flash(button);
        MediaManager.get().send(command, 0);
    }

    static void togglePlayPause() {
        MediaSnapshot snap = MediaManager.get().snapshot();
        boolean playingNow = isPlayingForDisplay(snap);
        optimisticPlaying = !playingNow;
        optimisticUntilMs = Util.getMillis() + 1500;
        MediaManager.get().send(MediaCommand.PLAY_PAUSE, 0);
    }

    private static void flash(PalmLayout.Hit button) {
        flash = button;
        flashUntilMs = Util.getMillis() + 180;
    }

    // ---- read by the renderer -------------------------------------------------------------

    static PalmLayout.Hit hover() {
        return hover;
    }

    static boolean isFlashing(PalmLayout.Hit button) {
        return flash == button && Util.getMillis() < flashUntilMs;
    }

    static boolean isScrubbing() {
        return scrubbing;
    }

    static float scrubFraction() {
        return scrubFraction;
    }

    /** What the play/pause button should show: reality, except right after a click. */
    static boolean isPlayingForDisplay(MediaSnapshot snap) {
        if (optimisticPlaying != null && Util.getMillis() < optimisticUntilMs) {
            return optimisticPlaying;
        }
        optimisticPlaying = null;
        return snap.info() != null && snap.info().state() == PlaybackState.PLAYING;
    }

    static void reset() {
        rectValid = false;
        hover = PalmLayout.Hit.NONE;
        scrubbing = false;
        optimisticPlaying = null;
        mouseX = -1f;
        mouseY = -1f;
    }
}
