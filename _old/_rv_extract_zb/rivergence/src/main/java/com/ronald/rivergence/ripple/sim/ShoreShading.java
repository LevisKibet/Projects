package com.ronald.rivergence.ripple.sim;

/**
 * Colours for wet sand and foam on one pixel of a beach block. Pure Java so it can be previewed.
 *
 * Everything is blocky: each block is a step of wetness (the first block inland is darkest, then a
 * couple of semi dark blocks, with some blocks randomly wetter or drier), and the foam is made of
 * crisp pixels that wash up and back in a broken, random pattern.
 */
public final class ShoreShading {
    /** Number of per-pixel static values produced by {@link #fillStatics}. */
    public static final int STATICS = 6;

    private static final float[] STEP_ALPHA = {0F, 0.58F, 0.40F, 0.29F, 0.17F, 0.08F};

    private ShoreShading() {}

    /** Position-only random values (they never change over time), cached by the renderer. */
    public static void fillStatics(int blockX, int blockZ, double cellX, double cellZ, float[] out, int off) {
        out[off] = hash(blockX, blockZ, 11);                               // per-block wetness jitter
        out[off + 1] = hash(blockX, blockZ, 53);                           // per-block jitter for the semi dark reach
        out[off + 2] = noise(cellX * 0.13 + 101, cellZ * 0.13 + 53);       // wave timing along the shore
        out[off + 3] = 0.6F + noise(cellX * 0.22 + 40, cellZ * 0.22 + 7) * 1.5F; // how far this wave runs up
        out[off + 4] = hash((int) Math.floor(cellX * 8), (int) Math.floor(cellZ * 8), 97); // foam pixel
        out[off + 5] = hash((int) Math.floor(cellX * 8), (int) Math.floor(cellZ * 8), 31); // sand speckle
    }

    /**
     * @param step       whole blocks inland of the water (1 = the first block)
     * @param d          distance inland from the water's edge in blocks, for the foam
     * @param st         values from {@link #fillStatics}
     * @param seconds    time, for the waves washing in and out
     * @param brightness light level, so foam is dim at night
     * @return packed ARGB (alpha 0 when nothing should be drawn)
     */
    public static int color(int step, float d, float[] st, int off, float seconds, float brightness) {
        // Wet sand by whole blocks, with a bit of random give and take.
        int n = step;
        if (n > 1) {
            if (st[off] < 0.22F) n -= 1;           // this block dried out sooner
            else if (st[off] > 0.86F) n += 1;      // this one stayed wet a bit longer
            if (n < 2) n = 2;
        }
        float darkA = n < STEP_ALPHA.length ? STEP_ALPHA[n] : 0F;
        if (n >= 3 && n <= 4 && st[off + 1] > 0.93F) darkA = STEP_ALPHA[2] * 0.8F;   // an odd wet patch further up
        darkA *= 0.82F + 0.36F * st[off + 5];                                // sand speckle

        // A wave rushes up quickly and drains back slowly.
        float p = seconds * 0.85F + st[off + 2] * 6.2832F;
        float s = p / 6.2832F;
        s -= (float) Math.floor(s);
        float ease;
        if (s < 0.22F) {
            float k = 1F - s / 0.22F;
            ease = 1F - k * k;
        } else {
            float q = (s - 0.22F) / 0.78F;
            ease = (float) Math.pow(1F - q, 1.7F);
        }
        float front = 0.30F + st[off + 3] * ease;
        float u = front - d;                 // how far behind the front this pixel is

        // Foam: a band just behind the front plus flecks left behind, picked pixel by pixel.
        float band = (u > -0.05F && u < 0.50F) ? 1F : 0F;
        float flecks = (u >= 0.50F && u < 1.5F) ? 0.5F : 0F;
        float strength = Math.max(band, flecks);
        boolean foam = strength > 0F && st[off + 4] < 0.30F + 0.62F * strength * (band > 0F ? 1F : 0.6F);
        boolean sheet = u > 0.05F && u < 0.9F && st[off + 4] > 0.5F;   // thin water sheen

        float r = 0.10F, g = 0.07F, b = 0.04F, a = darkA;
        if (sheet) {
            float sa = 0.14F;
            float na = sa + a * (1F - sa);
            r = (0.40F * brightness * sa + r * a * (1F - sa)) / na;
            g = (0.58F * brightness * sa + g * a * (1F - sa)) / na;
            b = (0.68F * brightness * sa + b * a * (1F - sa)) / na;
            a = na;
        }
        if (foam) {
            float fa = band > 0F ? 0.95F : 0.7F;
            float na = fa + a * (1F - fa);
            r = (0.97F * brightness * fa + r * a * (1F - fa)) / na;
            g = (0.99F * brightness * fa + g * a * (1F - fa)) / na;
            b = (1.00F * brightness * fa + b * a * (1F - fa)) / na;
            a = na;
        }

        int ia = Math.min(255, (int) (a * 255F));
        return (ia << 24)
                | (Math.min(255, (int) (r * 255F)) << 16)
                | (Math.min(255, (int) (g * 255F)) << 8)
                | Math.min(255, (int) (b * 255F));
    }

    private static final float[] GLOSS_DARK = {0F, 0.26F, 0.17F, 0.10F, 0.05F, 0.02F};
    private static final float[] GLOSS_SHEEN = {0F, 1.0F, 0.75F, 0.5F, 0.3F, 0.15F};

    /**
     * Same layout for blocks that do not soak water up (stone and the like): instead of dark wet
     * sand they get a slight cool darkening, a shiny film in patches, and glints that twinkle.
     */
    public static int colorGlossy(int step, float d, float[] st, int off, float seconds, float brightness) {
        int n = Math.min(step, GLOSS_DARK.length - 1);
        if (n > 1) {
            if (st[off] < 0.22F) n -= 1;
            else if (st[off] > 0.86F) n += 1;
            n = Math.max(2, Math.min(n, GLOSS_DARK.length - 1));
        }
        float darkA = GLOSS_DARK[n] * (0.85F + 0.3F * st[off + 5]);
        float sheenK = GLOSS_SHEEN[n];

        // A film of water in patches (whole blocks are wetter than others), broken up by pixel.
        float patch = st[off + 1];
        float sheenA = (patch > 0.35F && st[off + 5] < 0.30F + 0.5F * patch) ? 0.30F * sheenK : 0F;
        // Glints that twinkle as the light plays on the water.
        float tw = 0.5F + 0.5F * (float) Math.sin(seconds * 3.1F + st[off + 4] * 60F);
        float glintA = (st[off + 4] > 0.86F) ? 0.80F * tw * sheenK : 0F;

        // Foam line, the same as on sand.
        float p = seconds * 0.85F + st[off + 2] * 6.2832F;
        float sw = p / 6.2832F;
        sw -= (float) Math.floor(sw);
        float ease;
        if (sw < 0.22F) {
            float k = 1F - sw / 0.22F;
            ease = 1F - k * k;
        } else {
            float q = (sw - 0.22F) / 0.78F;
            ease = (float) Math.pow(1F - q, 1.7F);
        }
        float front = 0.30F + st[off + 3] * ease;
        float u = front - d;
        float band = (u > -0.05F && u < 0.50F) ? 1F : 0F;
        float flecks = (u >= 0.50F && u < 1.5F) ? 0.5F : 0F;
        float strength = Math.max(band, flecks);
        boolean foam = strength > 0F && st[off + 4] < 0.30F + 0.62F * strength * (band > 0F ? 1F : 0.6F);

        float r = 0.05F, g = 0.09F, b = 0.14F, a = darkA;
        float[] layers = {sheenA, glintA, foam ? (band > 0F ? 0.95F : 0.7F) : 0F};
        float[][] cols = {{0.62F, 0.80F, 0.92F}, {1F, 1F, 1F}, {0.97F, 0.99F, 1F}};
        for (int i = 0; i < 3; i++) {
            float la = layers[i];
            if (la <= 0F) continue;
            float na = la + a * (1F - la);
            r = (cols[i][0] * brightness * la + r * a * (1F - la)) / na;
            g = (cols[i][1] * brightness * la + g * a * (1F - la)) / na;
            b = (cols[i][2] * brightness * la + b * a * (1F - la)) / na;
            a = na;
        }
        int ia = Math.min(255, (int) (a * 255F));
        return (ia << 24)
                | (Math.min(255, (int) (r * 255F)) << 16)
                | (Math.min(255, (int) (g * 255F)) << 8)
                | Math.min(255, (int) (b * 255F));
    }

    private static float hash(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 144665;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Smooth value noise in 0..1. */
    static float noise(double x, double z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        float fx = (float) (x - xi);
        float fz = (float) (z - zi);
        fx = fx * fx * (3F - 2F * fx);
        fz = fz * fz * (3F - 2F * fz);
        float a = hash(xi, zi, 0);
        float b = hash(xi + 1, zi, 0);
        float c = hash(xi, zi + 1, 0);
        float d = hash(xi + 1, zi + 1, 0);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }
}
