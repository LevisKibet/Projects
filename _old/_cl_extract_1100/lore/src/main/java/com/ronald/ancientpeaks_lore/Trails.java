package com.ronald.ancientpeaks_lore;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faint, overgrown paths: lanes weaving between the village houses and fields and out to the castle, and old trails
 * across the island to the landing coast, the great arch, the three swords, Merlin's tower and the mountain lakes.
 * Trails follow the land (they wind round cliffs and lakes) on an even bed: cut through rises with rough, battered banks,
 * made up over dips. Painted after each chunk is decorated, only over natural ground, patchily, in gravel, moss, mossy
 * cobble and cracked stone.
 */
public final class Trails {
    private record Trail(double[][] pts, double width, double density, boolean lane, double[] cum, int id) {
        Trail(double[][] pts, double width, double density, boolean lane) { this(pts, width, density, lane, cumulative(pts), (int) (Math.floor(pts[0][0]) * 31 + Math.floor(pts[0][1]) * 17 + pts.length) & 0xffff); }
    }
    private static final java.util.concurrent.atomic.AtomicInteger ID = new java.util.concurrent.atomic.AtomicInteger();
    private static double[] cumulative(double[][] p) {
        double[] c = new double[p.length];
        for (int i = 1; i < p.length; i++) c[i] = c[i - 1] + Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
        return c;
    }
    private record Net(List<Trail> trails, Map<Long, List<int[]>> buckets, List<double[]> elev) {}
    private static final Map<String, Net> NETS = new ConcurrentHashMap<>();
    /** Out on the island's trails (not the village lanes) mossy stones and cobbles are just ground: dug or paved like earth. */
    private static final ThreadLocal<Boolean> WILD = ThreadLocal.withInitial(() -> false);
    private static boolean wildStone(BlockState st) {
        return WILD.get() && (st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MUD_BRICKS));
    }

    private static final Block[] LANE = {Blocks.GRAVEL, Blocks.GRAVEL, Blocks.GRAVEL, Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE,
            Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.MOSS_BLOCK, Blocks.COARSE_DIRT, Blocks.COARSE_DIRT};
    private static final Block[] TRAIL = {Blocks.GRAVEL, Blocks.GRAVEL, Blocks.MOSS_BLOCK, Blocks.MOSS_BLOCK, Blocks.MOSSY_COBBLESTONE, Blocks.COARSE_DIRT,
            Blocks.COARSE_DIRT, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.ROOTED_DIRT};

    public static void decorate(WorldGenLevel level, ChunkPos cp) {
        if (!(level instanceof WorldGenRegion region)) return;
        IslandSampler s = ((HighlandsAccess) (Object) region.getLevel().getChunkSource().randomState()).ancientpeaks$sampler();
        if (s == null) return;
        Island is = s.islandAt(cp.getMiddleBlockX(), cp.getMiddleBlockZ());
        if (is == null) return;
        long seed = level.getSeed();
        StructureTemplateManager mgr = region.getLevel().getStructureManager();
        Net net = NETS.computeIfAbsent(seed + "/" + is.cx + "/" + is.cz, k -> build(seed, s, is, mgr));
        List<int[]> segs = net.buckets.get(cp.toLong());
        if (segs == null) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<BlockPos> fences = new ArrayList<>();
        int[][] beds = new int[16][16];
        boolean[][] wildCol = new boolean[16][16];
        double[][] offs = new double[16][16];
        for (int[] row : beds) Arrays.fill(row, Integer.MIN_VALUE);
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                double best = 1e9, along = 0, bed = 0; Trail bt = null; int side = 1;
                for (int[] sg : segs) {
                    Trail t = net.trails.get(sg[0]);
                    double[] ev = net.elev.get(sg[0]);
                    double[] a = t.pts[sg[1]], b = t.pts[sg[1] + 1];
                    double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
                    double u = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x + 0.5 - a[0]) * dx + (z + 0.5 - a[1]) * dz) / l2));
                    double qx = a[0] + u * dx - (x + 0.5), qz = a[1] + u * dz - (z + 0.5);
                    double d = Math.sqrt(qx * qx + qz * qz);
                    if (d < best) {
                        best = d; bt = t; along = t.cum[sg[1]] + u * Math.sqrt(l2);
                        bed = ev[sg[1]] + (ev[sg[1] + 1] - ev[sg[1]]) * u;
                        side = (dx * (z + 0.5 - a[1]) - dz * (x + 0.5 - a[0])) > 0 ? 1 : -1;
                    }
                }
                if (bt == null) continue;
                WILD.set(!bt.lane);
                // the long-forgotten ways (to the harbour, to the cave) come and go: stretches of path, then grass and
                // brush where the land has taken it back, then path again
                if (bt.density < 0.6 && Math.sin(along * 0.09 + bt.id) + 0.6 * Math.sin(along * 0.23 + bt.id * 1.7) < -0.45) continue;
                double w = bt.width * (1 + 0.12 * Math.sin(along * 0.15 + bt.id));
                int target = (int) Math.floor(bed);
                if (best <= w + 4.5) { beds[x - cp.getMinBlockX()][z - cp.getMinBlockZ()] = target; offs[x - cp.getMinBlockX()][z - cp.getMinBlockZ()] = best - w;
                    wildCol[x - cp.getMinBlockX()][z - cp.getMinBlockZ()] = !bt.lane; }
                // a river or stream across the way: a plank bridge over it
                if (best <= w + 1.3) {
                    int wt = waterTop(level, pos, x, z);
                    if (wt != Integer.MIN_VALUE) {
                        bridge(level, pos, x, z, Math.max(target, wt + 1), best - w, along, bt, seed, fences);
                        continue;
                    }
                }
                // a tree standing on the way or right beside it comes down whole (no trunk left in the path, no floating crown)
                if (best <= w + 1.5) { if (!bt.lane) clearStreamside(level, pos, x, z); fellTree(region, level, pos, x, z); }
                if (best <= w + 4.5) shape(level, pos, x, z, target, Math.max(0, best - w), seed);
                if (best <= w) {
                    double h = hash(x, z, seed);
                    if (h > bt.density * Math.pow(1 - best / (w + 0.6), 0.35)) continue;
                    paint(level, pos, x, z, bt.lane ? LANE : TRAIL, hash(z, x, seed ^ 0x5DEECE66DL));
                } else if (best <= w + 1.9) {
                    edge(level, pos, x, z, bt, along, side, best - w, seed, fences);
                }
            }
        }
        // tidy the cut: lumps of earth or stone left hanging over the way, and plants with nothing under them
        for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) {
            int b = beds[i][j];
            if (b == Integer.MIN_VALUE) continue;
            int x = cp.getMinBlockX() + i, z = cp.getMinBlockZ() + j;
            for (int y = b + 1; y <= b + 10; y++) {
                BlockState st = level.getBlockState(pos.set(x, y, z));
                if (st.isAir()) continue;
                BlockState below = level.getBlockState(pos.set(x, y - 1, z));
                if (!below.isAir()) continue;
                pos.set(x, y, z);
                boolean loose = st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL) || st.is(BlockTags.SAND)
                        || st.is(Blocks.MOSS_BLOCK) || st.is(Blocks.MOSS_CARPET) || (st.canBeReplaced() && st.getFluidState().isEmpty())
                        || st.is(BlockTags.FLOWERS) || st.is(BlockTags.SAPLINGS);
                if (loose) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        // lone blocks still standing up out of the way (a stone the cut couldn't take, a bank block): knocked down level
        int[][] tops = new int[16][16];
        for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++)
            tops[i][j] = beds[i][j] == Integer.MIN_VALUE ? Integer.MIN_VALUE : solidTop(level, pos, cp.getMinBlockX() + i, cp.getMinBlockZ() + j, beds[i][j]);
        for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) {
            int b = beds[i][j];
            if (b == Integer.MIN_VALUE || offs[i][j] > 0.8 || tops[i][j] <= b || tops[i][j] > b + 3) continue;
            int low = 0, seen = 0;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int a = i + d[0], c = j + d[1];
                if (a < 0 || c < 0 || a > 15 || c > 15 || tops[a][c] == Integer.MIN_VALUE) continue;
                seen++;
                if (tops[a][c] <= b) low++;
            }
            if (seen < 2 || low < seen - 1) continue;
            WILD.set(wildCol[i][j]);
            int x = cp.getMinBlockX() + i, z = cp.getMinBlockZ() + j;
            for (int y = tops[i][j]; y > b; y--) {
                BlockState st = level.getBlockState(pos.set(x, y, z));
                boolean natural = st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL) || st.is(BlockTags.SAND)
                        || st.is(Blocks.CLAY) || wildStone(st) || st.is(BlockTags.LEAVES);
                if (!natural) break;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (BlockPos f : fences) {                                    // join fence runs to their neighbours
            BlockState st = level.getBlockState(f);
            if (st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock)
                level.setBlock(f, Block.updateFromNeighbourShapes(st, level, f), 2);
        }
    }

    /** Beside the path: lamp posts every so often, short runs of old fence, bushes and mossy stones - never in step on both sides. */
    private static void edge(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, Trail t, double along, int side, double band,
                             long seed, List<BlockPos> fences) {
        double spacing = t.lane ? 15 : 22;
        long k = Math.round(along / spacing);
        boolean lampSide = (hashL(k, t.id, seed) & 1) == 0 ? side == 1 : side == -1;
        boolean lamp = Math.abs(along - k * spacing) < 0.55 && band > 0.3 && band < 1.4 && lampSide && hash((int) k, t.id, seed ^ 77) < 0.85;
        long f = (long) Math.floor(along / 10);
        boolean fenceRun = hash((int) f, t.id * 3 + side, seed ^ 91) < 0.3 && (along - f * 10) < 4.2 && band > 0.4 && band < 1.45;
        double h = hash(x, z, seed ^ 0x3C6EF372L);
        if (!lamp && !fenceRun && h > 0.2) return;
        int g = ground(level, pos, x, z);
        if (g == Integer.MIN_VALUE) return;
        BlockPos up = new BlockPos(x, g + 1, z);
        clearPlant(level, up);
        if (!level.getBlockState(up).isAir()) return;
        Block fence = hash(x, z, seed ^ 5) < 0.6 ? Blocks.SPRUCE_FENCE : Blocks.DARK_OAK_FENCE;
        if (lamp) {
            boolean broken = hash(x, z, seed ^ 13) < 0.15;
            if (broken) { level.setBlock(up, Blocks.LANTERN.defaultBlockState(), 2); return; }   // fallen off its post
            level.setBlock(up, fence.defaultBlockState(), 2);
            level.setBlock(up.above(), fence.defaultBlockState(), 2);
            level.setBlock(up.above(2), Blocks.LANTERN.defaultBlockState(), 2);
            return;
        }
        if (fenceRun) {
            if (hash(x, z, seed ^ 23) < 0.15) return;                 // a missing post
            level.setBlock(up, (hash(x, z, seed ^ 29) < 0.2 ? Blocks.MOSSY_COBBLESTONE_WALL : fence).defaultBlockState(), 2);
            fences.add(up.immutable());
            return;
        }
        if (h < 0.06) {                                               // a bush
            Block b = h < 0.02 ? Blocks.FLOWERING_AZALEA : h < 0.035 ? Blocks.AZALEA : Blocks.OAK_LEAVES;
            BlockState st = b.defaultBlockState();
            if (st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) st = st.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
            level.setBlock(up, st, 2);
            if (b == Blocks.OAK_LEAVES && hash(x, z, seed ^ 41) < 0.5 && level.getBlockState(up.above()).isAir())
                level.setBlock(up.above(), Blocks.AZALEA_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
        } else if (h < 0.095) {                                       // an old mossy stone
            Block b = h < 0.075 ? Blocks.MOSSY_COBBLESTONE : h < 0.085 ? Blocks.MOSSY_STONE_BRICKS : Blocks.CRACKED_STONE_BRICKS;
            level.setBlock(up, b.defaultBlockState(), 2);
            if (hash(x, z, seed ^ 43) < 0.35 && level.getBlockState(up.above()).isAir()) level.setBlock(up.above(), Blocks.MOSS_CARPET.defaultBlockState(), 2);
        } else if (h < 0.13) {                                        // a sunk stone flush with the grass
            level.setBlock(up.below(), (h < 0.11 ? Blocks.MOSSY_COBBLESTONE : Blocks.MOSS_BLOCK).defaultBlockState(), 2);
        } else if (h < 0.16) {
            level.setBlock(up, Blocks.MOSS_CARPET.defaultBlockState(), 2);
        } else if (h < 0.2) {
            level.setBlock(up, (h < 0.18 ? Blocks.FERN : Blocks.SWEET_BERRY_BUSH).defaultBlockState(), 2);
        }
    }

    /**
     * Cut and fill for the path. On the way itself (e = 0) the ground is brought to the path's bed height: rises are
     * cut through, dips made up with earth. Beside it the cut faces and fills are battered back about a block per block
     * and roughened, so a path through a slope leaves a natural bank of turf and bare rock, not a straight wall.
     * Only natural ground is touched; where the real ground is far from the bed (more than 5 blocks) it is left alone.
     */
    private static void shape(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int bed, double e, long seed) {
        int g = ground(level, pos, x, z);
        if (g == Integer.MIN_VALUE || Math.abs(g - bed) > (e <= 0.5 ? 8 : 6)) return;
        // a block-wide level shoulder either side of the way, then banks rising (or falling) about a block per block out,
        // roughened only gently and smoothly (no lone pillars or one-block steps along the edge)
        double n = 0.7 * (0.5 + 0.5 * Math.sin(x * 0.21 + z * 0.17 + (seed & 31))) + 0.25 * (0.5 + 0.5 * Math.sin(x * 0.53 - z * 0.47));
        double rise = Math.max(0, e - 1.0) * 1.15 + (e > 1.0 ? n : 0);
        int up = bed + (int) Math.floor(rise);
        int lo = bed - (int) Math.floor(rise);
        if (g > up) {                                                   // cut
            for (int y = g + 3; y > up; y--) {
                BlockState st = level.getBlockState(pos.set(x, y, z));
                if (st.isAir()) continue;
                boolean natural = st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL) || st.is(Blocks.CALCITE)
                        || st.is(BlockTags.SAND) || st.is(Blocks.SNOW) || st.is(Blocks.CLAY) || wildStone(st)
                        || (st.canBeReplaced() && st.getFluidState().isEmpty());
                if (st.is(BlockTags.LEAVES)) { level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2); continue; }
                if (!natural) return;                                      // a wall, a fence: leave the column be
                if (st.getBlock() instanceof DoublePlantBlock) level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
            BlockState top = level.getBlockState(pos.set(x, up, z));
            if (top.is(BlockTags.DIRT) && !top.is(Blocks.GRASS_BLOCK))
                level.setBlock(pos, (e > 0 && hash(z, x, seed ^ 11) < 0.25 ? Blocks.COARSE_DIRT : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
            else if (top.is(BlockTags.BASE_STONE_OVERWORLD) && e > 0 && hash(z, x, seed ^ 13) < 0.3)
                level.setBlock(pos, (hash(x, z, seed ^ 17) < 0.5 ? Blocks.MOSSY_COBBLESTONE : Blocks.MOSS_BLOCK).defaultBlockState(), 2);
        } else if (g < lo && e < 3.5) {                                 // fill
            for (int y = g + 1; y <= lo; y++) {
                BlockState st = level.getBlockState(pos.set(x, y, z));
                if (!(st.isAir() || (st.canBeReplaced() && st.getFluidState().isEmpty()))) return;
                level.setBlock(pos, (y == lo ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2);
            }
            BlockState below = level.getBlockState(pos.set(x, g, z));
            if (below.is(Blocks.GRASS_BLOCK)) level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
        }
    }

    /** The top of open water in this column (a river or stream under the way), or MIN_VALUE if it's dry land. */
    private static int waterTop(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        for (int y = top; y > top - 14; y--) {
            BlockState st = level.getBlockState(pos.set(x, y, z));
            if (st.isAir() || st.is(BlockTags.LEAVES) || st.is(Blocks.LILY_PAD)) continue;
            if (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return y;
            if (st.canBeReplaced() || !st.blocksMotion()) continue;
            return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * One column of a plain timber bridge: a spruce deck a block above the water (or at the way's own level if that's
     * higher), a rail of fence along each side, and stout posts down into the riverbed every few blocks.
     */
    private static void bridge(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int deck, double e, double along,
                               Trail t, long seed, List<BlockPos> fences) {
        boolean rail = e > 0.25;
        level.setBlock(pos.set(x, deck, z), (rail ? Blocks.SPRUCE_PLANKS : (hash(x, z, seed ^ 61) < 0.15 ? Blocks.STRIPPED_SPRUCE_WOOD : Blocks.SPRUCE_PLANKS)).defaultBlockState(), 2);
        for (int y = deck + 1; y <= deck + 3; y++) {                     // headroom
            BlockState st = level.getBlockState(pos.set(x, y, z));
            if (!st.isAir() && (st.canBeReplaced() || st.is(BlockTags.LEAVES) || !st.getFluidState().isEmpty())) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        if (rail) {
            BlockPos r = new BlockPos(x, deck + 1, z);
            level.setBlock(r, Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
            fences.add(r);
            if (Math.floorMod((long) Math.floor(along), 5L) == 0) {      // a post down to the bed of the stream
                for (int y = deck - 1, n = 0; n < 14; y--, n++) {
                    BlockState st = level.getBlockState(pos.set(x, y, z));
                    if (!(st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty())) break;
                    level.setBlock(pos, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * What the island's streams leave on their banks (Ruins of Logres' StreamDecor) comes off the way: fallen logs
     * lying on the ground, azalea and dripleaf, piles of fallen leaves.
     */
    private static void clearStreamside(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        for (int y = top; y > top - 6; y--) {
            BlockState st = level.getBlockState(pos.set(x, y, z));
            boolean fallenLog = st.is(BlockTags.LOGS) && st.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                    && st.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) != net.minecraft.core.Direction.Axis.Y
                    && level.getBlockState(pos.below()).is(BlockTags.DIRT);
            boolean litter = st.is(BlockTags.LEAVES) && st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    && st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT) && level.getBlockState(pos.below()).is(BlockTags.DIRT);
            BlockState under = level.getBlockState(pos.below());
            boolean boulder = (st.is(Blocks.MOSSY_COBBLESTONE) || (st.is(Blocks.MOSS_BLOCK) && under.is(Blocks.MOSSY_COBBLESTONE)))
                    && level.getBlockState(pos.above()).isAir() && (under.is(Blocks.MOSSY_COBBLESTONE) || (under.is(BlockTags.DIRT) && y > top - 2));
            boolean bush = st.is(Blocks.AZALEA) || st.is(Blocks.FLOWERING_AZALEA) || st.is(Blocks.BIG_DRIPLEAF) || st.is(Blocks.BIG_DRIPLEAF_STEM)
                    || st.is(Blocks.SMALL_DRIPLEAF) || st.is(Blocks.MOSS_CARPET);
            if (fallenLog || litter || bush || boulder) {
                if (st.is(Blocks.SMALL_DRIPLEAF) && level.getBlockState(pos.above()).is(Blocks.SMALL_DRIPLEAF)) level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /** A natural tree with its trunk on (or right beside) the way: logs and its own leaves come away, within reach. */
    private static void fellTree(WorldGenRegion region, WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        BlockPos log = null;
        for (int y = top; y > top - 30; y--) {
            BlockState st = level.getBlockState(pos.set(x, y, z));
            if (st.isAir() || st.is(BlockTags.LEAVES) || (st.canBeReplaced() && st.getFluidState().isEmpty())) continue;
            if (st.is(BlockTags.LOGS) && !st.getBlock().getDescriptionId().contains("stripped")) log = pos.immutable();
            break;
        }
        if (log == null) return;
        ChunkPos c = region.getCenter();
        ArrayDeque<BlockPos> q = new ArrayDeque<>(); Set<BlockPos> seen = new HashSet<>();
        q.add(log); seen.add(log);
        int n = 0;
        while (!q.isEmpty() && n < 600) {
            BlockPos p = q.poll();
            if (Math.abs((p.getX() >> 4) - c.x) > 1 || Math.abs((p.getZ() >> 4) - c.z) > 1) continue;
            BlockState st = level.getBlockState(p);
            boolean wood = st.is(BlockTags.LOGS) && !st.getBlock().getDescriptionId().contains("stripped");
            boolean leaf = st.is(BlockTags.LEAVES) && !(st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT) && st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT));
            boolean hang = st.is(Blocks.VINE) || st.is(Blocks.COCOA) || st.is(Blocks.BEE_NEST);
            if (!wood && !leaf && !hang) continue;
            if (Math.abs(p.getX() - log.getX()) > 6 || Math.abs(p.getZ() - log.getZ()) > 6 || p.getY() < log.getY() - 1) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            n++;
            for (var d : net.minecraft.core.Direction.values()) {
                BlockPos nb = p.relative(d);
                if (seen.add(nb)) q.add(nb);
            }
            if (wood) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {   // branches set on the diagonal
                BlockPos nb = p.offset(dx, 1, dz);
                if (seen.add(nb)) q.add(nb);
            }
        }
        // the stump's grass: the ground under it becomes earth again
        BlockState under = level.getBlockState(pos.set(log.getX(), log.getY() - 1, log.getZ()));
        if (under.is(Blocks.DIRT) || under.is(Blocks.ROOTED_DIRT)) level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
    }

    private static int ground(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        for (int y = top; y > top - 14; y--) {
            BlockState st = level.getBlockState(pos.set(x, y, z));
            if (st.isAir() || st.is(BlockTags.LEAVES)) continue;
            if (!st.getFluidState().isEmpty() || st.is(BlockTags.LOGS)) return Integer.MIN_VALUE;
            if (st.canBeReplaced() || !st.blocksMotion()) continue;
            boolean natural = st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL) || st.is(Blocks.CALCITE)
                    || st.is(Blocks.CLAY) || wildStone(st);
            return natural ? y : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** Top solid block of a column near the way's bed (plants and air skipped), or the bed if none is found above it. */
    private static int solidTop(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int bed) {
        for (int y = bed + 6; y > bed - 3; y--) {
            BlockState st = level.getBlockState(pos.set(x, y, z));
            if (st.isAir() || (st.canBeReplaced() && st.getFluidState().isEmpty()) || !st.getFluidState().isEmpty()) continue;
            return y;
        }
        return bed;
    }

    private static void clearPlant(WorldGenLevel level, BlockPos up) {
        BlockState a = level.getBlockState(up);
        if (!a.isAir() && a.canBeReplaced() && a.getFluidState().isEmpty()) {
            if (a.getBlock() instanceof DoublePlantBlock) level.setBlock(up.above(), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(up, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static long hashL(long a, int b, long seed) { return (long) (hash((int) a, b, seed) * (1L << 40)); }

    private static void paint(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, Block[] mats, double h) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        for (int y = top; y > top - 14; y--) {
            BlockState st = level.getBlockState(pos.set(x, y, z));
            if (st.isAir() || st.is(BlockTags.LEAVES)) continue;
            if (!st.getFluidState().isEmpty()) return;                                // water: no path
            if (st.is(BlockTags.LOGS)) return;                                       // a trunk
            if (st.canBeReplaced() || !st.blocksMotion()) continue;                   // grass, flowers, carpets
            boolean ground = st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRAVEL) || st.is(Blocks.CALCITE)
                    || st.is(Blocks.CLAY) || wildStone(st);
            if (!ground) return;                                                      // a building, a field, a wall
            level.setBlock(pos, mats[(int) (h * mats.length) % mats.length].defaultBlockState(), 2);
            BlockPos up = pos.above();
            BlockState a = level.getBlockState(up);
            if (!a.isAir() && a.canBeReplaced() && a.getFluidState().isEmpty()) {
                if (a.getBlock() instanceof DoublePlantBlock) level.setBlock(up.above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(up, Blocks.AIR.defaultBlockState(), 2);
            }
            if (h > 0.93 && level.getBlockState(up).isAir()) level.setBlock(up, Blocks.MOSS_CARPET.defaultBlockState(), 2);
            return;
        }
    }

    private static double hash(int x, int z, long seed) {
        long v = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        v = (v ^ (v >>> 31)) * 0xBF58476D1CE4E5B9L; v ^= v >>> 29;
        return ((v >>> 11) & ((1L << 40) - 1)) / (double) (1L << 40);
    }

    // ------------------------------------------------------------------ building the network
    private static Net build(long seed, IslandSampler s, Island is, StructureTemplateManager mgr) {
        long t0 = System.currentTimeMillis();
        Random r = new Random(seed ^ is.cx * 31L ^ is.cz * 17L ^ 0x7A11L);
        List<Trail> trails = new ArrayList<>();
        int[] vc = LoreSite.castleSpot(s, is, mgr);
        double half = LoreSite.castleHalf(mgr);
        // lanes: a spanning tree over the castle gates, every door and field gate, plus cross-links
        List<double[]> nodes = new ArrayList<>();
        for (int[] g : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            nodes.add(new double[]{vc[0] + g[0] * (half + 4), vc[1] + g[1] * (half + 4)});
        for (LoreSite.Lot lot : LoreSite.villageLayout(seed, s, is, mgr)) { int[] d = lot.door(); nodes.add(new double[]{d[0], d[1]}); }
        int n = nodes.size();
        boolean[] in = new boolean[n]; in[0] = true;
        for (int k = 1; k < n; k++) {                                     // Prim's
            double bd = 1e18; int bi = -1, bj = -1;
            for (int i = 0; i < n; i++) if (in[i]) for (int j = 0; j < n; j++) if (!in[j]) {
                double d = dist(nodes.get(i), nodes.get(j)); if (d < bd) { bd = d; bi = i; bj = j; }
            }
            if (bj < 0) break;
            in[bj] = true;
            trails.add(new Trail(wiggle(new double[][]{nodes.get(bi), nodes.get(bj)}, r, 2.5), 2.1, 0.85, true));
        }
        for (int i = 1; i < n; i++) {                                     // cross-links so the lanes interweave
            int bj = -1; double bd = 1e18;
            for (int j = 1; j < n; j++) if (j != i) { double d = dist(nodes.get(i), nodes.get(j)); if (d < bd && d > 12) { bd = d; bj = j; } }
            if (bj > 0 && bd < 40 && r.nextDouble() < 0.4) trails.add(new Trail(wiggle(new double[][]{nodes.get(i), nodes.get(bj)}, r, 2), 2.0, 0.8, true));
        }
        // trails over the island, routed over the land
        Grid g = new Grid(s, is);
        double[] village = {vc[0], vc[1]};
        List<double[]> hubs = new ArrayList<>();
        hubs.add(village);
        List<int[]> landings = s.findLandings(is, 1);
        double[] landing = landings.isEmpty() ? null : new double[]{landings.get(0)[0], landings.get(0)[2]};
        int[] a = is.anchor("great_arch"), m = is.anchor("meadow_plateau");
        int[] tw = LoreSite.towerSpot(s, is);
        double[] arch = {a[0], a[1]}, swords = {m[0], m[1]}, tower = {tw[0], tw[1]};
        List<double[][]> links = new ArrayList<>();
        List<Double> dens = new ArrayList<>();
        if (landing != null) links.add(new double[][]{village, landing});
        links.add(new double[][]{village, arch});
        links.add(new double[][]{village, swords});
        links.add(new double[][]{swords, tower});
        int[] dk = LoreSite.dockSpot(s, is, mgr);
        int harbourLink = -1;
        if (dk != null) { harbourLink = links.size(); links.add(new double[][]{nodes.get(0), {dk[0] - dk[2] * 18, dk[1] - dk[3] * 18}}); }   // the top of the harbour stairs
        hubs.add(swords); hubs.add(tower); hubs.add(arch);
        for (double[] t : is.tarnsWorld()) {
            double[] lake = {t[0], t[1]};
            double[] from = hubs.stream().min(Comparator.comparingDouble(h -> dist(h, lake))).get();
            links.add(new double[][]{from, lake});
        }
        Trail harbourWay = null;
        for (int li = 0; li < links.size(); li++) {
            double[][] l = links.get(li);
            List<double[]> route = g.route(l[0], l[1]);
            if (route == null || route.size() < 2) continue;
            // the way down to the harbour is long overgrown (the boat there is how Mordred went round to the cave)
            Trail t = new Trail(wiggle(route.toArray(new double[0][]), r, 1.5), 2.0, li == harbourLink ? 0.5 : 0.72, false);
            trails.add(t);
            if (li == harbourLink) harbourWay = t;
        }
        // and a fainter way that leaves it and climbs round to where the cave comes out on the mountainside
        int[] exit = is.caveExit();
        if (exit != null) {
            double[] to = {exit[0], exit[1]}, from = null; double bd = 1e18;
            List<Trail> pool = new ArrayList<>();
            if (harbourWay != null) pool.add(harbourWay);
            else for (Trail t : trails) if (!t.lane) pool.add(t);
            for (Trail t : pool)
                for (int i = t.pts.length / 5; i < t.pts.length; i++) { double d = dist(t.pts[i], to); if (d < bd) { bd = d; from = t.pts[i]; } }
            if (from != null && bd > 12) {
                List<double[]> route = g.route(from, to);
                if (route != null && route.size() > 1) trails.add(new Trail(wiggle(route.toArray(new double[0][]), r, 1.5), 1.7, 0.45, false));
            }
        }
        // bucket segments by chunk
        Map<Long, List<int[]>> buckets = new HashMap<>();
        for (int ti = 0; ti < trails.size(); ti++) {
            double[][] p = trails.get(ti).pts;
            for (int si = 0; si < p.length - 1; si++) {
                int x0 = (int) Math.floor(Math.min(p[si][0], p[si + 1][0]) - 4) >> 4, x1 = (int) Math.floor(Math.max(p[si][0], p[si + 1][0]) + 4) >> 4;
                int z0 = (int) Math.floor(Math.min(p[si][1], p[si + 1][1]) - 4) >> 4, z1 = (int) Math.floor(Math.max(p[si][1], p[si + 1][1]) + 4) >> 4;
                for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++)
                    buckets.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> new ArrayList<>()).add(new int[]{ti, si});
            }
        }
        AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] island {},{}: {} paths and trails laid in {} ms", is.cx, is.cz, trails.size(), System.currentTimeMillis() - t0);
        // each path's bed height along its length: the land's own height, smoothed over ~10 blocks and kept within a
        // block or two of it, so the way runs evenly without deep cuttings or causeways (the routing keeps it gentle)
        List<double[]> elev = new ArrayList<>();
        IslandSampler.Column col = new IslandSampler.Column();
        for (Trail t : trails) {
            int np = t.pts.length;
            double[] raw = new double[np], sm = new double[np];
            for (int i = 0; i < np; i++) { s.fill(col, (int) Math.floor(t.pts[i][0]), (int) Math.floor(t.pts[i][1])); raw[i] = col.is == null ? 63 : col.h; }
            for (int i = 0; i < np; i++) {
                double sum = 0, wsum = 0;
                for (int j = i; j >= 0 && t.cum[i] - t.cum[j] <= 5; j--) { double wt = 1 - (t.cum[i] - t.cum[j]) / 6; sum += raw[j] * wt; wsum += wt; }
                for (int j = i + 1; j < np && t.cum[j] - t.cum[i] <= 5; j++) { double wt = 1 - (t.cum[j] - t.cum[i]) / 6; sum += raw[j] * wt; wsum += wt; }
                // close to the real ground: smoothed a little, never a deep cutting or a tall causeway
                sm[i] = Math.max(raw[i] - 2, Math.min(raw[i] + 1.5, sum / wsum));
            }
            elev.add(sm);
        }
        return new Net(trails, buckets, elev);
    }

    private static double dist(double[] a, double[] b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); }

    /** Subdivide, smooth (Chaikin) and let the line meander a little. */
    private static double[][] wiggle(double[][] in, Random r, double amp) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < in.length - 1; i++) {
            double[] a = in[i], b = in[i + 1];
            int steps = Math.max(1, (int) (dist(a, b) / 6));
            for (int k = 0; k < steps; k++) { double t = k / (double) steps; pts.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t}); }
        }
        pts.add(in[in.length - 1].clone());
        double ph = r.nextDouble() * 10;
        for (int i = 1; i < pts.size() - 1; i++) {                      // sideways meander
            double[] p = pts.get(i), q = pts.get(i + 1);
            double dx = q[0] - p[0], dz = q[1] - p[1], l = Math.max(1e-6, Math.hypot(dx, dz));
            double o = amp * Math.sin(i * 0.55 + ph) + amp * 0.5 * Math.sin(i * 1.7 + ph * 2);
            p[0] += -dz / l * o; p[1] += dx / l * o;
        }
        for (int it = 0; it < 2; it++) {                                  // Chaikin
            List<double[]> s = new ArrayList<>();
            s.add(pts.get(0));
            for (int i = 0; i < pts.size() - 1; i++) {
                double[] a = pts.get(i), b = pts.get(i + 1);
                s.add(new double[]{a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25});
                s.add(new double[]{a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75});
            }
            s.add(pts.get(pts.size() - 1));
            pts = s;
        }
        return pts.toArray(new double[0][]);
    }

    /** A coarse height grid of the island for routing trails that avoid cliffs, lakes, the sea and the strait. */
    private static final class Grid {
        static final int C = 4;
        final int x0, z0, n;
        final float[] h; final byte[] kind;                                  // kind: 0 ok, 1 lake shore/strait (costly), 2 water/impassable
        Grid(IslandSampler s, Island is) {
            int R = (int) is.boundR;
            x0 = is.cx - R; z0 = is.cz - R; n = 2 * R / C + 1;
            h = new float[n * n]; kind = new byte[n * n];
            IslandSampler.Column c = new IslandSampler.Column();
            List<double[]> tarns = is.tarnsWorld();
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                int x = x0 + i * C, z = z0 + j * C, k = i * n + j;
                s.fill(c, x, z);
                h[k] = (float) c.h;
                if (c.is == null || c.m < 1 || c.h < 64.5) { kind[k] = 2; continue; }
                for (double[] t : tarns) if (Math.hypot(x - t[0], z - t[1]) < t[3] + 3) { kind[k] = 2; break; }
                if (kind[k] == 0) {
                    var zn = s.zone(c, (int) c.h);
                    if (zn == IslandSampler.Zone.GORGE || zn == IslandSampler.Zone.TARN || s.nearRiver(c, 5)) kind[k] = 1;   // cross streams, don't follow them
                }
            }
        }
        int idx(double x, double z) {
            int i = (int) Math.round((x - x0) / C), j = (int) Math.round((z - z0) / C);
            return i < 0 || j < 0 || i >= n || j >= n ? -1 : i * n + j;
        }
        int nearestOpen(int k) {
            if (k < 0) return -1;
            if (kind[k] < 2) return k;
            int ci = k / n, cj = k % n;
            for (int rad = 1; rad < 24; rad++)
                for (int di = -rad; di <= rad; di++) for (int dj = -rad; dj <= rad; dj++) {
                    if (Math.max(Math.abs(di), Math.abs(dj)) != rad) continue;
                    int i = ci + di, j = cj + dj;
                    if (i >= 0 && j >= 0 && i < n && j < n && kind[i * n + j] < 2) return i * n + j;
                }
            return -1;
        }
        /** A way from one place to another: gentle where it can be, and only as steep as it must be. */
        List<double[]> route(double[] from, double[] to) {
            List<double[]> r = route(from, to, 0.55);
            return r != null ? r : route(from, to, 0.85);
        }

        List<double[]> route(double[] from, double[] to, double maxSlope) {
            int s = nearestOpen(idx(from[0], from[1])), g = nearestOpen(idx(to[0], to[1]));
            if (s < 0 || g < 0) return null;
            float[] cost = new float[n * n]; Arrays.fill(cost, Float.MAX_VALUE);
            int[] prev = new int[n * n]; Arrays.fill(prev, -1);
            PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingLong(e -> e[0]));
            cost[s] = 0; pq.add(new long[]{0, s});
            int gi = g / n, gj = g % n;
            // 16 directions, so a way up a slope can slant across it at a gentle grade
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                    {2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {1, -2}, {-1, 2}, {-1, -2}};
            while (!pq.isEmpty()) {
                long[] e = pq.poll(); int k = (int) e[1];
                if (k == g) break;
                int i = k / n, j = k % n;
                for (int[] d : nb) {
                    int a = i + d[0], b = j + d[1];
                    if (a < 0 || b < 0 || a >= n || b >= n) continue;
                    int q = a * n + b;
                    if (kind[q] == 2) continue;
                    boolean knight = Math.abs(d[0]) + Math.abs(d[1]) == 3;
                    if (knight) {                                              // the cells it passes between must be fine too
                        int m1 = (i + Integer.signum(d[0]) * (Math.abs(d[0]) == 2 ? 1 : 0)) * n + (j + Integer.signum(d[1]) * (Math.abs(d[1]) == 2 ? 1 : 0));
                        int m2 = (i + Integer.signum(d[0])) * n + (j + Integer.signum(d[1]));
                        if (kind[m1] == 2 || kind[m2] == 2) continue;
                        if (Math.abs(h[m1] - h[k]) > maxSlope * C * 1.2 || Math.abs(h[m2] - h[q]) > maxSlope * C * 1.2) continue;
                    }
                    double len = Math.hypot(d[0], d[1]) * C;
                    double slope = Math.abs(h[q] - h[k]) / len;
                    if (slope > maxSlope) continue;                            // too steep a step: round it
                    // a gentle grade costs little; anything steeper than about 1 in 7 costs a lot more, so a way up a
                    // hillside winds and slants across it (switchbacks) instead of charging straight up the fall line
                    double over = Math.max(0, slope - 0.13);
                    double c = len * (1 + 5 * slope + 420 * over * over) * (kind[q] == 1 ? 3 : 1) * (1 + 0.1 * ((q * 2654435761L >>> 16) % 100) / 100.0);
                    float nc = (float) (cost[k] + c);
                    if (nc < cost[q]) {
                        cost[q] = nc; prev[q] = k;
                        double hh = Math.hypot(a - gi, b - gj) * C;
                        pq.add(new long[]{(long) ((nc + hh) * 16), q});
                    }
                }
            }
            if (prev[g] < 0 && g != s) return null;
            LinkedList<double[]> out = new LinkedList<>();
            for (int k = g; k >= 0; k = prev[k]) { out.addFirst(new double[]{x0 + (k / n) * C, z0 + (k % n) * C}); if (k == s) break; }
            return out;
        }
    }
}
