package com.ronald.ancientpeaks.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Wraps the aquifer's floodedness: across an island's whole footprint everything below sea level is flooded
 * with the sea, so the ocean always meets the island (no dry seabed, no walls of water, flooded sea tunnel).
 */
public final class IslandFlood implements DensityFunction {
    public static final MapCodec<IslandFlood> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(d -> d.input),
            DensityFunction.NoiseHolder.CODEC.fieldOf("seed_noise").forGetter(d -> d.seedNoise)
    ).apply(i, IslandFlood::new));
    public static final KeyDispatchDataCodec<IslandFlood> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final DensityFunction input;
    private final DensityFunction.NoiseHolder seedNoise;
    private final IslandSampler sampler;

    public IslandFlood(DensityFunction input, DensityFunction.NoiseHolder seedNoise) {
        this.input = input; this.seedNoise = seedNoise;
        this.sampler = seedNoise.noise() == null ? null : IslandSampler.forSeed(HighlandsDensity.seedFrom(seedNoise));
    }

    @Override
    public double compute(FunctionContext ctx) {
        if (sampler != null && sampler.ready() && sampler.islandAt(ctx.blockX(), ctx.blockZ()) != null) {
            // below sea level: the sea. Above it: never an aquifer pocket, so no stray water hangs in the sea tunnel
            return ctx.blockY() < 63 ? 1.0 : -1.0;
        }
        return input.compute(ctx);
    }

    @Override public void fillArray(double[] values, ContextProvider provider) { provider.fillAllDirectly(values, this); }
    @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(new IslandFlood(input.mapAll(visitor), visitor.visitNoise(seedNoise))); }
    @Override public double minValue() { return Math.min(input.minValue(), -1.0); }
    @Override public double maxValue() { return Math.max(input.maxValue(), 1.0); }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
