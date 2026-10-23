package com.ronald.rivergence.world;

import com.ronald.rivergence.plan.Planner;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * Fills the planned water into a chunk, before its plants and trees go in.
 *
 * Each wet column is filled from its water level down to the bed. Above sea level the chunk's water (and its
 * neighbours' just over the edge, so both sides agree) is gathered first, and wherever it could run off - open air,
 * or grass and flowers beside or under it, outside its own channel or lake - the bank is built up with a block that
 * matches the ground round it. Where the water runs on down its channel (riffles, falls), it is set flowing.
 * The beds are dressed: gravel, sand, clay and dirt instead of grass under water.
 */
public final class WaterPlacer {
    private WaterPlacer() {}

    static final int SEA_TOP = 62;

    public static Planner planner(WorldGenLevel level) {
        if (!(level instanceof WorldGenRegion region)) return null;
        return ((RiverAccess) (Object) region.getLevel().getChunkSource().randomState()).rivergence$planner();
    }

    public static void flora(WorldGenLevel level, ChunkPos cp) { FloraDecor.run(level, cp); }

    public static void run(WorldGenLevel level, ChunkPos cp) {
        Planner planner = planner(level);
        if (planner == null) return;
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        // nothing planned anywhere near: nothing to do (a quick look before working out 324 columns)
        boolean near = false;
        for (int i = -1; i <= 1 && !near; i++) for (int j = -1; j <= 1 && !near; j++) near = planner.mayHaveWater(cp.x + i, cp.z + j);
        if (!near) return;
        // the columns of the chunk and one round it
        Planner.Column[] cols = new Planner.Column[18 * 18];
        boolean any = false;
        for (int a = 0; a < 18; a++) for (int b = 0; b < 18; b++) {
            Planner.Column c = new Planner.Column();
            planner.column(x0 - 1 + a, z0 - 1 + b, c, true);
            cols[a + b * 18] = c;
            if (c.wet()) any = true;
        }
        beaches(level, cols, new BlockPos.MutableBlockPos());
        if (!any) { SpringDecor.run(level, cols, new BlockPos.MutableBlockPos()); WallDecor.run(level, cols, new BlockPos.MutableBlockPos()); return; }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LongOpenHashSet all = new LongOpenHashSet();
        List<BlockPos> mine = new ArrayList<>();
        for (int a = 0; a < 18; a++) for (int b = 0; b < 18; b++) {
            Planner.Column c = cols[a + b * 18];
            if (!c.wet()) continue;
            boolean inside = a >= 1 && a <= 16 && b >= 1 && b <= 16;
            int x = c.x, z = c.z;
            // only down to the planned bed: where the ground falls away under it (a step, a cliff), the column is left
            // open and the water runs over the edge and down the land as it is, rather than standing in a walled shaft
            int floor = c.bed == Double.POSITIVE_INFINITY ? c.water - 12 : (int) Math.floor(c.bed) - 3;
            // drowned trees: what grew where the water now stands goes, trunk and crown, so nothing stands in the lake
            if (c.water > SEA_TOP && floor < c.water && c.channel && !c.lake) {
                int g = c.water;
                while (g >= floor && fillable(level.getBlockState(m.set(x, g, z)))) g--;
                if (g < floor) continue;                                       // nothing under it: let it fall
            }
            for (int y = c.water; y > level.getMinBuildHeight(); y--) {
                BlockState st = level.getBlockState(m.set(x, y, z));
                if (!st.getFluidState().isEmpty()) { if (y > SEA_TOP) all.add(m.asLong()); continue; }
                if (!fillable(st)) break;
                all.add(m.asLong());
                if (inside) mine.add(m.immutable());
            }
        }
        // bottom up, so each block's support is known when it's checked
        List<BlockPos> placed = new ArrayList<>();
        BANKS.get().clear();
        mine.sort((p, q) -> Integer.compare(p.getY(), q.getY()));
        for (BlockPos p : mine) {
            if (!fillable(level.getBlockState(p))) continue;
            if (p.getY() > SEA_TOP && !holdIn(level, cols, x0, z0, all, p)) {
                // still water with nothing to hold it on that side: the shore comes in here instead of a wall out there
                all.remove(p.asLong());
                BlockState under = level.getBlockState(p.below());
                if (under.isSolidRender(level, p.below()) && under.getFluidState().isEmpty()) { level.setBlock(p, bankBlock(level, p), 2); BANKS.get().add(p.asLong()); }
                continue;
            }
            else if (spillsInto(level.getBlockState(p.below())) && !all.contains(p.below().asLong())) continue;
            level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
            placed.add(p);
        }
        aprons(level, cols, x0, z0, all);
        // the beds: no grass under water
        for (int a = 1; a <= 16; a++) for (int b = 1; b <= 16; b++) {
            Planner.Column c = cols[a + b * 18];
            if (!c.wet()) continue;
            int y = c.water;
            while (y > level.getMinBuildHeight() && !level.getFluidState(m.set(c.x, y, c.z)).isEmpty()) y--;
            if (y >= c.water) continue;
            BlockState bed = level.getBlockState(m.set(c.x, y, c.z));
            if (c.pool != null && c.poolQ < 1 && (bed.is(BlockTags.DIRT) || bed.is(BlockTags.BASE_STONE_OVERWORLD) || bed.is(Blocks.GRASS_BLOCK) || bed.is(Blocks.SAND))) {
                level.setBlock(m, poolBed(c.x, y, c.z), 2);                    // a plunge pool: scoured gravel, clay and stones
                continue;
            }
            if (bed.is(Blocks.GRASS_BLOCK) || bed.is(Blocks.PODZOL) || bed.is(Blocks.MYCELIUM) || bed.is(Blocks.DIRT_PATH) || bed.is(Blocks.SNOW_BLOCK) || bed.is(Blocks.DIRT))
                level.setBlock(m, BankDecor.bed(level, c, c.x, y, c.z), 2);
        }
        SpringDecor.run(level, cols, m);
        poolRims(level, cols, m);
        BankDecor.run(level, cols, m);
        WallDecor.run(level, cols, m);
        for (BlockPos p : placed) {
            for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                if (spillsInto(level.getBlockState(p.relative(d)))) { level.scheduleTick(p, Fluids.WATER, 1 + (p.hashCode() & 7)); break; }
            }
        }
    }

    /**
     * Where a bank stands up off lower ground (beside a drop, a fall, a stream along a slope), the ground is brought up
     * to it in a slope, a block lower for each block out, so the bank reads as the lie of the land and not as a wall.
     */
    private static void aprons(WorldGenLevel level, Planner.Column[] cols, int x0, int z0, LongOpenHashSet water) {
        LongOpenHashSet banks = BANKS.get();
        if (banks.isEmpty()) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        long[] list = banks.toLongArray();
        for (long b : list) {
            int bx = BlockPos.getX(b), by = BlockPos.getY(b), bz = BlockPos.getZ(b);
            if (!level.getBlockState(m.set(bx, by + 1, bz)).isAir() && level.getFluidState(m).isEmpty()) continue;    // only the top of a bank
            BlockState like = level.getBlockState(m.set(bx, by, bz));
            if (like.getFluidState().isEmpty() == false || like.isAir()) continue;
            BlockState fill = like.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : like;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int k = 1; k <= 4; k++) {
                    int x = bx + d.getStepX() * k, z = bz + d.getStepZ() * k, top = by - k;
                    if (x < x0 - 15 || x > x0 + 30 || z < z0 - 15 || z > z0 + 30) break;   // within what this chunk may touch
                    Planner.Column c = col(cols, x0, z0, x, z);
                    if (c != null && c.wet()) break;                                        // not into the water's own course
                    // the ground here: found within a few blocks under the slope's line, or the slope stops
                    int g = top;
                    while (g > top - 6 && !level.getBlockState(m.set(x, g, z)).isSolidRender(level, m)) {
                        if (!level.getFluidState(m).isEmpty() || water.contains(m.asLong())) { g = Integer.MIN_VALUE; break; }
                        g--;
                    }
                    if (g == Integer.MIN_VALUE || g <= top - 6) break;
                    if (g >= top) break;                                                    // already as high as the slope
                    for (int y = g + 1; y <= top; y++) {
                        BlockState st = level.getBlockState(m.set(x, y, z));
                        if (!st.isAir() && !(st.canBeReplaced() && st.getFluidState().isEmpty())) break;
                        level.setBlock(m, y == top && level.getBlockState(m.set(x, y + 1, z)).isAir() && like.is(Blocks.GRASS_BLOCK) ? Blocks.GRASS_BLOCK.defaultBlockState() : fill, 2);
                        m.set(x, y, z);
                    }
                    if (level.getBlockState(m.set(x, g, z)).is(Blocks.GRASS_BLOCK)) level.setBlock(m, Blocks.DIRT.defaultBlockState(), 2);
                }
            }
        }
    }

    /** A bay's floor and the beach round it: sand (gravel and stones on cold coasts, red sand by the badlands), shingle near the waterline. */
    private static void beaches(WorldGenLevel level, Planner.Column[] cols, BlockPos.MutableBlockPos m) {
        for (int a = 1; a <= 16; a++) for (int b = 1; b <= 16; b++) {
            Planner.Column c = cols[a + b * 18];
            if (c.bayQ >= 1.5) continue;
            int y = 72;
            for (; y > 48; y--) {
                BlockState st = level.getBlockState(m.set(c.x, y, c.z));
                if (!st.isAir() && st.getFluidState().isEmpty() && !st.canBeReplaced()) break;
            }
            if (y <= 48) continue;
            var biome = level.getBiome(m);
            float t = biome.value().getBaseTemperature();
            boolean cold = t < 0.2f, red = biome.is(net.minecraft.tags.BiomeTags.IS_BADLANDS);
            long h = BlockPos.asLong(c.x, y, c.z) * 0x9E3779B97F4A7C15L;
            int pick = (int) ((h >>> 35) & 15);
            boolean shingle = c.bayQ > 0.85 && c.bayQ < 1.08 && pick < 5;
            BlockState top = red ? Blocks.RED_SAND.defaultBlockState()
                    : cold ? (pick < 8 ? Blocks.GRAVEL : pick < 13 ? Blocks.SAND : Blocks.STONE).defaultBlockState()
                    : shingle ? Blocks.GRAVEL.defaultBlockState()
                    : c.bay && c.bayQ < 0.6 && pick < 3 ? Blocks.CLAY.defaultBlockState() : Blocks.SAND.defaultBlockState();
            BlockState under = red ? Blocks.RED_SANDSTONE.defaultBlockState() : cold ? Blocks.GRAVEL.defaultBlockState() : Blocks.SANDSTONE.defaultBlockState();
            for (int k = 0; k < 4; k++) {
                BlockState st = level.getBlockState(m.set(c.x, y - k, c.z));
                if (!(st.is(BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.SNOW_BLOCK)
                        || st.is(Blocks.MUD) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.SAND) || st.is(Blocks.GRAVEL))) break;
                level.setBlock(m, k == 0 ? top : k == 1 && top.is(Blocks.SAND) ? top : under, 2);
                if (k == 0) {                                                     // no grass or flowers left on the sand
                    BlockState above = level.getBlockState(m.set(c.x, y + 1, c.z));
                    if (!above.isAir() && above.getFluidState().isEmpty() && above.canBeReplaced()) level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static BlockState poolBed(int x, int y, int z) {
        int pick = (int) ((BlockPos.asLong(x, y, z) * 0x9E3779B97F4A7C15L >>> 35) & 15);
        return (pick < 7 ? Blocks.GRAVEL : pick < 10 ? Blocks.CLAY : pick < 13 ? Blocks.COBBLESTONE : Blocks.MOSSY_COBBLESTONE).defaultBlockState();
    }

    /** The rock round a plunge pool: mossy cobble, cobble, stone and moss, wet with spray. */
    private static void poolRims(WorldGenLevel level, Planner.Column[] cols, BlockPos.MutableBlockPos m) {
        for (int a = 1; a <= 16; a++) for (int b = 1; b <= 16; b++) {
            Planner.Column c = cols[a + b * 18];
            if (c.pool == null || c.poolQ < 1 || c.poolQ >= 1.45) continue;
            int L = c.pool.level;
            for (int y = L + 2; y >= L - 1; y--) {
                BlockState st = level.getBlockState(m.set(c.x, y, c.z));
                if (st.isAir() || !st.getFluidState().isEmpty() || st.canBeReplaced()) continue;
                if (!(st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.GRAVEL) || st.is(Blocks.SAND))) break;
                int pick = (int) ((m.asLong() * 0x9E3779B97F4A7C15L >>> 37) & 15);
                Block bl = pick < 6 ? Blocks.MOSSY_COBBLESTONE : pick < 9 ? Blocks.COBBLESTONE : pick < 12 ? Blocks.STONE : pick < 14 ? Blocks.MOSS_BLOCK : null;
                if (bl != null) level.setBlock(m, bl.defaultBlockState(), 2);
                break;
            }
        }
    }

    private static BlockState bedBlock(Planner.Column c, int x, int y, int z) {
        long h = BlockPos.asLong(x, y, z) * 0x9E3779B97F4A7C15L;
        int pick = (int) ((h >>> 35) & 15);
        if (c.lake) return (pick < 6 ? Blocks.DIRT : pick < 10 ? Blocks.GRAVEL : pick < 13 ? Blocks.CLAY : Blocks.SAND).defaultBlockState();
        if (c.hw > 3) return (pick < 7 ? Blocks.GRAVEL : pick < 11 ? Blocks.SAND : pick < 13 ? Blocks.CLAY : Blocks.DIRT).defaultBlockState();
        return (pick < 8 ? Blocks.GRAVEL : pick < 12 ? Blocks.DIRT : pick < 14 ? Blocks.COARSE_DIRT : Blocks.SAND).defaultBlockState();
    }

    private static Planner.Column col(Planner.Column[] cols, int x0, int z0, int x, int z) {
        int a = x - x0 + 1, b = z - z0 + 1;
        if (a < 0 || b < 0 || a >= 18 || b >= 18) return null;
        return cols[a + b * 18];
    }

    /** Make sure the ground holds this water in: wherever it could run off its own channel or lake, a bank goes in. */
    private static final ThreadLocal<LongOpenHashSet> BANKS = ThreadLocal.withInitial(LongOpenHashSet::new);

    private static boolean holdIn(WorldGenLevel level, Planner.Column[] cols, int x0, int z0, LongOpenHashSet water, BlockPos at) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        Planner.Column me = col(cols, x0, z0, at.getX(), at.getZ());
        boolean still = me != null && !me.channel && (me.lake || me.springQ < 1 || me.poolQ < 1 || me.wetland || me.oasisQ < 1);
        boolean side = me != null && me.channel && me.dist > 0.75;                    // a stream's edge, not its middle
        // first: is there a side this water would pour off sideways, with no ground to bank it? then it isn't placed
        // at all and the shore comes in (water running on down its own course, over its own drop, still falls)
        if (still || side) for (Direction d : Direction.Plane.HORIZONTAL) {
            n.setWithOffset(at, d);
            if (water.contains(n.asLong()) || !spillsInto(level.getBlockState(n))) continue;
            Planner.Column c = col(cols, x0, z0, n.getX(), n.getZ());
            if (c != null && c.wet()) continue;
            if (!groundWithin(level, n, 4)) return false;
        }
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            n.setWithOffset(at, d);
            if (water.contains(n.asLong())) continue;
            BlockState st = level.getBlockState(n);
            if (!spillsInto(st)) continue;
            if (d != Direction.DOWN) {
                Planner.Column c = col(cols, x0, z0, n.getX(), n.getZ());
                if (c != null && c.wet() && (c.channel || c.lake || c.springQ < 1 || c.poolQ < 1 || c.wetland || c.oasisQ < 1) && n.getY() <= c.water + 1) continue;   // running on down its own course
                if (!groundWithin(level, n, 4)) continue;                                         // over a drop: it falls
            }
            level.setBlock(n, bankBlock(level, n), 2);
            BANKS.get().add(n.asLong());
        }
        return true;
    }

    /** A bank block mixed from what the ground round it is made of: rock gives stone and cobble, soil gives dirt and moss, grassed over in the open. */
    private static BlockState bankBlock(WorldGenLevel level, BlockPos at) {
        BlockState like = groundLike(level, at);
        if (like != null) return like;
        return mixedBank(level, at);
    }

    /**
     * The ground this bank stands in: the commonest natural block beside or under it, so a bank in a hillside is that
     * hillside (stone in stone, dirt grassed over in the open), not a band of some other block.
     */
    private static BlockState groundLike(WorldGenLevel level, BlockPos at) {
        BlockState best = null; int bestN = 0;
        BlockState[] seen = new BlockState[9]; int[] count = new int[9]; int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy >= -1; dy--) for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
            if (dy == -1 && d == Direction.DOWN) continue;
            m.set(at.getX() + d.getStepX(), at.getY() + dy + d.getStepY(), at.getZ() + d.getStepZ());
            if (BANKS.get().contains(m.asLong())) continue;
            BlockState st = level.getBlockState(m);
            if (!st.getFluidState().isEmpty() || !st.isSolidRender(level, m) || !ground(st)) continue;
            if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM)) st = Blocks.DIRT.defaultBlockState();   // grassed again below if open
            int i = 0;
            while (i < n && seen[i] != st) i++;
            if (i == n) { if (n == seen.length) continue; seen[n] = st; n++; }
            if (++count[i] > bestN) { bestN = count[i]; best = st; }
        }
        if (best == null) return null;
        boolean open = level.getBlockState(at.above()).isAir();
        if (best.is(Blocks.DIRT) && open) {
            if (level.getBlockState(at.below()).is(Blocks.GRASS_BLOCK)) level.setBlock(at.below(), Blocks.DIRT.defaultBlockState(), 2);
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
        if (best.is(Blocks.SAND) && !open && level.getBlockState(at.below()).isAir()) return Blocks.SANDSTONE.defaultBlockState();   // sand can't hang
        if (best.is(Blocks.GRAVEL) && level.getBlockState(at.below()).isAir()) return Blocks.STONE.defaultBlockState();
        return best;
    }

    /** Natural ground: soil, sand, gravel, clay, mud and the overworld's stones. */
    private static boolean ground(BlockState st) {
        return st.is(BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY) || st.is(Blocks.MUD)
                || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.TERRACOTTA) || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE)
                || st.is(Blocks.CALCITE) || st.is(Blocks.SMOOTH_BASALT) || st.is(Blocks.BASALT) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.PACKED_ICE);
    }

    /** With no natural ground beside it to copy: a mix from what's round it. */
    private static BlockState mixedBank(WorldGenLevel level, BlockPos at) {
        int rock = 0, soil = 0, sand = 0;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockState st = level.getBlockState(at.relative(d));
            if (!st.getFluidState().isEmpty() || !st.isSolidRender(level, at.relative(d))) continue;
            if (st.is(BlockTags.SAND)) sand++;
            else if (st.is(BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.MUD) || st.is(Blocks.MOSS_BLOCK) || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY)) soil++;
            else rock++;
        }
        long h = at.asLong() * 0x9E3779B97F4A7C15L;
        int pick = (int) ((h >>> 33) & 7);
        boolean open = level.getBlockState(at.above()).isAir();
        if (sand > soil && sand >= rock) return (pick < 6 ? Blocks.SAND : Blocks.SANDSTONE).defaultBlockState();
        if (soil >= rock) {
            if (open) return pick < 1 ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
            if (level.getBlockState(at.below()).is(Blocks.GRASS_BLOCK)) level.setBlock(at.below(), Blocks.DIRT.defaultBlockState(), 2);
            return (pick < 4 ? Blocks.DIRT : pick < 6 ? Blocks.COARSE_DIRT : pick < 7 ? Blocks.ROOTED_DIRT : Blocks.MOSS_BLOCK).defaultBlockState();
        }
        return (pick < 3 ? Blocks.STONE : pick < 5 ? Blocks.COBBLESTONE : pick < 7 ? Blocks.MOSSY_COBBLESTONE : Blocks.ANDESITE).defaultBlockState();
    }

    private static boolean groundWithin(WorldGenLevel level, BlockPos at, int depth) {
        for (int k = 1; k <= depth; k++) {
            if (BANKS.get().contains(at.below(k).asLong())) continue;               // a bank under it isn't ground: count down to the real ground
            BlockState st = level.getBlockState(at.below(k));
            if (st.isSolidRender(level, at.below(k)) || !st.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /** Any part of a tree, vanilla or a tree mod's own (Dynamic Trees' branches, trunk shells, leaves). */
    static boolean tree(BlockState st) {
        if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                || st.is(Blocks.BEE_NEST) || st.is(Blocks.VINE) || st.is(Blocks.PINK_PETALS)) return true;
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock());
        if (id == null || id.getNamespace().equals("minecraft")) return false;
        String path = id.getPath();
        return path.contains("branch") || path.contains("trunk") || path.contains("leaves") || path.contains("log") || path.contains("roots");
    }

    /** Soil a tree mod turned into its own block under a trunk (Dynamic Trees' rooty dirt). */
    private static boolean rooty(BlockState st) {
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock());
        return id != null && !id.getNamespace().equals("minecraft") && id.getPath().contains("rooty");
    }

    /**
     * Drowned trees: whatever grew where the water stands goes, trunk and crown, and the water closes over the spot.
     * Run when the water goes in, and again once every tree (a tree mod's too) has been placed in the chunk.
     */
    static void clearTrees(WorldGenLevel level, int x, int water, int z, BlockPos.MutableBlockPos m) {
        int y = water;
        boolean found = false;
        for (; y > water - 24; y--) {
            BlockState st = level.getBlockState(m.set(x, y, z));
            if (tree(st)) { level.setBlock(m, Blocks.AIR.defaultBlockState(), 2); found = true; continue; }
            if (rooty(st)) { level.setBlock(m, Blocks.DIRT.defaultBlockState(), 2); found = true; break; }
            if (!st.isAir() && !st.canBeReplaced() && st.getFluidState().isEmpty()) break;
        }
        if (!found) return;
        // the crown above the water, so none of it is left hanging
        for (int k = water + 1; k < water + 24; k++) {
            BlockState st = level.getBlockState(m.set(x, k, z));
            if (tree(st)) level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
            else if (!st.isAir()) break;
        }
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)          // leaves spread round the trunk
            for (int k = water + 1; k < water + 18; k++) {
                BlockState st = level.getBlockState(m.set(x + dx, k, z + dz));
                if (tree(st) && !(st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT) && st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)))
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
            }
        // and the water closes over where it stood
        for (int k = water; k > water - 24; k--) {
            BlockState st = level.getBlockState(m.set(x, k, z));
            if (!st.getFluidState().isEmpty()) continue;
            if (!fillable(st)) break;
            level.setBlock(m, Blocks.WATER.defaultBlockState(), 2);
        }
    }

    /** Somewhere water can go: empty, or a plant it takes the place of. */
    static boolean fillable(BlockState st) {
        return st.isAir() || (st.getFluidState().isEmpty() && st.canBeReplaced(Fluids.WATER));
    }

    /** Could water run into this block (air, or grass, flowers and anything else water washes out)? */
    static boolean spillsInto(BlockState st) {
        if (!st.getFluidState().isEmpty()) return false;
        return st.isAir() || st.canBeReplaced(Fluids.WATER);
    }
}
