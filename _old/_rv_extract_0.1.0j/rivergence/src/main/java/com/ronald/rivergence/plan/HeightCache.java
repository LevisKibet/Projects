package com.ronald.rivergence.plan;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

/**
 * Land heights already sampled at cell centres, shared by all of a world's region plans: neighbouring regions plan
 * over wide overlapping margins, so most of a new region's heights are already known.
 */
public final class HeightCache {
    private static final int STRIPES = 32, MAX_PER_STRIPE = 120_000;
    private final Long2FloatOpenHashMap[] maps = new Long2FloatOpenHashMap[STRIPES];

    public HeightCache() {
        for (int i = 0; i < STRIPES; i++) { maps[i] = new Long2FloatOpenHashMap(); maps[i].defaultReturnValue(Float.NaN); }
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private Long2FloatOpenHashMap stripe(long k) { return maps[(int) ((k * 0x9E3779B97F4A7C15L) >>> 59)]; }

    public float get(int x, int z) {
        long k = key(x, z);
        Long2FloatOpenHashMap m = stripe(k);
        synchronized (m) { return m.get(k); }
    }

    public void put(int x, int z, float h) {
        long k = key(x, z);
        Long2FloatOpenHashMap m = stripe(k);
        synchronized (m) {
            if (m.size() > MAX_PER_STRIPE) m.clear();                 // a long journey: forget the oldest ground wholesale
            m.put(k, h);
        }
    }
}
