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
        // the columns of the chunk and one round it
        Planner.Column[] cols = new Planner.Column[18 * 18];
        boolean any = false;
        for (int a = 0; a < 18; a++) for (int b = 0; b < 18; b++) {
            Planner.Column c = new Planner.Column();
            planner.column(x0 - 1 + a, z0 - 1 + b, c, true);
            cols[a + b * 18] = c;
            if (c.wet()) any = true;
        }
        if (!any) { SpringDecor.run(level, cols, new BlockPos.MutableBlockPos()); WallDecor.run(level, cols, new BlockPos.MutableBlockPos()); return; }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LongOpenHashSet all = new LongOpenHashSet();
        List<BlockPos> mine = new ArrayList<>();
        for (int a = 0; a < 18; a++) for (int b = 0; b < 18; b++) {
            Planner.Column c = cols[a + b * 18];
            if (!c.wet()) continue;
            boolean inside = a >= 1 && a <= 16 && b >= 1 && b <= 16;
            int x = c.x, z = c.z;
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
        mine.sort((p, q) -> Integer.compare(p.getY(), q.getY()));
        for (BlockPos p : mine) {
            if (!fillable(level.getBlockState(p))) continue;
            if (p.getY() > SEA_TOP) holdIn(level, cols, x0, z0, all, p);
            else if (spillsInto(level.getBlockState(p.below())) && !all.contains(p.below().asLong())) continue;
            level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
            placed.add(p);
        }
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
    private static void holdIn(WorldGenLevel level, Planner.Column[] cols, int x0, int z0, LongOpenHashSet water, BlockPos at) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            n.setWithOffset(at, d);
            if (water.contains(n.asLong())) continue;
            BlockState st = level.getBlockState(n);
            if (!spillsInto(st)) continue;
            if (d != Direction.DOWN) {
                Planner.Column c = col(cols, x0, z0, n.getX(), n.getZ());
                if (c != null && c.wet() && (c.channel || c.lake || c.springQ < 1 || c.poolQ < 1 || c.wetland || c.oasisQ < 1) && n.getY() <= c.water + 1) continue;   // running on down its own course
                if (!groundWithin(level, n, 2)) continue;                                         // over a drop: it falls
            }
            level.setBlock(n, bankBlock(level, n), 2);
        }
    }

    /** A bank block mixed from what the ground round it is made of: rock gives stone and cobble, soil gives dirt and moss, grassed over in the open. */
    private static BlockState bankBlock(WorldGenLevel level, BlockPos at) {
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
            BlockState st = level.getBlockState(at.below(k));
            if (st.isSolidRender(level, at.below(k)) || !st.getFluidState().isEmpty()) return true;
        }
        return false;
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
