package com.ronald.ancientpeaks.world;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/** Fills the island's rivers, tarns, strait and megalake; water spilling over each river step becomes a waterfall. */
public class GorgeRiverFeature extends Feature<NoneFeatureConfiguration> {
    public GorgeRiverFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    static IslandSampler sampler(WorldGenLevel level) {
        if (!(level instanceof WorldGenRegion region)) return null;
        RandomState rs = region.getLevel().getChunkSource().randomState();
        return ((HighlandsAccess) (Object) rs).ancientpeaks$sampler();
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        return run(ctx.level(), new ChunkPos(ctx.origin()), ctx.random());
    }

    /** Runs straight after the chunk's decoration pass (see ChunkGeneratorMixin), outside the feature system. */
    public static boolean run(WorldGenLevel level, ChunkPos cp, net.minecraft.util.RandomSource random) {
        IslandSampler sampler = sampler(level);
        if (sampler == null) return false;
        IslandSampler.Column col = new IslandSampler.Column();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<BlockPos> placed = new ArrayList<>();
        java.util.Map<Long, IslandSampler.Column> cols = new java.util.HashMap<>();
        // water above sea level is gathered first and checked as a whole before any is placed (see holdIn)
        it.unimi.dsi.fastutil.longs.LongOpenHashSet high = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        List<int[]> decorate = new ArrayList<>();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
                if (sampler.islandAt(x, z) == null) continue;
                IslandSampler.Column c = column(sampler, cols, x, z);
                if (c.m <= 0.5) continue;
                int top = sampler.waterTop(c);
                for (int y = 28; y <= top; y++) {
                    if (!sampler.isWater(c, x, y, z)) continue;
                    if (y > 62) { high.add(BlockPos.asLong(x, y, z)); continue; }
                    pos.set(x, y, z);
                    if (!fillable(level.getBlockState(pos))) continue;
                    if (spillsInto(level.getBlockState(pos.below()))) continue;   // never leave water floating
                    level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
                }
                decorate.add(new int[]{x, z, top});
            }
        }
        if (!high.isEmpty()) {
            // the same water in a margin round the chunk, so what's checked at the chunk's edges agrees with its neighbours
            it.unimi.dsi.fastutil.longs.LongOpenHashSet all = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(high);
            int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
            for (int x = x0 - MARGIN; x < x0 + 16 + MARGIN; x++) for (int z = z0 - MARGIN; z < z0 + 16 + MARGIN; z++) {
                if (x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16) continue;
                if (sampler.islandAt(x, z) == null) continue;
                IslandSampler.Column c = column(sampler, cols, x, z);
                if (c.m <= 0.5) continue;
                int top = sampler.waterTop(c);
                for (int y = 63; y <= top; y++) if (sampler.isWater(c, x, y, z)) all.add(BlockPos.asLong(x, y, z));
            }
            for (long k : high) {
                pos.set(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
                if (!fillable(level.getBlockState(pos))) continue;
                holdIn(level, sampler, cols, all, pos);
                level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
                placed.add(pos.immutable());
            }
        }
        for (int[] d : decorate) {
            IslandSampler.Column c = column(sampler, cols, d[0], d[1]);
            if (sampler.tarnOf(c) >= 0) decorateTarn(level, sampler, c, d[0], d[1], d[2], random, pos);
            boolean way = c.p1d < 4 || c.p2d < 4 || c.hxd < 4;
            if (c.chq < 1 || (c.cvd < 3.5 && c.cvs > 0.15) || way) decorateMere(level, d[0], d[1], random, pos, c.chq < 1, (int) Math.min(c.h, 250));
        }
        for (BlockPos p : placed) {
            for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                // anywhere it can run on (open air, or grass and flowers it washes away), it is set running
                if (spillsInto(level.getBlockState(p.relative(d)))) { level.scheduleTick(p, Fluids.WATER, 1 + (p.hashCode() & 7)); break; }
            }
        }
        if (IslandSampler.DEBUG && !placed.isEmpty()) System.out.println("[ancientpeaks] chunk " + cp + " placed " + placed.size() + " water above sea level");
        return !placed.isEmpty();
    }

    /** Lake life: springs, seagrass, kelp and sea pickles on the floor; tall grass and ferns along the shore. */
    private static void decorateTarn(WorldGenLevel level, IslandSampler s, IslandSampler.Column col, int x, int z, int top,
                                     net.minecraft.util.RandomSource random, BlockPos.MutableBlockPos pos) {
        double td = s.tarnDist(col), r = s.tarnRadius(col);
        int lvl = s.tarnLevel(col);
        if (td < r * 0.97) {
            int y = lvl;
            while (y > lvl - 12 && level.getBlockState(pos.set(x, y, z)).is(Blocks.WATER)) y--;
            int depth = lvl - y;                                  // water blocks above the floor block at y
            if (depth < 1) return;
            BlockPos floor = new BlockPos(x, y, z);
            if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return;
            float f = random.nextFloat();
            if (s.inTarnCore(col) && f < 0.04f) {                 // a spring: soul sand sending up bubbles
                level.setBlock(floor, Blocks.SOUL_SAND.defaultBlockState(), 2);
                for (int yy = y + 1; yy <= lvl; yy++)
                    level.setBlock(pos.set(x, yy, z), Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(net.minecraft.world.level.block.BubbleColumnBlock.DRAG_DOWN, false), 2);
            } else if (f < 0.10f && depth >= 4) {                 // kelp
                int h = 1 + random.nextInt(depth - 2);
                for (int k = 1; k <= h; k++)
                    level.setBlock(pos.set(x, y + k, z), k == h ? Blocks.KELP.defaultBlockState().setValue(net.minecraft.world.level.block.KelpBlock.AGE, 20 + random.nextInt(4))
                            : Blocks.KELP_PLANT.defaultBlockState(), 2);
            } else if (f < 0.14f) {                               // sea pickles, glowing on the floor
                level.setBlock(pos.set(x, y + 1, z), Blocks.SEA_PICKLE.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.SeaPickleBlock.PICKLES, 1 + random.nextInt(4))
                        .setValue(net.minecraft.world.level.block.SeaPickleBlock.WATERLOGGED, true), 2);
            } else if (f < 0.24f && depth >= 3) {                 // tall seagrass
                level.setBlock(pos.set(x, y + 1, z), Blocks.TALL_SEAGRASS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
                level.setBlock(pos.set(x, y + 2, z), Blocks.TALL_SEAGRASS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            } else if (f < 0.55f) {                               // seagrass
                level.setBlock(pos.set(x, y + 1, z), Blocks.SEAGRASS.defaultBlockState(), 2);
            }
        } else if (td < r + 8) {
            // the shore: tall grass, grass and ferns, thinning out away from the water
            if (random.nextFloat() > 0.75f - (float) ((td - r) / 8) * 0.45f) return;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
            BlockPos at = new BlockPos(x, y, z);
            if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()) return;
            var ground = level.getBlockState(at.below());
            if (!(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.PODZOL))) return;
            float f = random.nextFloat();
            if (f < 0.55f) {
                var tall = random.nextFloat() < 0.8f ? Blocks.TALL_GRASS : Blocks.LARGE_FERN;
                level.setBlock(at, tall.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
                level.setBlock(at.above(), tall.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            } else {
                level.setBlock(at, (f < 0.85f ? Blocks.GRASS : Blocks.FERN).defaultBlockState(), 2);
            }
        }
    }

    /** How far round the chunk the water above sea level is worked out (its neighbours just over the edge). */
    private static final int MARGIN = 1;

    private static IslandSampler.Column column(IslandSampler s, java.util.Map<Long, IslandSampler.Column> cols, int x, int z) {
        return cols.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(x, z), k -> { IslandSampler.Column c = new IslandSampler.Column(); s.fill(c, x, z); return c; });
    }

    /** Somewhere water can go: empty, or a plant in the channel (the water takes its place rather than leaving a gap). */
    private static boolean fillable(net.minecraft.world.level.block.state.BlockState st) {
        return st.isAir() || (st.getFluidState().isEmpty() && st.canBeReplaced(net.minecraft.world.level.material.Fluids.WATER));
    }

    /**
     * Make sure the ground holds this water in. Wherever it could run off - open air or grass and flowers beside it or
     * under it, outside the stream - the bank is built up there with the ground's own block, so the water stays in
     * its channel instead of spilling down the slope or washing through the plants below. Running on down its own
     * channel is left alone (the riffles and little falls where a stream steps down).
     */
    private static void holdIn(WorldGenLevel level, IslandSampler sampler, java.util.Map<Long, IslandSampler.Column> cols,
                               it.unimi.dsi.fastutil.longs.LongOpenHashSet water, BlockPos at) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            n.setWithOffset(at, d);
            if (water.contains(n.asLong())) continue;
            var st = level.getBlockState(n);
            if (!spillsInto(st)) continue;
            if (d != Direction.DOWN && inChannel(sampler, column(sampler, cols, n.getX(), n.getZ()), n.getY())) continue;
            if (d != Direction.DOWN && !groundWithin(level, n, 2)) continue;     // no tall walls: over a drop it runs down
            level.setBlock(n, bankBlock(level, n), 2);
        }
    }

    /**
     * What to build a bank lip from: a mix of what the ground round it is made of, so it reads as part of the bank,
     * not a strip of dirt. Rock beside it gives stone, cobble, mossy cobble and andesite; soil gives dirt, coarse dirt,
     * rooted dirt and moss; a lip open to the sky is grassed over.
     */
    private static net.minecraft.world.level.block.state.BlockState bankBlock(WorldGenLevel level, BlockPos at) {
        int rock = 0, soil = 0;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            var st = level.getBlockState(at.relative(d));
            if (!st.getFluidState().isEmpty() || !st.isSolidRender(level, at.relative(d))) continue;
            if (st.is(net.minecraft.tags.BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.MUD) || st.is(Blocks.MOSS_BLOCK)) soil++;
            else rock++;
        }
        long h = at.asLong() * 0x9E3779B97F4A7C15L;
        int pick = (int) ((h >>> 33) & 7);
        boolean open = level.getBlockState(at.above()).isAir();
        BlockPos below = at.below();
        if (soil >= rock) {
            if (open) return pick < 2 ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
            if (level.getBlockState(below).is(Blocks.GRASS_BLOCK)) level.setBlock(below, Blocks.DIRT.defaultBlockState(), 2);
            return (pick < 3 ? Blocks.DIRT : pick < 5 ? Blocks.COARSE_DIRT : pick < 6 ? Blocks.ROOTED_DIRT : Blocks.MOSS_BLOCK).defaultBlockState();
        }
        return (pick < 3 ? Blocks.STONE : pick < 5 ? Blocks.COBBLESTONE : pick < 7 ? Blocks.MOSSY_COBBLESTONE : Blocks.ANDESITE).defaultBlockState();
    }

    /**
     * Would water running into height y of this column stay with the water it came from? Yes along a stream's own
     * course (its riffles, and the falls where it drops away), and inside the bowl of a tarn, pond, spring or plunge
     * pool below its rim.
     */
    private static boolean inChannel(IslandSampler s, IslandSampler.Column c, int y) {
        if (c.m <= 0.5) return false;
        for (int i = 0; i < s.streamCount(c); i++)
            if (s.isStream(c, i) && s.streamDist(c, i) < s.streamWidth(c, i) + 0.5) return true;
        if (y > Math.floor(c.h)) return false;
        return (s.tarnOf(c) >= 0 && s.tarnDist(c) < s.tarnRadius(c) - 0.3) || (s.pondOf(c) >= 0 && s.pondQ(c) < 1)
                || (s.springOf(c) >= 0 && s.springQ(c) < 1) || (s.poolOf(c) >= 0 && s.poolQ(c) < 1);
    }

    /** Is there solid ground no more than `depth` blocks under this spot? */
    private static boolean groundWithin(WorldGenLevel level, BlockPos at, int depth) {
        for (int k = 1; k <= depth; k++) {
            var st = level.getBlockState(at.below(k));
            if (st.isSolidRender(level, at.below(k)) || !st.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** Could water run into this block (air, or grass, flowers and anything else water washes out)? */
    private static boolean spillsInto(net.minecraft.world.level.block.state.BlockState st) {
        if (!st.getFluidState().isEmpty()) return false;                 // water already: fine
        return st.isAir() || st.canBeReplaced(net.minecraft.world.level.material.Fluids.WATER);
    }

    /**
     * The mere cave, grown lush: moss over the floors, azalea and ferns, glow berries hanging from the roof (the light
     * to find your way by), spore blossoms, dripleaf in the shallows; the great chamber keeps its dripstone too, and
     * old bones lie by the water.
     */
    static void decorateMere(WorldGenLevel level, int x, int z, net.minecraft.util.RandomSource r, BlockPos.MutableBlockPos pos, boolean chamber, int top) {
        for (int y = 48; y < top; y++) {
            pos.set(x, y, z);
            var here = level.getBlockState(pos);
            if (!here.isAir()) continue;
            BlockPos up = pos.above(), down = pos.below();
            var above = level.getBlockState(up);
            var below = level.getBlockState(down);
            boolean roof = above.isSolidRender(level, up), floor = below.isSolidRender(level, down);
            boolean wetBeside = false;
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST})
                if (level.getFluidState(down.relative(d)).is(net.minecraft.tags.FluidTags.WATER)) wetBeside = true;
            if (roof) {
                float k = r.nextFloat();
                if (k < 0.13) vines(level, pos.immutable(), 1 + r.nextInt(5), r);
                else if (k < 0.145) level.setBlock(pos, Blocks.SPORE_BLOSSOM.defaultBlockState(), 2);
                else if (chamber && k < 0.24) {
                    if (r.nextFloat() < 0.5) level.setBlock(up, Blocks.DRIPSTONE_BLOCK.defaultBlockState(), 2);
                    hang(level, pos.immutable(), 1 + r.nextInt(r.nextFloat() < 0.3 ? 5 : 3), Direction.DOWN);
                } else if (r.nextFloat() < 0.35) level.setBlock(up, Blocks.MOSS_BLOCK.defaultBlockState(), 2);
                continue;
            }
            if (floor && level.getFluidState(pos).isEmpty()) {
                if (wetBeside && r.nextFloat() < 0.5) {
                    level.setBlock(down, Blocks.CLAY.defaultBlockState(), 2);
                    if (r.nextFloat() < 0.25) placeDripleaf(level, pos.immutable(), r);
                    continue;
                }
                if (r.nextFloat() < 0.75) level.setBlock(down, Blocks.MOSS_BLOCK.defaultBlockState(), 2);
                float k = r.nextFloat();
                if (k < 0.18) level.setBlock(pos, Blocks.MOSS_CARPET.defaultBlockState(), 2);
                else if (k < 0.22) level.setBlock(pos, Blocks.AZALEA.defaultBlockState(), 2);
                else if (k < 0.25) level.setBlock(pos, Blocks.FLOWERING_AZALEA.defaultBlockState(), 2);
                else if (k < 0.31) level.setBlock(pos, (r.nextBoolean() ? Blocks.GRASS : Blocks.FERN).defaultBlockState(), 2);
                else if (chamber && k < 0.345) {
                    if (r.nextFloat() < 0.6) level.setBlock(down, Blocks.DRIPSTONE_BLOCK.defaultBlockState(), 2);
                    hang(level, pos.immutable(), 1 + r.nextInt(3), Direction.UP);
                } else if (chamber && k < 0.355) {
                    level.setBlock(pos, Blocks.BONE_BLOCK.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                            r.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z), 2);
                }
            }
        }
        // glow lichen on the walls here and there
        for (int y = 52; y < top; y++) {
            pos.set(x, y, z);
            if (!level.getBlockState(pos).isAir() || r.nextFloat() > 0.025) continue;
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos w = pos.relative(d);
                if (level.getBlockState(w).isSolidRender(level, w)) {
                    level.setBlock(pos, Blocks.GLOW_LICHEN.defaultBlockState().setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(d), true), 2);
                    break;
                }
            }
        }
    }

    /** Glow berry vines hanging down from pos, about half of them in fruit (and so alight). */
    private static void vines(WorldGenLevel level, BlockPos start, int len, net.minecraft.util.RandomSource r) {
        int n = 0;
        while (n < len && level.getBlockState(start.below(n)).isAir()) n++;
        for (int k = 0; k < n; k++) {
            boolean end = k == n - 1, berries = r.nextFloat() < 0.45;
            var st = end ? Blocks.CAVE_VINES.defaultBlockState().setValue(net.minecraft.world.level.block.CaveVines.BERRIES, berries)
                    : Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(net.minecraft.world.level.block.CaveVines.BERRIES, berries);
            level.setBlock(start.below(k), st, 2);
        }
    }

    /** Big dripleaf by the water: a stem or two, then the leaf. */
    private static void placeDripleaf(WorldGenLevel level, BlockPos pos, net.minecraft.util.RandomSource r) {
        Direction f = Direction.Plane.HORIZONTAL.getRandomDirection(r);
        int h = r.nextInt(3);
        for (int k = 0; k < h; k++) {
            if (!level.getBlockState(pos.above(k)).isAir()) return;
            level.setBlock(pos.above(k), Blocks.BIG_DRIPLEAF_STEM.defaultBlockState().setValue(net.minecraft.world.level.block.BigDripleafStemBlock.FACING, f), 2);
        }
        if (level.getBlockState(pos.above(h)).isAir())
            level.setBlock(pos.above(h), Blocks.BIG_DRIPLEAF.defaultBlockState().setValue(net.minecraft.world.level.block.BigDripleafBlock.FACING, f), 2);
    }

    /** A dripstone spike of the given length from pos, pointing the given way (each block's thickness set by hand). */
    private static void hang(WorldGenLevel level, BlockPos start, int len, Direction dir) {
        int n = 0;
        for (int k = 0; k < len; k++) {
            if (!level.getBlockState(start.relative(dir, k)).isAir() || !level.getFluidState(start.relative(dir, k)).isEmpty()) break;
            n++;
        }
        if (n == 0) return;
        for (int k = 0; k < n; k++) {
            var th = k == n - 1 ? net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP
                    : k == n - 2 ? net.minecraft.world.level.block.state.properties.DripstoneThickness.FRUSTUM
                    : k == 0 ? net.minecraft.world.level.block.state.properties.DripstoneThickness.BASE
                    : net.minecraft.world.level.block.state.properties.DripstoneThickness.MIDDLE;
            level.setBlock(start.relative(dir, k), Blocks.POINTED_DRIPSTONE.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.PointedDripstoneBlock.TIP_DIRECTION, dir)
                    .setValue(net.minecraft.world.level.block.PointedDripstoneBlock.THICKNESS, th), 2);
        }
    }
}
