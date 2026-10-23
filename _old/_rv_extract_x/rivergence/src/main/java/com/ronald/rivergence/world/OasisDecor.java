package com.ronald.rivergence.world;

import com.ronald.rivergence.plan.Planner;
import com.ronald.rivergence.plan.RegionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * The green ring round an oasis: grass taking over from the sand close to the water and thinning out away from it,
 * reeds of sugar cane at the waterline, tall grass and ferns, and a few palms leaning out over the pool.
 */
final class OasisDecor {
    private OasisDecor() {}

    static void ring(WorldGenLevel level, Planner.Column c, int x, int z, RandomSource r, BlockPos.MutableBlockPos m) {
        RegionPlan.Oasis o = c.oasis;
        double q = c.oasisQ;
        if (q < 1 || q > 2.4) return;
        int y = o.level + 8;
        BlockState g = null;
        for (; y >= o.level - 1; y--) {
            g = level.getBlockState(m.set(x, y, z));
            if (!g.isAir() && !g.canBeReplaced()) break;
        }
        if (y < o.level - 1 || g == null || !g.getFluidState().isEmpty()) return;
        if (!(g.is(BlockTags.SAND) || g.is(Blocks.SANDSTONE) || g.is(BlockTags.TERRACOTTA) || g.is(BlockTags.DIRT) || g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.GRAVEL))) return;
        BlockPos ground = new BlockPos(x, y, z), at = ground.above();
        double green = (Planner.valueNoise(x / 3.5, z / 3.5, 101) + 1) / 2 - (q - 1) * 0.55;   // greener near the water
        boolean grass = green > 0.12;
        if (grass) level.setBlock(ground, (green > 0.2 || r.nextFloat() < 0.5 ? Blocks.GRASS_BLOCK : Blocks.COARSE_DIRT).defaultBlockState(), 2);
        else if (q < 1.8 && green > -0.05) level.setBlock(ground, Blocks.COARSE_DIRT.defaultBlockState(), 2);
        if (!level.getBlockState(at).isAir()) return;
        boolean wetSide = false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(ground.relative(d)).is(FluidTags.WATER)) wetSide = true;
        float f = r.nextFloat();
        if (q > 1.12 && q < 1.9 && f < 0.022 && grass) { palm(level, at, o, x, z, r); return; }
        if (wetSide && f < 0.45) { cane(level, at, r); return; }
        if (!grass) { if (f < 0.03) level.setBlock(at, Blocks.DEAD_BUSH.defaultBlockState(), 2); return; }
        if (f < 0.32) {
            Block b = f < 0.08 ? Blocks.LARGE_FERN : Blocks.TALL_GRASS;
            level.setBlock(at, b.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), 2);
            if (level.getBlockState(at.above()).isAir()) level.setBlock(at.above(), b.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 2);
            else level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
        } else if (f < 0.5) level.setBlock(at, (f < 0.4 ? Blocks.GRASS : Blocks.FERN).defaultBlockState(), 2);
        else if (f < 0.53) level.setBlock(at, Blocks.SWEET_BERRY_BUSH.defaultBlockState(), 2);
    }

    private static void cane(WorldGenLevel level, BlockPos at, RandomSource r) {
        if (!Blocks.SUGAR_CANE.defaultBlockState().canSurvive(level, at)) return;
        int h = 2 + r.nextInt(3);
        for (int i = 0; i < h && level.getBlockState(at.above(i)).isAir(); i++) level.setBlock(at.above(i), Blocks.SUGAR_CANE.defaultBlockState(), 2);
    }

    /** A palm: a slim trunk leaning out toward the water, a crown of drooping fronds, cocoa pods under the crown. */
    private static void palm(WorldGenLevel level, BlockPos base, RegionPlan.Oasis o, int x, int z, RandomSource r) {
        double lx = o.x - (x + 0.5), lz = o.z - (z + 0.5), ll = Math.max(1e-6, Math.hypot(lx, lz));
        int sx = (int) Math.round(lx / ll), sz = (int) Math.round(lz / ll);
        int h = 6 + r.nextInt(4);
        double lean = 1.2 + r.nextDouble() * 1.6;
        for (int i = 0; i <= h + 2; i++)                                         // room to grow?
            if (!level.getBlockState(base.offset((int) Math.round(sx * lean * i / h), i, (int) Math.round(sz * lean * i / h))).isAir()) return;
        BlockState log = Blocks.JUNGLE_LOG.defaultBlockState();
        BlockPos top = base;
        for (int i = 0; i < h; i++) {
            double t = i / (double) h;
            top = base.offset((int) Math.round(sx * lean * t * t), i, (int) Math.round(sz * lean * t * t));
            level.setBlock(top, log, 2);
        }
        BlockState leaf = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockPos crown = top.above();
        level.setBlock(crown, leaf, 2);
        // eight fronds, out and drooping
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : dirs) {
            int len = (d[0] != 0 && d[1] != 0) ? 2 + r.nextInt(2) : 3 + r.nextInt(2);
            for (int s = 1; s <= len; s++) {
                BlockPos p = crown.offset(d[0] * s, -(s * s) / 5, d[1] * s);
                if (level.getBlockState(p).isAir()) level.setBlock(p, leaf, 2);
            }
        }
        // cocoa pods on the trunk just under the crown
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (r.nextFloat() > 0.35) continue;
            BlockPos p = top.relative(dir);
            if (!level.getBlockState(p).isAir()) continue;
            level.setBlock(p, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, dir.getOpposite()).setValue(CocoaBlock.AGE, r.nextInt(3)), 2);
        }
    }
}
