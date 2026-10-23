package com.ronald.ancientpeaks.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A guarantee that the plains and meadows are never bare turf: after a chunk is decorated, if its grassland got almost
 * no plants (some mod packs strip or skip the biome's grass features), grass, tall grass, ferns and wildflowers are
 * sown over it here. Chunks that already have their grass are left alone.
 */
public final class PlainsBloom {
    private PlainsBloom() {}

    private static final Block[] FLOWERS = {Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET,
            Blocks.DANDELION, Blocks.ALLIUM, Blocks.WHITE_TULIP, Blocks.RED_TULIP};

    public static void run(WorldGenLevel level, ChunkPos cp) {
        IslandSampler s = IslandSampler.ACTIVE;
        if (s == null || s.islandAt(cp.getMiddleBlockX(), cp.getMiddleBlockZ()) == null) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int[] tops = new int[256];
        int open = 0, planted = 0;
        for (int i = 0; i < 256; i++) {
            int x = cp.getMinBlockX() + (i & 15), z = cp.getMinBlockZ() + (i >> 4);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
            tops[i] = Integer.MIN_VALUE;
            if (y <= 63) continue;
            BlockState st = level.getBlockState(m.set(x, y, z));
            if (st.is(Blocks.GRASS_BLOCK)) {
                if (!grassland(level, m.set(x, y + 1, z))) continue;
                open++; tops[i] = y;
            } else if (st.canBeReplaced() && st.getFluidState().isEmpty() && !st.isAir()
                    && level.getBlockState(m.set(x, y - 1, z)).is(Blocks.GRASS_BLOCK)) {
                open++; planted++;
            } else if (st.getBlock() instanceof DoublePlantBlock) { open++; planted++; }
        }
        if (open < 48 || planted >= open * 0.15) return;
        long seed = level.getSeed() ^ 0x51ED270B27C3A8E5L;
        for (int i = 0; i < 256; i++) {
            if (tops[i] == Integer.MIN_VALUE) continue;
            int x = cp.getMinBlockX() + (i & 15), z = cp.getMinBlockZ() + (i >> 4), y = tops[i] + 1;
            double h = hash(x, z, seed), patch = 0.5 + 0.5 * Math.sin(x * 0.11 + Math.cos(z * 0.07) * 3) * Math.cos(z * 0.09 + x * 0.03);
            BlockPos at = new BlockPos(x, y, z);
            if (!level.getBlockState(at).isAir()) continue;
            double grass = 0.18 + 0.4 * patch;
            if (h < grass) level.setBlock(at, Blocks.GRASS.defaultBlockState(), 2);
            else if (h < grass + 0.05 && level.getBlockState(at.above()).isAir()) {
                level.setBlock(at, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), 2);
                level.setBlock(at.above(), Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 2);
            } else if (h < grass + 0.08) level.setBlock(at, Blocks.FERN.defaultBlockState(), 2);
            else if (h < grass + 0.08 + 0.05 * patch) level.setBlock(at, FLOWERS[(int) (hash(z, x, seed) * FLOWERS.length) % FLOWERS.length].defaultBlockState(), 2);
        }
    }

    /** Open sky above a grass block, on the plains or meadow (not inside a building, field or path). */
    private static boolean grassland(WorldGenLevel level, BlockPos above) {
        if (!level.getBlockState(above).isAir()) return false;
        var b = level.getBiome(above);
        return b.is(StreamEnds.PLAINS) || b.unwrapKey().map(k -> k.location().getPath().equals("highland_meadow")).orElse(false);
    }

    private static double hash(int x, int z, long seed) {
        long v = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        v = (v ^ (v >>> 31)) * 0xBF58476D1CE4E5B9L; v ^= v >>> 29;
        return ((v >>> 11) & ((1L << 40) - 1)) / (double) (1L << 40);
    }
}
