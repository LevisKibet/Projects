package com.ripplemod.sim;

/** Turns a wave height field into highlight/shadow colours, as if lit from one side. */
public final class RippleShading {
    // Direction the light comes from, flattened to the water plane.
    public static final float LX = 0.62F;
    public static final float LZ = 0.78F;

    private RippleShading() {}

    /** Slope of the surface along the light direction, per grid point. */
    public static void slopes(float[] heights, boolean[] wet, float[] fade, float[] out) {
        int n = RippleField.N;
        float inv = 1F / (2F * RippleField.CELL);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int idx = j * n + i;
                if (!wet[idx]) { out[idx] = 0; continue; }
                float c = heights[idx];
                float e = (i + 1 < n && wet[idx + 1]) ? heights[idx + 1] : c;
                float w = (i > 0 && wet[idx - 1]) ? heights[idx - 1] : c;
                float s = (j + 1 < n && wet[idx + n]) ? heights[idx + n] : c;
                float nn = (j > 0 && wet[idx - n]) ? heights[idx - n] : c;
                float gx = (e - w) * inv;
                float gz = (s - nn) * inv;
                out[idx] = (gx * LX + gz * LZ + 0.4F * (float) Math.sqrt(gx * gx + gz * gz)) * fade[idx];
            }
        }
    }

    /** Overlay colour for a slope value. Returns packed ARGB with alpha 0 on flat water. */
    public static int color(float slope, float brightness) {
        float r, g, b, a;
        if (slope > 0) {
            a = 0.85F * (1F - (float) Math.exp(-slope * 4.5F));
            r = 0.96F; g = 0.99F; b = 1.0F;
        } else {
            a = 0.6F * (1F - (float) Math.exp(slope * 3.2F));
            r = 0.03F; g = 0.10F; b = 0.20F;
        }
        r *= brightness; g *= brightness; b *= brightness;
        int ia = Math.min(255, (int) (a * 255F));
        return (ia << 24) | ((int) (r * 255F) << 16) | ((int) (g * 255F) << 8) | (int) (b * 255F);
    }
}
