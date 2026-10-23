package com.ronald.rivergence.snow;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Runs after vanilla lays its single layer of snow: each column's snow settled to its depth, and through the trees. */
public final class DriftFeature extends Feature<NoneFeatureConfiguration> {
    public DriftFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        BlockPos o = ctx.origin();
        int x0 = o.getX() & ~15, z0 = o.getZ() & ~15;
        for (int i = 0; i < 16; i++) for (int k = 0; k < 16; k++) Snow.settle(level, x0 + i, z0 + k);
        return true;
    }
}
