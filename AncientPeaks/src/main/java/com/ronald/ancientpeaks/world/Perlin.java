package com.ronald.ancientpeaks.world;

import java.util.Random;

/** 2D gradient noise, identical in shape to the prototype used for the preview renders. */
public final class Perlin {
    private final int[] p = new int[512];
    private final double[] gx = new double[256], gz = new double[256];

    public Perlin(long seed) {
        Random r = new Random(seed);
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = perm[i]; perm[i] = perm[j]; perm[j] = t; }
        for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
        for (int i = 0; i < 256; i++) { double a = r.nextDouble() * Math.PI * 2; gx[i] = Math.cos(a); gz[i] = Math.sin(a); }
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    private double dot(int ix, int iz, double dx, double dz) {
        int g = p[p[ix & 255] + (iz & 255)] & 255;
        return gx[g] * dx + gz[g] * dz;
    }

    public double get(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double xf = x - xi, zf = z - zi, u = fade(xf), v = fade(zf);
        double n00 = dot(xi, zi, xf, zf), n10 = dot(xi + 1, zi, xf - 1, zf);
        double n01 = dot(xi, zi + 1, xf, zf - 1), n11 = dot(xi + 1, zi + 1, xf - 1, zf - 1);
        double a = n00 + u * (n10 - n00), b = n01 + u * (n11 - n01);
        return (a + v * (b - a)) * 1.41;
    }
}
