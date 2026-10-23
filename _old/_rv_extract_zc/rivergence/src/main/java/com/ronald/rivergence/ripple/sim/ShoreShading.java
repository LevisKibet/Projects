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
        // wave timing by whole blocks: a slow drift along the shore, each block a little ahead or behind
        out[off + 2] = noise(blockX * 0.11 + 101, blockZ * 0.11 + 53) * 0.85F + hash(blockX, blockZ, 71) * 0.15F;
        // which stretch of shore this block belongs to (each stretch gets its own waves), in uneven pieces
        int sx = Math.floorDiv(blockX + (int) (noise(blockX * 0.3, blockZ * 0.3 + 9) * 4), 5);
        int sz = Math.floorDiv(blockZ + (int) (noise(blockX * 0.3 + 17, blockZ * 0.3) * 4), 5);
        out[off + 3] = hash(sx, sz, 211);
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

        // The waves, by whole blocks: a foaming block at the front, wet blocks behind it, flecks as it drains.
        int w = wave(step, st, off, seconds);
        float band = w == 2 ? 1F : 0F;
        boolean foam = (w == 2 && st[off + 4] < 0.82F) || (w == 3 && st[off + 4] < 0.32F);
        boolean sheet = w == 1 && st[off + 4] > 0.45F;                 // thin water sheen

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

        // The foam, by whole blocks, the same as on sand.
        int w = wave(step, st, off, seconds);
        float band = w == 2 ? 1F : 0F;
        boolean foam = (w == 2 && st[off + 4] < 0.82F) || (w == 3 && st[off + 4] < 0.32F);

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

    /**
     * Where a wave stands on this block now, by whole blocks: 0 dry of it, 1 wet behind the front, 2 the foaming
     * front as it rushes up, 3 foam flecks left as it drains back. Each wave on a stretch of shore runs its own
     * distance (one to four blocks, now and then a lull), at its own pace.
     */
    static int wave(int step, float[] st, int off, float seconds) {
        float stretch = st[off + 3];
        float speed = 0.11F + 0.05F * stretch;                     // waves a second, a little different along the shore
        // the stretch keeps its own beat; each block along it lags a little behind or runs a little ahead
        float cyc = seconds * speed + stretch * 7.3F - st[off + 2] * 0.22F;
        int c = (int) Math.floor(cyc);
        float s = cyc - c;
        int seed = (int) (stretch * 65536);
        float hr = hash(seed, c, 5);
        int reach = 1 + (int) (hr * hr * 3.6F);
        if (hash(seed, c, 9) < 0.18F) reach = 0;                   // a lull: this one hardly reaches the sand
        float rush = 0.18F + 0.12F * hash(seed, c, 13);            // quick up, slow back
        float ease = s < rush ? 1F - (1F - s / rush) * (1F - s / rush) : (float) Math.pow(1F - (s - rush) / (1F - rush), 1.6F);
        int reached = (int) Math.ceil(reach * ease - 0.15F);
        if (reached <= 0 || step > reached) return 0;
        if (step == reached) return s < rush ? 2 : 3;
        return 1;
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
