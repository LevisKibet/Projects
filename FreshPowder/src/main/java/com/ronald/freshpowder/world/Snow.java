package com.ronald.freshpowder.world;

import com.ronald.freshpowder.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** The shared snow rules: how deep the snow lies at a spot, and snow sifting down through trees. */
public final class Snow {
    private Snow() {}

    public static double hash(int x, int y, int z, int s) {
        long h = x * 3129871L ^ z * 116129781L ^ y * 0x5DEECE66DL ^ s * 0x9E3779B97F4A7C15L;
        h = h * h * 42317861L + h * 11L;
        return ((h >> 16) & 0xFFFF) / 65535.0;
    }

    private static double lattice(int x, int z) { return hash(x, 0, z, 7); }

    /** Smooth value noise, 0..1. */
    public static double noise(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = lattice(x0, z0), b = lattice(x0 + 1, z0), c = lattice(x0, z0 + 1), d = lattice(x0 + 1, z0 + 1);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    /** The snow layer block of this column, or null if its top isn't snow. */
    public static BlockPos snowAt(LevelAccessor level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos p = new BlockPos(x, y, z);
        if (level.getBlockState(p).is(Blocks.SNOW)) return p;
        if (level.getBlockState(p.below()).is(Blocks.SNOW)) return p.below();
        return null;
    }

    /** How many layers the snow settles to here: deeper in broad hollows and against walls, thin on leaves. */
    public static int depth(LevelAccessor level, BlockPos pos) {
        int max = Config.MAX_DEPTH.get();
        BlockState under = level.getBlockState(pos.below());
        if (under.is(BlockTags.LEAVES)) return Math.min(max, 1 + (hash(pos.getX(), pos.getY(), pos.getZ(), 3) < 0.4 ? 1 : 0));
        double n = 0.65 * noise(pos.getX() / 14.0, pos.getZ() / 14.0) + 0.35 * noise(pos.getX() / 5.0, pos.getZ() / 5.0);
        int d = 1 + (int) Math.floor(n * (max - 0.6));
        int walls = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos np = pos.relative(dir);
            BlockState s = level.getBlockState(np);
            if (!s.isAir() && !s.is(Blocks.SNOW) && s.isFaceSturdy(level, np, dir.getOpposite())) walls++;
        }
        d += walls >= 2 ? 2 : walls;
        return Math.max(1, Math.min(max, d));
    }

    private static boolean cold(LevelAccessor level, BlockPos pos) {
        return level.getBiome(pos).value().coldEnoughToSnow(pos);
    }

    /** At world generation: the column's snow brought to its depth, and some dusting through the tree below. */
    public static void settle(LevelAccessor level, int x, int z) {
        BlockPos p = snowAt(level, x, z);
        if (p == null || !cold(level, p)) return;
        if (Config.DRIFTS.get()) {
            BlockState s = level.getBlockState(p);
            int want = depth(level, p);
            if (s.getValue(SnowLayerBlock.LAYERS) < want) level.setBlock(p, s.setValue(SnowLayerBlock.LAYERS, want), 2);
        }
        if (Config.TREE_SNOW.get()) sift(level, p, null);
    }

    /** During snowfall at a random spot: the drift grows a layer toward its depth, and snow sifts through trees. */
    public static void fall(LevelAccessor level, BlockPos top, RandomSource r) {
        BlockPos p = snowAt(level, top.getX(), top.getZ());
        if (p == null || !cold(level, p)) return;
        if (Config.DRIFTS.get() && r.nextInt(2) == 0) {
            BlockState s = level.getBlockState(p);
            int n = s.getValue(SnowLayerBlock.LAYERS);
            if (n < depth(level, p)) level.setBlock(p, s.setValue(SnowLayerBlock.LAYERS, n + 1), 3);
        }
        if (Config.TREE_SNOW.get() && r.nextInt(3) == 0) sift(level, p, r);
    }

    /**
     * Down through the tree under the top snow: on each bough of leaves with air over it (and on the ground under the
     * tree), now and then a layer of snow. With a random source (snowfall) it adds to one spot; without (world
     * generation) it dusts the whole column by a fixed pattern.
     */
    private static void sift(LevelAccessor level, BlockPos snow, RandomSource r) {
        BlockPos.MutableBlockPos m = snow.below().mutable();
        boolean canopy = false;
        for (int i = 0; i < 28; i++) {
            BlockState s = level.getBlockState(m);
            if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) {
                canopy |= s.is(BlockTags.LEAVES);
                m.move(Direction.DOWN);
                continue;
            }
            if (!s.isAir() && !s.is(Blocks.SNOW)) return;               // the ground (or anything else): done
            BlockPos below = m.below();
            BlockState b = level.getBlockState(below);
            if (canopy && !b.isAir() && !b.is(Blocks.SNOW)) {
                boolean bough = b.is(BlockTags.LEAVES);
                if (r == null) {
                    if (s.isAir() && hash(m.getX(), m.getY(), m.getZ(), 11) < (bough ? 0.6 : 0.35) && Blocks.SNOW.defaultBlockState().canSurvive(level, m))
                        level.setBlock(m, Blocks.SNOW.defaultBlockState(), 2);
                } else if (r.nextInt(3) == 0) {
                    if (s.isAir()) {
                        if (Blocks.SNOW.defaultBlockState().canSurvive(level, m)) level.setBlock(m, Blocks.SNOW.defaultBlockState(), 3);
                    } else {
                        int n = s.getValue(SnowLayerBlock.LAYERS);
                        if (n < (bough ? 2 : 2)) level.setBlock(m, s.setValue(SnowLayerBlock.LAYERS, n + 1), 3);
                    }
                    return;
                }
            }
            m.move(Direction.DOWN);
        }
    }
}
