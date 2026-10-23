package com.ronald.ancientpeaks.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Dresses the island's streams once their water is in (see Island.buildStreams for their shapes), in rings out from
 * the water:
 *   bed       - clay, mud, gravel, moss and sand under the water; seagrass where it's slow, lily pads where it's calm;
 *   waterline - mud, rooted dirt, coarse dirt, moss, clay and mossy stones where the water meets the land;
 *   inner bank - ferns, tall grass, moss carpet, azalea, dripleaf;
 *   outer bank - grass, mossy boulders, the odd fallen log and drifts of fallen leaves.
 * Springs get their own dressing (dotted pools between hummocks of mud and moss, a few old mud-brick edging stones,
 * azalea on the high side, dripleaf in the wet); falls get mossy, broken rock with vines and hanging leaves round
 * them and a gravel-and-clay plunge pool. Bank width grows with the stream. Cold, high ground keeps to bare stone.
 * Only natural ground is touched, and only on the island.
 */
public final class StreamDecor {
    private StreamDecor() {}

    public static void run(WorldGenLevel level, ChunkPos cp) {
        IslandSampler s = GorgeRiverFeature.sampler(level);
        if (s == null) return;
        Island is = s.islandAt(cp.getMiddleBlockX(), cp.getMiddleBlockZ());
        if (is == null || is.rivers.isEmpty()) return;
        long seed = level.getSeed() ^ 0x57AEA11L;
        List<double[]> falls = is.fallsWorld(), springs = is.springsWorld();
        IslandSampler.Column c = new IslandSampler.Column();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
            s.fill(c, x, z);
            if (c.is == null || c.m <= 0.5) continue;
            // the nearest stream: how far past its water's edge this column is, and how big it is there
            double edge = 1e9, w = 0;
            for (int i = 0; i < s.streamCount(c); i++) {
                if (!s.isStream(c, i)) continue;
                double e = s.streamDist(c, i) - s.streamWidth(c, i);
                if (e < edge) { edge = e; w = s.streamWidth(c, i); }
            }
            boolean pond = s.pondOf(c) >= 0 && s.pondQ(c) < 2.2;
            double pondEdge = pond ? (s.pondQ(c) - 1) * is.ponds.get(s.pondOf(c))[2] : 1e9;
            boolean spring = s.springOf(c) >= 0 && s.springQ(c) < 1.8;
            boolean pool = s.poolOf(c) >= 0 && s.poolQ(c) < 1.8;
            if (pond && pondEdge < edge) { edge = pondEdge; w = Math.max(w, 2.5); }
            if (edge > 2 + 2.6 * (1 + 0.7 * w) && !spring && !pool && !nearFall(falls, x, z)) continue;

            IslandSampler.Zone zone = s.zone(c, (int) c.h);
            boolean cold = zone == IslandSampler.Zone.PEAKS || c.h > 205;
            boolean forest = zone == IslandSampler.Zone.FOREST;

            // what's here: water surface (if any) and the solid ground under it
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            int water = Integer.MIN_VALUE, ground = Integer.MIN_VALUE;
            for (int y = top; y > top - 24; y--) {
                BlockState st = level.getBlockState(m.set(x, y, z));
                if (st.isAir() || st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS)) continue;
                if (st.getFluidState().is(FluidTags.WATER)) { if (water == Integer.MIN_VALUE) water = y; continue; }
                if (st.canBeReplaced() || !st.blocksMotion()) continue;
                ground = y; break;
            }
            if (ground == Integer.MIN_VALUE) continue;
            // a column under a tree: only its floor matters (we went down through the crown)

            if (spring) springColumn(level, m, s, c, is, x, z, ground, water, seed);
            else if (water != Integer.MIN_VALUE) { if (water > 63) bedColumn(level, m, x, z, ground, water, w, pond, pool, cold, seed); }
            else if (s.poolOf(c) >= 0 && s.poolQ(c) >= 0.95 && s.poolQ(c) < 1.45) poolRim(level, m, x, z, ground, seed);
            else bankColumn(level, m, x, z, ground, edge, w, cold, forest, seed);
        }
        for (double[] f : falls) fallDressing(level, cp, m, f, seed);
    }

    /** The rim round a plunge pool: mossy cobble, cobble and stone, with a little moss, two blocks deep. */
    private static void poolRim(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, int ground, long seed) {
        for (int k = 0; k < 2; k++) {
            BlockState g = level.getBlockState(m.set(x, ground - k, z));
            if (!natural(g)) return;
            double h = hash(x, z * 31 + k, seed ^ 0x51AB);
            Block b = h < 0.42 ? Blocks.MOSSY_COBBLESTONE : h < 0.66 ? Blocks.COBBLESTONE : h < 0.86 ? Blocks.STONE : Blocks.MOSS_BLOCK;
            level.setBlock(m, b.defaultBlockState(), 2);
        }
    }

    private static boolean nearFall(List<double[]> falls, int x, int z) {
        for (double[] f : falls) if (Math.abs(f[0] - x) < f[6] + 7 && Math.abs(f[1] - z) < f[6] + 7) return true;
        return false;
    }

    // ------------------------------------------------------------------ under the water
    private static void bedColumn(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, int ground, int water, double w,
                                  boolean pond, boolean pool, boolean cold, long seed) {
        double h = hash(x, z, seed), h2 = hash(z, x, seed ^ 77);
        BlockState bed = level.getBlockState(m.set(x, ground, z));
        if (natural(bed)) {
            Block b;
            if (cold) b = h < 0.5 ? Blocks.GRAVEL : (h < 0.7 ? Blocks.COBBLESTONE : bed.getBlock());
            else if (pool) b = h < 0.4 ? Blocks.GRAVEL : h < 0.62 ? Blocks.MOSSY_COBBLESTONE : h < 0.82 ? Blocks.CLAY : bed.getBlock();
            else if (pond || w > 2.2) b = h < 0.3 ? Blocks.CLAY : h < 0.55 ? Blocks.MUD : h < 0.75 ? Blocks.GRAVEL : h < 0.85 ? Blocks.SAND : Blocks.MOSS_BLOCK;
            else b = h < 0.35 ? Blocks.GRAVEL : h < 0.58 ? Blocks.CLAY : h < 0.73 ? Blocks.MUD : h < 0.83 ? Blocks.MOSS_BLOCK : bed.getBlock();
            if (b != bed.getBlock()) level.setBlock(m, b.defaultBlockState(), 2);
        }
        if (cold || pool) return;
        int depth = water - ground;
        boolean slow = pond || w > 1.8;
        // underwater plants: in slow water, not on the falls
        BlockState above = level.getBlockState(m.set(x, ground + 1, z));
        if (above.is(Blocks.WATER) && level.getFluidState(m).isSource() && depth >= 1) {
            if (depth >= 3 && h2 < (slow ? 0.07 : 0.02)) {
                BlockState up = level.getBlockState(m.set(x, ground + 2, z));
                if (up.is(Blocks.WATER)) {
                    level.setBlock(m.set(x, ground + 1, z), Blocks.TALL_SEAGRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), 2);
                    level.setBlock(m.set(x, ground + 2, z), Blocks.TALL_SEAGRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2);
                }
            } else if (h2 < (slow ? 0.28 : 0.08)) {
                level.setBlock(m.set(x, ground + 1, z), Blocks.SEAGRASS.defaultBlockState(), 2);
            }
        }
        // lily pads where the water is broad and still
        if ((pond || w > 2.3) && hash(x, z, seed ^ 191) < (pond ? 0.13 : 0.06)
                && level.getBlockState(m.set(x, water + 1, z)).isAir() && level.getFluidState(m.set(x, water, z)).isSource())
            level.setBlock(m.set(x, water + 1, z), Blocks.LILY_PAD.defaultBlockState(), 2);
    }

    // ------------------------------------------------------------------ the banks
    private static void bankColumn(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, int ground, double edge, double w,
                                   boolean cold, boolean forest, long seed) {
        BlockState g = level.getBlockState(m.set(x, ground, z));
        if (!natural(g)) return;
        double bank = 1 + 0.7 * w;                       // bank width = 1 + 0.35 x the stream's width
        double h = hash(x, z, seed ^ 3), p = hash(z, x, seed ^ 5);
        boolean free = level.getBlockState(m.set(x, ground + 1, z)).isAir();
        m.set(x, ground, z);
        if (edge < 1.2) {                                 // the waterline
            Block b = cold ? (h < 0.35 ? Blocks.GRAVEL : h < 0.55 ? Blocks.MOSSY_COBBLESTONE : null)
                    : h < 0.18 ? Blocks.MUD : h < 0.32 ? Blocks.ROOTED_DIRT : h < 0.43 ? Blocks.COARSE_DIRT : h < 0.58 ? Blocks.MOSS_BLOCK
                    : h < 0.65 ? Blocks.CLAY : h < 0.72 ? Blocks.MOSSY_COBBLESTONE : null;
            if (b != null) level.setBlock(m, b.defaultBlockState(), 2);
            if (!cold && free && p < 0.14) put(level, m.set(x, ground + 1, z), Blocks.MOSS_CARPET.defaultBlockState());
            else if (!cold && free && p < 0.22) put(level, m.set(x, ground + 1, z), Blocks.FERN.defaultBlockState());
            return;
        }
        if (cold) {
            if (edge < 1.2 + bank && free && p < 0.05) put(level, m.set(x, ground + 1, z), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
            return;
        }
        if (!free) return;
        boolean soil = g.is(BlockTags.DIRT) || g.is(Blocks.MOSS_BLOCK);
        if (edge < 1.2 + bank) {                          // inner bank
            if (!soil) { if (p < 0.08) put(level, m.set(x, ground + 1, z), Blocks.MOSS_CARPET.defaultBlockState()); return; }
            if (h < 0.12 && g.is(Blocks.GRASS_BLOCK)) level.setBlock(m.set(x, ground, z), Blocks.MOSS_BLOCK.defaultBlockState(), 2);
            m.set(x, ground + 1, z);
            if (p < 0.13) tall(level, m, Blocks.TALL_GRASS);
            else if (p < 0.26) put(level, m, Blocks.FERN.defaultBlockState());
            else if (p < 0.30) tall(level, m, Blocks.LARGE_FERN);
            else if (p < 0.42) put(level, m, Blocks.GRASS.defaultBlockState());
            else if (p < 0.46) put(level, m, Blocks.AZALEA.defaultBlockState());
            else if (p < 0.48) put(level, m, Blocks.FLOWERING_AZALEA.defaultBlockState());
            else if (p < 0.505) put(level, m, Blocks.BIG_DRIPLEAF.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, face(x, z)));
            else if (p < 0.58) put(level, m, Blocks.MOSS_CARPET.defaultBlockState());
            else if (forest && p < 0.61) put(level, m, fallenLeaves(h));
            return;
        }
        if (edge < 1.2 + 2 * bank) {                      // outer bank
            m.set(x, ground + 1, z);
            if (p < 0.012) boulder(level, m, x, ground, z, h);
            else if (p < 0.018 && soil) fallenLog(level, m, x, ground, z, h, forest);
            else if (!soil) return;
            else if (p < 0.13) put(level, m, Blocks.GRASS.defaultBlockState());
            else if (p < 0.19) put(level, m, Blocks.FERN.defaultBlockState());
            else if (forest && p < 0.24) put(level, m, fallenLeaves(h));
        }
    }

    // ------------------------------------------------------------------ springs
    private static void springColumn(WorldGenLevel level, BlockPos.MutableBlockPos m, IslandSampler s, IslandSampler.Column c, Island is,
                                     int x, int z, int ground, int water, long seed) {
        double[] sp = is.springs.get(s.springOf(c));
        double q = s.springQ(c);
        double h = hash(x, z, seed ^ 11), p = hash(z, x, seed ^ 13);
        BlockState g = level.getBlockState(m.set(x, ground, z));
        if (!natural(g) && !g.is(Blocks.CLAY) && !g.is(Blocks.MUD)) return;
        double ang = Math.atan2(c.v - sp[1], c.u - sp[0]);
        double out = Math.cos(ang) * sp[4] + Math.sin(ang) * sp[5];   // +1 toward the outlet, -1 the uphill side
        if (q < 1) {
            if (water != Integer.MIN_VALUE) {                          // a wet hollow: soft clay and mud bed
                level.setBlock(m, (h < 0.42 ? Blocks.CLAY : h < 0.8 ? Blocks.MUD : Blocks.MOSS_BLOCK).defaultBlockState(), 2);
                if (p < 0.12 && level.getBlockState(m.set(x, ground + 1, z)).is(Blocks.WATER) && level.getBlockState(m.set(x, ground + 2, z)).isAir())
                    smallDripleaf(level, m.set(x, ground + 1, z), x, z, true);
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
        boolean edging = q < 1.35 && Math.abs(Math.sin((ang - Math.atan2(sp[5], sp[4]) - 2.0) / 2)) < 0.28;   // one side: old edging
        if (edging && p < 0.55) {
            level.setBlock(m, Blocks.MUD.defaultBlockState(), 2);
            BlockState brick = h < 0.5 ? Blocks.MUD_BRICKS.defaultBlockState() : h < 0.8 ? Blocks.MUD_BRICK_SLAB.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            if (level.getBlockState(m.set(x, ground + 1, z)).isAir() || level.getBlockState(m).canBeReplaced()) level.setBlock(m, brick, 2);
            return;
        }
        Block b = h < 0.3 ? Blocks.MOSS_BLOCK : h < 0.45 ? Blocks.ROOTED_DIRT : h < 0.58 ? Blocks.COARSE_DIRT : h < 0.66 ? Blocks.MUD : null;
        if (b != null) level.setBlock(m, b.defaultBlockState(), 2);
        if (!level.getBlockState(m.set(x, ground + 1, z)).isAir()) return;
        if (out < -0.35 && p < 0.22) put(level, m, (p < 0.09 ? Blocks.FLOWERING_AZALEA : Blocks.AZALEA).defaultBlockState());   // the high side
        else if (p < 0.08) put(level, m, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        else if (p < 0.14 && (b == Blocks.MOSS_BLOCK || b == Blocks.MUD)) put(level, m, Blocks.BIG_DRIPLEAF.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, face(x, z)));
        else if (p < 0.40) put(level, m, Blocks.MOSS_CARPET.defaultBlockState());
        else if (p < 0.50) put(level, m, Blocks.FERN.defaultBlockState());
        else if (p < 0.56) put(level, m, fallenLeaves(h));
    }

    private static void smallDripleaf(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, boolean wet) {
        if (!level.getBlockState(m.above()).isAir() && !wet) return;
        BlockState lower = Blocks.SMALL_DRIPLEAF.defaultBlockState().setValue(SmallDripleafBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, face(x, z)).setValue(BlockStateProperties.WATERLOGGED, wet);
        BlockState upper = lower.setValue(SmallDripleafBlock.HALF, DoubleBlockHalf.UPPER).setValue(BlockStateProperties.WATERLOGGED, false);
        BlockPos at = m.immutable();
        if (!level.getBlockState(at.above()).isAir()) return;
        level.setBlock(at, lower, 2);
        level.setBlock(at.above(), upper, 2);
    }

    // ------------------------------------------------------------------ falls
    /** Mossy, broken rock round each fall in this chunk, vines hanging off it, and leaves trailing over the lip. */
    private static void fallDressing(WorldGenLevel level, ChunkPos cp, BlockPos.MutableBlockPos m, double[] f, long seed) {
        double reach = f[6] + 4;
        int x0 = (int) Math.floor(Math.min(f[0], f[7]) - reach), x1 = (int) Math.ceil(Math.max(f[0], f[7]) + reach);
        int z0 = (int) Math.floor(Math.min(f[1], f[8]) - reach), z1 = (int) Math.ceil(Math.max(f[1], f[8]) + reach);
        x0 = Math.max(x0, cp.getMinBlockX()); x1 = Math.min(x1, cp.getMaxBlockX());
        z0 = Math.max(z0, cp.getMinBlockZ()); z1 = Math.min(z1, cp.getMaxBlockZ());
        if (x0 > x1 || z0 > z1) return;
        int yLo = (int) f[3] - 3, yHi = (int) f[2] + 2;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            double d = Math.min(Math.hypot(x - f[0], z - f[1]), Math.hypot(x - f[7], z - f[8]));
            if (d > reach) continue;
            for (int y = yLo; y <= yHi; y++) {
                BlockState st = level.getBlockState(m.set(x, y, z));
                if (!natural(st)) continue;
                Direction open = null;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockState nb = level.getBlockState(m.set(x + dir.getStepX(), y, z + dir.getStepZ()));
                    if (nb.isAir() || nb.getFluidState().is(FluidTags.WATER)) { open = dir; break; }
                }
                if (open == null) continue;                              // buried: leave it
                double h = hash(x * 31 + y, z, seed ^ 23);
                Block b = h < 0.36 ? Blocks.MOSSY_COBBLESTONE : h < 0.5 ? Blocks.MOSS_BLOCK : h < 0.6 ? Blocks.COBBLESTONE
                        : h < 0.65 ? Blocks.MOSSY_STONE_BRICKS : null;
                if (b != null) level.setBlock(m.set(x, y, z), b.defaultBlockState(), 2);
                // vines down the face, on the dry side
                BlockPos air = new BlockPos(x + open.getStepX(), y, z + open.getStepZ());
                if (hash(x, z * 17 + y, seed ^ 29) < 0.22 && level.getBlockState(air).isAir() && inChunk(cp, air)) {
                    BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(open.getOpposite()), true);
                    int len = 1 + (int) (hash(y, x, seed ^ 31) * 4);
                    for (int k = 0; k < len; k++) {
                        BlockPos vp = air.below(k);
                        if (!level.getBlockState(vp).isAir()) break;
                        level.setBlock(vp, vine, 2);
                    }
                }
            }
            // leaves trailing over the lip and grass tufts on top
            int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            if (topY >= yHi - 4 && topY <= yHi + 3 && d > f[6] + 0.5) {
                BlockState below = level.getBlockState(m.set(x, topY - 1, z));
                if (natural(below) && level.getBlockState(m.set(x, topY, z)).isAir()) {
                    double h = hash(z, x, seed ^ 37);
                    if (h < 0.12) put(level, m, fallenLeaves(h * 8));
                    else if (h < 0.35) put(level, m, Blocks.GRASS.defaultBlockState());
                    else if (h < 0.45) put(level, m, Blocks.FERN.defaultBlockState());
                }
            }
        }
    }

    // ------------------------------------------------------------------ small pieces
    private static void boulder(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int ground, int z, double h) {
        put(level, m.set(x, ground + 1, z), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        if (h < 0.4 && level.getBlockState(m.set(x, ground + 2, z)).isAir()) put(level, m, (h < 0.2 ? Blocks.MOSS_BLOCK : Blocks.MOSSY_COBBLESTONE).defaultBlockState());
        if (h > 0.6 && level.getBlockState(m.set(x, ground + 2, z)).isAir()) put(level, m, Blocks.MOSS_CARPET.defaultBlockState());
    }

    /** A rotting log lying along the bank: three or four blocks, mossed over, only on level ground within the chunk. */
    private static void fallenLog(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int ground, int z, double h, boolean forest) {
        boolean alongX = h < 0.5;
        int len = 3 + (int) (h * 10) % 2;
        int cx = x >> 4, cz = z >> 4;
        for (int k = 0; k < len; k++) {
            int lx = alongX ? x + k : x, lz = alongX ? z : z + k;
            if ((lx >> 4) != cx || (lz >> 4) != cz) return;
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz) - 1;
            if (top != ground || !natural(level.getBlockState(m.set(lx, top, lz)))) return;
            BlockState up = level.getBlockState(m.set(lx, top + 1, lz));
            if (!up.isAir() && !up.canBeReplaced()) return;
        }
        Block log = forest ? (h < 0.25 ? Blocks.DARK_OAK_LOG : Blocks.SPRUCE_LOG) : Blocks.OAK_LOG;
        BlockState st = log.defaultBlockState().setValue(RotatedPillarBlock.AXIS, alongX ? Direction.Axis.X : Direction.Axis.Z);
        for (int k = 0; k < len; k++) {
            int lx = alongX ? x + k : x, lz = alongX ? z : z + k;
            BlockState up = level.getBlockState(m.set(lx, ground + 1, lz));
            if (up.getBlock() instanceof DoublePlantBlock) level.setBlock(m.above(), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(m.set(lx, ground + 1, lz), st, 2);
            if (hash(lx, lz, 41) < 0.45 && level.getBlockState(m.set(lx, ground + 2, lz)).isAir())
                level.setBlock(m, Blocks.MOSS_CARPET.defaultBlockState(), 2);
        }
    }

    private static BlockState fallenLeaves(double h) {
        Block b = h < 0.3 ? Blocks.OAK_LEAVES : h < 0.6 ? Blocks.DARK_OAK_LEAVES : h < 0.8 ? Blocks.AZALEA_LEAVES : Blocks.SPRUCE_LEAVES;
        return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    private static void put(WorldGenLevel level, BlockPos.MutableBlockPos m, BlockState st) {
        BlockState here = level.getBlockState(m);
        if (!here.isAir() && !(here.canBeReplaced() && here.getFluidState().isEmpty())) return;
        level.setBlock(m, st, 2);
    }

    private static void tall(WorldGenLevel level, BlockPos.MutableBlockPos m, Block b) {
        if (!level.getBlockState(m).isAir() || !level.getBlockState(m.above()).isAir()) return;
        BlockPos at = m.immutable();
        level.setBlock(at, b.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(at.above(), b.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 2);
    }

    /** Natural ground that may be reworked: earth, stone, gravel, sand - never anything built. */
    static boolean natural(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL) || st.is(BlockTags.SAND)
                || st.is(Blocks.CALCITE) || st.is(Blocks.CLAY) || st.is(Blocks.MUD) || st.is(Blocks.MOSS_BLOCK) || st.is(Blocks.SNOW_BLOCK);
    }

    private static boolean inChunk(ChunkPos cp, BlockPos p) { return (p.getX() >> 4) == cp.x && (p.getZ() >> 4) == cp.z; }

    private static Direction face(int x, int z) { return Direction.from2DDataValue((int) (hash(x, z, 53) * 4) & 3); }

    static double hash(int x, int z, long seed) {
        long v = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        v = (v ^ (v >>> 31)) * 0xBF58476D1CE4E5B9L; v ^= v >>> 29;
        return ((v >>> 11) & ((1L << 40) - 1)) / (double) (1L << 40);
    }
}
