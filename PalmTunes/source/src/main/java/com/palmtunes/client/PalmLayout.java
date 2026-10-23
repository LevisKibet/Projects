package com.palmtunes.client;

/**
 * Geometry of the glass panel in "virtual pixels" (the panel is {@value #W} x {@value #H} of them).
 * Both the renderer and the mouse handling use this one description, so what you see is what you click.
 */
public final class PalmLayout {
    public static final float W = 260f;
    public static final float H = 196f;

    public enum Hit {
        NONE, PREVIOUS, PLAY_PAUSE, NEXT, SEEK, CLOSE, SHUFFLE, REPEAT
    }

    // Cover art
    public static final float ART_X = 12f;
    public static final float ART_Y = 12f;
    public static final float ART_SIZE = 72f;

    // Text column
    public static final float TEXT_X = 96f;
    public static final float TEXT_RIGHT = W - 12f;
    public static final float TITLE_Y = 17f;
    public static final float ARTIST_Y = 29f;
    public static final float ALBUM_Y = 38f;
    public static final float SOURCE_Y = 54f;

    // Progress bar
    public static final float BAR_X = 14f;
    public static final float BAR_W = W - 28f;
    public static final float BAR_Y = 98f;
    public static final float BAR_H = 3.5f;
    public static final float TIME_Y = 105f;

    // Transport buttons (centres and radii)
    public static final float BUTTON_Y = 125f;
    public static final float SIDE_R = 6.7f;
    public static final float SMALL_R = 8f;
    public static final float BIG_R = 10.7f;
    public static final float SHUFFLE_X = W / 2f - 76f;
    public static final float PREV_X = W / 2f - 38f;
    public static final float PLAY_X = W / 2f;
    public static final float NEXT_X = W / 2f + 38f;
    public static final float REPEAT_X = W / 2f + 76f;

    // Up-next list
    public static final float QUEUE_RULE_Y = 148f;
    public static final float QUEUE_LABEL_Y = 153f;
    public static final float QUEUE_ROW_Y = 164f;
    public static final float QUEUE_ROW_STEP = 9f;
    public static final int QUEUE_ROWS = 3;

    // Close "x"
    public static final float CLOSE_X = W - 14f;
    public static final float CLOSE_Y = 13f;
    public static final float CLOSE_R = 4.5f;

    private PalmLayout() {
    }

    public static Hit hitTest(float vx, float vy) {
        if (vx < 0 || vy < 0 || vx > W || vy > H) {
            return Hit.NONE;
        }
        if (inCircle(vx, vy, CLOSE_X, CLOSE_Y, CLOSE_R + 3f)) return Hit.CLOSE;
        if (inCircle(vx, vy, PLAY_X, BUTTON_Y, BIG_R + 2f)) return Hit.PLAY_PAUSE;
        if (inCircle(vx, vy, PREV_X, BUTTON_Y, SMALL_R + 2f)) return Hit.PREVIOUS;
        if (inCircle(vx, vy, NEXT_X, BUTTON_Y, SMALL_R + 2f)) return Hit.NEXT;
        if (inCircle(vx, vy, SHUFFLE_X, BUTTON_Y, SIDE_R + 4f)) return Hit.SHUFFLE;
        if (inCircle(vx, vy, REPEAT_X, BUTTON_Y, SIDE_R + 4f)) return Hit.REPEAT;
        // The bar is thin, so give it a generous vertical hit zone.
        if (vx >= BAR_X - 4f && vx <= BAR_X + BAR_W + 4f && vy >= BAR_Y - 7f && vy <= BAR_Y + BAR_H + 7f) {
            return Hit.SEEK;
        }
        return Hit.NONE;
    }

    /** 0..1 position along the progress bar for a virtual x coordinate. */
    public static float barFraction(float vx) {
        return Math.max(0f, Math.min(1f, (vx - BAR_X) / BAR_W));
    }

    private static boolean inCircle(float x, float y, float cx, float cy, float r) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    /** m:ss, or h:mm:ss for long tracks. */
    public static String formatTime(long ms) {
        if (ms < 0) {
            return "--:--";
        }
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
