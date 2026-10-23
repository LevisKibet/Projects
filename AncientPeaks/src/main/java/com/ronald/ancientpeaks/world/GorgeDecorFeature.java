package com.ronald.ancientpeaks.world;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Under the overhangs and natural bridges along the rivers: hanging glow berries, and here and there
 * an old lantern on a chain left by whoever lived here before.
 */
public class GorgeDecorFeature extends Feature<NoneFeatureConfiguration> {
    public GorgeDecorFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        return run(ctx.level(), new ChunkPos(ctx.origin()), ctx.random());
    }

    public static boolean run(WorldGenLevel level, ChunkPos cp, RandomSource rnd) {
        IslandSampler sampler = GorgeRiverFeature.sampler(level);
        if (sampler == null) return false;
        IslandSampler.Column col = new IslandSampler.Column();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                if (sampler.islandAt(x, z) == null) continue;
                sampler.fill(col, x, z);
                if (col.m <= 0.5 || !sampler.nearRiver(col, 45)) continue;
                int from = Math.max(sampler.waterTop(col) + 2, 60), to = (int) Math.min(col.h + 4, 318);
                for (int y = from; y < to; y++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) continue;
                    BlockState above = level.getBlockState(pos.above());
                    if (!above.isSolidRender(level, pos.above()) || above.is(Blocks.WATER)) continue;
                    // a ceiling: how much open air hangs below it?
                    int drop = 0;
                    while (drop < 12 && level.getBlockState(pos.below(drop + 1)).isAir()) drop++;
                    if (drop < 3) continue;
                    float roll = rnd.nextFloat();
                    if (roll < 0.022f && drop >= 7) {
                        int chain = 1 + rnd.nextInt(Math.min(4, drop - 4));
                        for (int i = 0; i < chain; i++) level.setBlock(pos.below(i), Blocks.CHAIN.defaultBlockState(), 2);
                        level.setBlock(pos.below(chain), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 2);
                        any = true;
                    } else if (roll < 0.30f) {
                        int len = 1 + rnd.nextInt(Math.min(6, drop - 1));
                        for (int i = 0; i < len; i++) {
                            boolean tip = i == len - 1;
                            BlockState vine = (tip ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT).defaultBlockState()
                                    .setValue(CaveVines.BERRIES, rnd.nextFloat() < 0.4f);
                            level.setBlock(pos.below(i), vine, 2);
                        }
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
