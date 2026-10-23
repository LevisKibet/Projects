package com.ronald.rivergence.world;

import com.ronald.rivergence.plan.Planner;
import com.ronald.rivergence.plan.RegionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Dresses the springs where streams rise, as on the Ruins of Logres island: soft clay and mud under the dotted pools,
 * hummocks of moss, mud and rooted dirt between them (so it reads as many little springs welling up together), a few
 * old mud-brick edging stones on one side, azalea on the high side, dripleaf in the wet, moss carpet and ferns round about.
 */
final class SpringDecor {
    private SpringDecor() {}

    static void run(WorldGenLevel level, Planner.Column[] cols, BlockPos.MutableBlockPos m) {
        for (int a = 1; a <= 16; a++) for (int b = 1; b <= 16; b++) {
            Planner.Column c = cols[a + b * 18];
            if (c.spring == null || c.springQ >= 1.8) continue;
            column(level, m, c, c.spring, c.x, c.z);
        }
    }

    private static void column(WorldGenLevel level, BlockPos.MutableBlockPos m, Planner.Column c, RegionPlan.Spring sp, int x, int z) {
        int ground = sp.level + 4;
        while (ground > sp.level - 6) {
            BlockState st = level.getBlockState(m.set(x, ground, z));
            if (!st.isAir() && st.getFluidState().isEmpty() && !st.canBeReplaced()) break;
            ground--;
        }
        boolean water = level.getFluidState(m.set(x, ground + 1, z)).is(net.minecraft.tags.FluidTags.WATER);
        double q = c.springQ;
        double h = hash(x, z, sp.seed ^ 11), p = hash(z, x, sp.seed ^ 13);
        BlockState g = level.getBlockState(m.set(x, ground, z));
        if (!natural(g)) return;
        double ang = Math.atan2(z + 0.5 - sp.z, x + 0.5 - sp.x);
        double out = Math.cos(ang) * sp.dx + Math.sin(ang) * sp.dz;              // +1 toward the outlet, -1 the uphill side
        if (q < 1) {
            if (water) {                                                       // a wet hollow: soft clay and mud bed
                level.setBlock(m, (h < 0.42 ? Blocks.CLAY : h < 0.8 ? Blocks.MUD : Blocks.MOSS_BLOCK).defaultBlockState(), 2);
                if (p < 0.12 && level.getBlockState(m.set(x, ground + 2, z)).isAir()) smallDripleaf(level, m.set(x, ground + 1, z), x, z, true);
                return;
            }
            // a hummock between the pools
            level.setBlock(m, (h < 0.4 ? Blocks.MOSS_BLOCK : h < 0.65 ? Blocks.MUD : h < 0.85 ? Blocks.ROOTED_DIRT : Blocks.CLAY).defaultBlockState(), 2);
            if (!level.getBlockState(m.set(x, ground + 1, z)).isAir()) return;
            if (p < 0.4) put(level, m, Blocks.MOSS_CARPET.defaultBlockState());
            else if (p < 0.5 && (h < 0.4 || h >= 0.85)) smallDripleaf(level, m, x, z, false);
            return;
        }
        // the rim and the ground round the seep
        double side = Math.atan2(sp.dz, sp.dx);
        boolean edging = q < 1.35 && Math.abs(Math.sin((ang - side - 2.0) / 2)) < 0.28;   // one side: old edging
        if (edging && p < 0.55) {
            level.setBlock(m, Blocks.MUD.defaultBlockState(), 2);
            BlockState brick = h < 0.5 ? Blocks.MUD_BRICKS.defaultBlockState() : h < 0.8 ? Blocks.MUD_BRICK_SLAB.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            BlockState above = level.getBlockState(m.set(x, ground + 1, z));
            if (above.isAir() || (above.canBeReplaced() && above.getFluidState().isEmpty())) level.setBlock(m, brick, 2);
            return;
        }
        Block bl = h < 0.3 ? Blocks.MOSS_BLOCK : h < 0.45 ? Blocks.ROOTED_DIRT : h < 0.58 ? Blocks.COARSE_DIRT : h < 0.66 ? Blocks.MUD : null;
        if (bl != null) level.setBlock(m, bl.defaultBlockState(), 2);
        if (!level.getBlockState(m.set(x, ground + 1, z)).isAir()) return;
        if (out < -0.35 && p < 0.22) put(level, m, (p < 0.09 ? Blocks.FLOWERING_AZALEA : Blocks.AZALEA).defaultBlockState());   // the high side
        else if (p < 0.08) put(level, m, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        else if (p < 0.14 && (bl == Blocks.MOSS_BLOCK || bl == Blocks.MUD)) put(level, m, Blocks.BIG_DRIPLEAF.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, face(x, z)));
        else if (p < 0.40) put(level, m, Blocks.MOSS_CARPET.defaultBlockState());
        else if (p < 0.50) put(level, m, Blocks.FERN.defaultBlockState());
    }

    /** Ground the dressing may change: soil and stone, not anything built or placed. */
    private static boolean natural(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.STONE) || st.is(Blocks.GRAVEL) || st.is(Blocks.SAND)
                || st.is(Blocks.CLAY) || st.is(Blocks.MUD) || st.is(Blocks.MOSS_BLOCK) || st.is(Blocks.ANDESITE) || st.is(Blocks.DIORITE)
                || st.is(Blocks.GRANITE) || st.is(Blocks.TUFF) || st.is(Blocks.DEEPSLATE) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.COBBLESTONE);
    }

    private static void put(WorldGenLevel level, BlockPos.MutableBlockPos m, BlockState st) {
        if (st.canSurvive(level, m)) level.setBlock(m, st, 2);
    }

    private static void smallDripleaf(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, boolean wet) {
        BlockPos at = m.immutable();
        if (!level.getBlockState(at.above()).isAir()) return;
        BlockState lower = Blocks.SMALL_DRIPLEAF.defaultBlockState().setValue(SmallDripleafBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, face(x, z)).setValue(BlockStateProperties.WATERLOGGED, wet);
        BlockState upper = lower.setValue(SmallDripleafBlock.HALF, DoubleBlockHalf.UPPER).setValue(BlockStateProperties.WATERLOGGED, false);
        level.setBlock(at, lower, 2);
        level.setBlock(at.above(), upper, 2);
    }

    private static Direction face(int x, int z) { return Direction.from2DDataValue((int) Math.floorMod(x * 31L + z * 17L, 4L)); }

    private static double hash(int x, int z, long seed) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ seed;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }
}
