package com.ronald.ancientpeaks.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Wraps the existing terrain (vanilla or Tectonic) and, inside Ancient Highlands regions,
 * blends it into the highlands terrain. Outside the regions the original terrain is untouched.
 */
public final class HighlandsDensity implements DensityFunction {
    public static final MapCodec<HighlandsDensity> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(d -> d.input),
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("continents").forGetter(d -> d.continents),
            DensityFunction.NoiseHolder.CODEC.fieldOf("seed_noise").forGetter(d -> d.seedNoise)
    ).apply(i, HighlandsDensity::new));
    public static final KeyDispatchDataCodec<HighlandsDensity> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final DensityFunction input, continents;
    private final DensityFunction.NoiseHolder seedNoise;
    private final IslandSampler sampler;
    private final ThreadLocal<ColumnCache> cache;

    /** Per-thread cache of 4 chunk-sized windows of columns: noise chunks visit blocks in cell order, so a single-entry cache would miss constantly. */
    private static final class ColumnCache {
        final long[] keys = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        final IslandSampler.Column[][] cols = new IslandSampler.Column[4][256];
        ColumnCache() { for (IslandSampler.Column[] w : cols) for (int i = 0; i < 256; i++) w[i] = new IslandSampler.Column(); }
    }

    public HighlandsDensity(DensityFunction input, DensityFunction continents, DensityFunction.NoiseHolder seedNoise) {
        this(input, continents, seedNoise, seedNoise.noise() == null ? null : IslandSampler.forSeed(seedFrom(seedNoise)), ThreadLocal.withInitial(ColumnCache::new));
    }

    private HighlandsDensity(DensityFunction input, DensityFunction continents, DensityFunction.NoiseHolder seedNoise, IslandSampler sampler, ThreadLocal<ColumnCache> cache) {
        this.input = input; this.continents = continents; this.seedNoise = seedNoise; this.sampler = sampler; this.cache = cache;
    }

    /** The world seed isn't visible to density functions, but a seeded noise is: derive a stable seed from it. */
    static long seedFrom(DensityFunction.NoiseHolder h) {
        long s = 1469598103934665603L;
        for (int i = 0; i < 4; i++) {
            s ^= Double.doubleToLongBits(h.getValue(i * 13.37 + 0.5, i * 7.1 + 0.25, i * 3.3 + 0.75));
            s *= 1099511628211L;
        }
        return s;
    }

    public IslandSampler sampler() { return sampler; }
    public DensityFunction continents() { return continents; }

    public IslandSampler.Column column(FunctionContext ctx) {
        ColumnCache cc = cache.get();
        int x = ctx.blockX(), z = ctx.blockZ();
        int cx = x >> 4, cz = z >> 4, slot = ((cx & 1) << 1) | (cz & 1);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        IslandSampler.Column[] w = cc.cols[slot];
        if (cc.keys[slot] != key) { cc.keys[slot] = key; for (IslandSampler.Column col : w) col.x = Integer.MIN_VALUE; }
        IslandSampler.Column c = w[((x & 15) << 4) | (z & 15)];
        if (c.x != x || c.z != z) sampler.fill(c, x, z);
        return c;
    }

    @Override
    public double compute(FunctionContext ctx) {
        if (sampler == null || !sampler.ready()) return input.compute(ctx);
        IslandSampler.Column c = column(ctx);
        if (c.m <= 0) return input.compute(ctx);
        double ours = sampler.density(c, ctx.blockX(), ctx.blockY(), ctx.blockZ());
        if (c.m >= 1) return ours;
        double orig = input.compute(ctx);
        return orig + (ours - orig) * c.m;
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        DensityFunction.NoiseHolder nn = visitor.visitNoise(seedNoise);
        if (sampler != null && nn.noise() == seedNoise.noise())   // same world: share the seeded maths and the column cache
            return visitor.apply(new HighlandsDensity(input.mapAll(visitor), continents.mapAll(visitor), nn, sampler, cache));
        return visitor.apply(new HighlandsDensity(input.mapAll(visitor), continents.mapAll(visitor), nn));
    }

    @Override public double minValue() { return Math.min(input.minValue(), -1.0); }
    @Override public double maxValue() { return Math.max(input.maxValue(), 1.0); }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
