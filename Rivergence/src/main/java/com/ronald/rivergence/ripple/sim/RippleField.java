package com.ronald.rivergence.ripple.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * A small patch of water surface simulated with a damped wave equation.
 * Pure Java (no Minecraft types) so it can be previewed and tested on its own.
 */
public final class RippleField {
    public static final int N = 96;
    public static final float CELL = 0.1f;          // blocks per grid point

    private static final float C2 = 0.36f;          // wave speed squared, in cells^2 per tick^2
        private static final float BASE_DAMP = 0.012f;
    private static final int SPONGE = 12;            // edge cells that absorb waves

    public final double originX, originZ, surfaceY;
    public final float[] h = new float[N * N];
    public final float[] prev = new float[N * N];
    private final float[] v = new float[N * N];
    private final float[] lap = new float[N * N];
    private final float[] damp = new float[N * N];
    /** 1 in the middle, fading to 0 at the patch edge so patches never end with a visible seam. */
    public final float[] fade = new float[N * N];
    public final boolean[] wet = new boolean[N * N];

    public int age;
    public int quietTicks;
    public float energy;
    private final List<Droplet> droplets = new ArrayList<>();

    private static final class Droplet {
        double x, z;
        float amp, sigma, period, tau;
        int t, duration;
    }

    public RippleField(double centerX, double centerZ, double surfaceY) {
        this.originX = centerX - N * CELL / 2.0;
        this.originZ = centerZ - N * CELL / 2.0;
        this.surfaceY = surfaceY;
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int idx = j * N + i;
                wet[idx] = true;
                int edge = Math.min(Math.min(i, N - 1 - i), Math.min(j, N - 1 - j));
                float sponge = edge >= SPONGE ? 0f : (float) Math.pow((SPONGE - edge) / (double) SPONGE, 2) * 0.35f;
                damp[idx] = BASE_DAMP + sponge;
                fade[idx] = Math.min(1f, edge / 10f);
            }
        }
    }

    public double worldX(int i) { return originX + i * CELL; }
    public double worldZ(int j) { return originZ + j * CELL; }

    /** True if the point is far enough from the edges that waves will not be swallowed at once. */
    public boolean isInterior(double x, double z, double marginBlocks) {
        double max = N * CELL;
        double lx = x - originX;
        double lz = z - originZ;
        return lx > marginBlocks && lz > marginBlocks && lx < max - marginBlocks && lz < max - marginBlocks;
    }

    /** One-off push to the surface velocity around a point. */
    public void impulse(double x, double z, float amount, float sigma) {
        gaussian(v, x, z, amount, sigma);
    }

    /**
     * A decaying oscillating source, like something dropping into the water:
     * it pushes the surface down and up several times, which sends out a train of rings.
     */
    public void droplet(double x, double z, float amp, float sigma, float periodTicks, float tauTicks, int duration) {
        Droplet d = new Droplet();
        d.x = x; d.z = z; d.amp = amp; d.sigma = sigma;
        d.period = periodTicks; d.tau = tauTicks; d.duration = duration;
        droplets.add(d);
        quietTicks = 0;
    }

    private void gaussian(float[] target, double x, double z, float amount, float sigma) {
        double fx = (x - originX) / CELL;
        double fz = (z - originZ) / CELL;
        float sc = sigma / CELL;
        int r = (int) Math.ceil(sc * 3);
        int ci = (int) Math.round(fx);
        int cj = (int) Math.round(fz);
        float inv = 1f / (2f * sc * sc);
        for (int j = Math.max(1, cj - r); j <= Math.min(N - 2, cj + r); j++) {
            for (int i = Math.max(1, ci - r); i <= Math.min(N - 2, ci + r); i++) {
                int idx = j * N + i;
                if (!wet[idx]) continue;
                double dx = i - fx, dz = j - fz;
                target[idx] += amount * (float) Math.exp(-(dx * dx + dz * dz) * inv);
            }
        }
        quietTicks = 0;
    }

    public void step() {
        age++;
        System.arraycopy(h, 0, prev, 0, h.length);

        for (int k = droplets.size() - 1; k >= 0; k--) {
            Droplet d = droplets.get(k);
            float f = d.amp * (float) (-Math.cos(2 * Math.PI * d.t / d.period) * Math.exp(-d.t / d.tau));
            gaussian(v, d.x, d.z, f, d.sigma);
            if (++d.t > d.duration) droplets.remove(k);
        }

        float maxA = 0;
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int idx = j * N + i;
                if (!wet[idx]) { lap[idx] = 0; continue; }
                float c = h[idx];
                float e = at(i + 1, j, c), w = at(i - 1, j, c), s = at(i, j + 1, c), n = at(i, j - 1, c);
                float se = at(i + 1, j + 1, c), sw = at(i - 1, j + 1, c), ne = at(i + 1, j - 1, c), nw = at(i - 1, j - 1, c);
                // Isotropic 9-point Laplacian so rings stay round.
                lap[idx] = (4F * (e + w + s + n) + (se + sw + ne + nw) - 20F * c) / 6F;
            }
        }
        for (int idx = 0; idx < N * N; idx++) {
            if (!wet[idx]) { h[idx] = 0; v[idx] = 0; continue; }
            float vel = (v[idx] + C2 * lap[idx]) * (1f - damp[idx]);
            v[idx] = vel;
            float nh = h[idx] + vel;
            h[idx] = nh;
            float a = Math.abs(nh);
            if (a > maxA) maxA = a;
        }
        energy = maxA;
        if (maxA < 0.0025f && droplets.isEmpty()) quietTicks++; else quietTicks = 0;
    }

    private float at(int i, int j, float fallback) {
        if (i < 0 || j < 0 || i >= N || j >= N) return fallback;
        int k = j * N + i;
        return wet[k] ? h[k] : fallback;
    }

    public boolean isDead() {
        return quietTicks > 8 && droplets.isEmpty();
    }

    /** Heights blended between the previous and current tick, for smooth rendering. */
    public void interpolate(float partial, float[] out) {
        for (int k = 0; k < out.length; k++) {
            out[k] = prev[k] + (h[k] - prev[k]) * partial;
        }
    }

    /** Marks which points lie over water of the right height. {@code wetAt} is queried per block column. */
    public void refreshWet(Terrain terrain) {
        int bx0 = (int) Math.floor(originX);
        int bz0 = (int) Math.floor(originZ);
        int bx1 = (int) Math.floor(originX + N * CELL);
        int bz1 = (int) Math.floor(originZ + N * CELL);
        int w = bx1 - bx0 + 1;
        int d = bz1 - bz0 + 1;
        boolean[] col = new boolean[w * d];
        for (int bz = 0; bz < d; bz++) {
            for (int bx = 0; bx < w; bx++) {
                col[bz * w + bx] = terrain.isWater(bx0 + bx, bz0 + bz, surfaceY);
            }
        }
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int bx = (int) Math.floor(worldX(i)) - bx0;
                int bz = (int) Math.floor(worldZ(j)) - bz0;
                wet[j * N + i] = col[bz * w + bx];
            }
        }
    }

    public interface Terrain {
        boolean isWater(int blockX, int blockZ, double surfaceY);
    }
}
