package com.ronald.rivergence.plan;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * A quick, close estimate of the land's surface height, for planning where water goes. Uses the world's own terrain
 * functions, so it follows whatever terrain the world has (vanilla, Terralith, Tectonic):
 * first the smooth early shape of the land (the part the game itself uses to judge heights early), which finds the
 * surface roughly; then the final terrain function, searched down from just above that, which pins it to the block
 * (searching from above, so caves under the surface never fool it).
 */
public final class FastHeights implements Drainage.Heights {
    private final DensityFunction rough, exact;
    private final int minY, maxY;
    /** Where the last search ended: neighbouring columns are close, so the next search starts there. */
    private int last = 80;

    public FastHeights(RandomState rs, int minY, int maxY) {
        this.rough = rs.router().initialDensityWithoutJaggedness();
        this.exact = rs.router().finalDensity();
        this.minY = minY; this.maxY = maxY;
    }

    @Override
    public double at(int x, int z) {
        double est = roughSurface(x, z);
        if (est <= minY) return minY;
        // from a little above the rough surface: make sure it's open sky there, then come down to the ground
        int y = (int) Math.min(maxY - 1, est + 12);
        for (int k = 0; k < 8 && y + 8 < maxY && d(exact, x, y, z) > 0; k++) y += 8;
        int step = 4;
        while (y > minY && d(exact, x, y - step, z) <= 0) y -= step;
        double lo = y - step, hi = y;                      // ground at lo, air at hi
        for (int i = 0; i < 2; i++) {
            double mid = (lo + hi) / 2;
            if (d(exact, x, (int) Math.round(mid), z) > 0) lo = mid; else hi = mid;
        }
        return Math.floor(lo);
    }

    /** The smooth early shape of the land: where it turns from air to ground. */
    double roughSurface(int x, int z) {
        int step = 8, y = Math.max(minY + 8, Math.min(maxY - 8, last));
        if (d(rough, x, y, z) > 0) {
            while (y + step < maxY && d(rough, x, y + step, z) > 0) y += step;
        } else {
            do { y -= step; } while (y > minY && d(rough, x, y, z) <= 0);
            if (y <= minY) { last = 80; return minY; }
        }
        last = y;
        return y + step / 2.0;
    }

    private static double d(DensityFunction f, int x, int y, int z) { return f.compute(new DensityFunction.SinglePointContext(x, y, z)); }
}
