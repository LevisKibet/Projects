package com.ronald.rivergence.world;

import com.mojang.serialization.MapCodec;
import com.ronald.rivergence.plan.Planner;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The world's terrain with the water's beds and banks cut into it: wherever the plan says the ground may stand no
 * higher than some height (a channel's bed, its banks rising away from the water), anything above is taken away.
 * It only ever removes ground, and only where a channel runs; everywhere else the terrain is untouched.
 */
public final class CarveDensity implements DensityFunction {
    /** Set while the game is only asking a column's height (not building a chunk): then a plan isn't waited for. */
    public static final ThreadLocal<Boolean> PEEK = ThreadLocal.withInitial(() -> false);

    /** Set while a chunk is choosing its structures (then the water plan is waited for; for /locate and maps it isn't). */
    public static final ThreadLocal<Boolean> STARTING = ThreadLocal.withInitial(() -> false);

    /** The planner of the chunk being set up on this thread (see NoiseChunkMixin). */
    public static final ThreadLocal<Planner> SETTING_UP = new ThreadLocal<>();

    private final DensityFunction input;
    private final Planner planner;
    private final ThreadLocal<Cache> cache;
    /** Only clear the water's own bed (applied after structures have shaped the ground), not the banks. */
    private final boolean bedOnly;

    /** Per thread: four chunk-sized windows of columns (chunks are filled cell by cell, so one window would thrash). */
    private static final class Cache {
        final long[] keys = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        final boolean[] peeked = new boolean[4];
        final Planner.Column[][] cols = new Planner.Column[4][256];
        final boolean[][] done = new boolean[4][256];
        Cache() { for (Planner.Column[] w : cols) for (int i = 0; i < 256; i++) w[i] = new Planner.Column(); }
    }

    public CarveDensity(DensityFunction input, Planner planner, boolean bedOnly) { this(input, planner, ThreadLocal.withInitial(Cache::new), bedOnly); }
    private CarveDensity(DensityFunction input, Planner planner, ThreadLocal<Cache> cache, boolean bedOnly) { this.input = input; this.planner = planner; this.cache = cache; this.bedOnly = bedOnly; }

    private Planner.Column column(int x, int z) {
        Cache c = cache.get();
        int cx = x >> 4, cz = z >> 4, slot = ((cx & 1) << 1) | (cz & 1);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        boolean peek = PEEK.get();
        // a window filled while peeking (plan perhaps not ready) is filled again for real chunk work
        if (c.keys[slot] != key || (c.peeked[slot] && !peek)) { c.keys[slot] = key; c.peeked[slot] = peek; java.util.Arrays.fill(c.done[slot], false); }
        int i = ((x & 15) << 4) | (z & 15);
        Planner.Column col = c.cols[slot][i];
        if (!c.done[slot][i]) { planner.column(x, z, col, !peek); c.done[slot][i] = true; }
        return col;
    }

    @Override
    public double compute(FunctionContext ctx) {
        Planner.Column c = column(ctx.blockX(), ctx.blockZ());
        if (bedOnly) {
            // between the bed and just above the water: open, whatever a structure built there
            int y = ctx.blockY();
            if (c.bed == Double.POSITIVE_INFINITY || y < c.bed + 0.5 || y > c.water + 2) return input.compute(ctx);
            return Math.min(input.compute(ctx), (c.bed + 0.5 - y) * 0.25);
        }
        int y = ctx.blockY();
        double v;
        double cut = c.carve == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : (c.carve + 0.5 - y) * 0.25;
        if (cut <= 0) v = cut;                                          // above the bed or bank: open either way
        else {
            v = input.compute(ctx);
            // a vanilla river valley filled up to the land round it (from a little below, so caves are left alone)
            if (c.fill != Double.NEGATIVE_INFINITY && y <= c.fill + 0.5 && y >= c.fill - 16) v = Math.max(v, (c.fill + 0.5 - y) * 0.25);
            v = Math.min(v, cut);
        }
        // a dam: solid from below the stream bed to its top
        if (c.wallTop != Integer.MIN_VALUE && y >= c.wallBase && y <= c.wallTop) return Math.max(v, 1);
        // built up a little (a spring's rim and hummocks), never more than a few blocks
        if (c.raise != Double.NEGATIVE_INFINITY && y <= c.raise + 0.5 && y >= c.raise - 3) v = Math.max(v, (c.raise + 0.5 - y) * 0.25);
        return v;
    }

    @Override public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
    @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(new CarveDensity(input.mapAll(visitor), planner, cache, bedOnly)); }
    @Override public double minValue() { return Math.min(input.minValue(), -100); }
    @Override public double maxValue() { return input.maxValue(); }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return KeyDispatchDataCodec.of(MapCodec.unit(this)); }
}
