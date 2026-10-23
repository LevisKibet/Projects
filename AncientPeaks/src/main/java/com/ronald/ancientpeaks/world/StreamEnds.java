package com.ronald.ancientpeaks.world;

import com.ronald.ancientpeaks.AncientPeaks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * With Chronicles of Logres installed, no stream crosses the island plains (they hold its village and castle), so a stream that runs down to them
 * would stop dead at their edge and spill across the grass. Here, after all other decoration:
 *   - where a stream flows INTO the plains it drops into a mossy swallow hole and goes underground;
 *   - wherever else stream water touches lower plains ground, the bank is raised so it can't spill;
 *   - any water that already spilled onto the plains (above sea level) is taken away.
 */
public final class StreamEnds {
    private StreamEnds() {}

    static final ResourceKey<Biome> PLAINS = ResourceKey.create(Registries.BIOME, new ResourceLocation(AncientPeaks.MODID, "highland_plains"));
    static final int SEA = 63;

    // ------------------------------------------------------------------ Streams Reflowing, looked up lazily (optional)
    private static boolean looked;
    private static Method active, plan, flowX, flowZ, inChannel;

    private static synchronized void look() {
        if (looked) return;
        looked = true;
        try {
            Class<?> wg = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
            Class<?> eng = Class.forName("dev.streamsreflowing.core.RiverEngine");
            Class<?> cp = Class.forName("dev.streamsreflowing.core.compose.ColumnPlan");
            active = wg.getMethod("active");
            plan = eng.getMethod("plan", int.class, int.class);
            flowX = cp.getMethod("flowDirX");
            flowZ = cp.getMethod("flowDirZ");
            inChannel = cp.getMethod("inChannel");
        } catch (Throwable t) {
            active = null;
        }
    }

    /** Downstream direction of the stream at (x, z), or null when unknown. */
    private static double[] flow(Object engine, int x, int z) {
        if (engine == null) return null;
        try {
            Object p = plan.invoke(engine, x, z);
            if (!(Boolean) inChannel.invoke(p)) return null;
            double fx = (Double) flowX.invoke(p), fz = (Double) flowZ.invoke(p), n = Math.hypot(fx, fz);
            return n < 1e-6 ? null : new double[]{fx / n, fz / n};
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the pass
    public static void run(WorldGenLevel level, ChunkPos cp) {
        if (Island.PLAINS_OPEN) return;                            // no town on the plains: streams may cross them
        IslandSampler s = IslandSampler.ACTIVE;
        if (s == null || s.islandAt(cp.getMiddleBlockX(), cp.getMiddleBlockZ()) == null) return;
        look();
        Object engine = null;
        try { if (active != null) engine = active.invoke(null); } catch (Throwable ignored) { }

        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<int[]> holes = new ArrayList<>();                     // x, z, water surface y
        Set<Long> dams = new HashSet<>();                          // plains columns that need a lip, packed x/z
        List<Integer> damY = new ArrayList<>();
        List<int[]> damList = new ArrayList<>();

        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = cp.getMinBlockX() + dx, z = cp.getMinBlockZ() + dz;
            int top = surface(level, m, x, z);
            if (top <= SEA) continue;
            boolean plains = isPlains(level, m, x, top, z);
            boolean wet = level.getFluidState(m.set(x, top, z)).is(FluidTags.WATER);
            if (plains) {
                // spilled water on the plains (the sea and the tarns are other biomes): take it away
                if (wet) drain(level, m, x, top, z);
                continue;
            }
            if (!wet) continue;
            // stream water: does it touch plains ground it could spill onto?
            double[] f = null; boolean looked = false; boolean hole = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = x + d.getStepX(), nz = z + d.getStepZ();
                int nTop = surface(level, m, nx, nz);
                if (!isPlains(level, m, nx, Math.max(nTop, SEA), nz)) continue;
                if (!looked) { f = flow(engine, x, z); looked = true; }
                if (f != null && f[0] * d.getStepX() + f[1] * d.getStepZ() > 0.5) hole = true;
                if (nTop <= top) {
                    // the lip: this column and its neighbour on the plains side
                    for (int k = 1; k <= 2; k++) {
                        int lx = x + d.getStepX() * k, lz = z + d.getStepZ() * k;
                        if (dams.add(((long) lx << 32) ^ (lz & 0xffffffffL))) { damList.add(new int[]{lx, lz}); damY.add(top + 1); }
                    }
                }
            }
            if (hole) holes.add(new int[]{x, z, top});
        }

        for (int i = 0; i < damList.size(); i++) dam(level, m, damList.get(i)[0], damList.get(i)[1], damY.get(i));
        for (int[] h : holes) swallowHole(level, m, h[0], h[1], h[2]);
        // the island's own streams that reach the plains: each drops into a swallow hole where it ends
        Island is = s.islandAt(cp.getMiddleBlockX(), cp.getMiddleBlockZ());
        if (is != null) for (double[] k : is.sinksWorld()) {
            int x = (int) Math.floor(k[0]), z = (int) Math.floor(k[1]);
            if ((x >> 4) != cp.x || (z >> 4) != cp.z) continue;
            int wy = (int) Math.floor(k[2]);
            int top = surface(level, m, x, z);
            swallowHole(level, m, x, z, Math.min(wy, top));
        }
    }

    /** y of the top non-air block in the column (water counts). */
    private static int surface(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z) {
        int y = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1, level.getMaxBuildHeight() - 1);
        int floor = level.getMinBuildHeight();
        while (y > floor) {
            BlockState st = level.getBlockState(m.set(x, y, z));
            if (!st.isAir() && !(st.canBeReplaced() && st.getFluidState().isEmpty())) return y;
            y--;
        }
        return floor;
    }

    private static boolean isPlains(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int y, int z) {
        return level.getBiome(m.set(x, y, z)).is(PLAINS);
    }

    private static void drain(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int top, int z) {
        for (int y = top; y > SEA; y--) {
            if (!level.getFluidState(m.set(x, y, z)).is(FluidTags.WATER)) {
                BlockState st = level.getBlockState(m);
                if (st.is(Blocks.DIRT) || st.is(Blocks.MUD) || st.is(Blocks.CLAY) || st.is(Blocks.GRAVEL) || st.is(Blocks.SAND))
                    level.setBlock(m, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                return;
            }
            level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** Raise plains ground beside a stream to one above its water, so the water stays in its bed. */
    private static void dam(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, int y) {
        int top = surface(level, m, x, z);
        if (level.getFluidState(m.set(x, top, z)).is(FluidTags.WATER) && !isPlains(level, m, x, top, z)) return;   // the stream itself
        if (top >= y) return;
        for (int yy = top; yy <= y; yy++) {
            m.set(x, yy, z);
            BlockState st = level.getBlockState(m);
            if (yy == top && !st.getFluidState().is(FluidTags.WATER) && !st.isAir()) continue;    // keep the ground block
            level.setBlock(m, (yy == y ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2);
        }
        BlockState above = level.getBlockState(m.set(x, y + 1, z));
        if (!above.isAir() && above.canBeReplaced()) level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
        else if (level.getFluidState(m).is(FluidTags.WATER)) level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
    }

    /** The stream falls into a narrow mossy shaft and on down into the dark, landing in a small pool far below. */
    private static void swallowHole(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, int wy) {
        long h = (x * 341873128712L) ^ (z * 132897987541L);
        int depth = 12 + (int) Math.floorMod(h, 7L);
        int bottom = Math.max(level.getMinBuildHeight() + 4, wy - depth);
        for (int y = wy; y > bottom + 1; y--) level.setBlock(m.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(m.set(x, bottom + 1, z), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(m.set(x, bottom, z), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(m.set(x, bottom - 1, z), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
        // line the shaft wherever it opened onto air (a cave), mossy near the top
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            for (int y = wy - 1; y >= bottom; y--) {
                m.set(nx, y, nz);
                BlockState st = level.getBlockState(m);
                if (!st.getFluidState().isEmpty()) continue;                             // the stream feeding in
                if (st.isAir() || st.canBeReplaced()) {
                    boolean mossy = Math.floorMod(h + y * 31L + nx * 7L + nz, 3L) != 0 && y > wy - 6;
                    level.setBlock(m, (mossy ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).defaultBlockState(), 2);
                }
            }
            // let the water upstream start falling in
            m.set(nx, wy, nz);
            if (level.getFluidState(m).is(FluidTags.WATER)) level.scheduleTick(m.immutable(), Fluids.WATER, 2);
        }
        // moss and a little hanging growth round the lip
        for (Direction d : Direction.Plane.HORIZONTAL) {
            m.set(x + d.getStepX(), wy, z + d.getStepZ());
            BlockState st = level.getBlockState(m);
            if (st.is(Blocks.STONE) || st.is(Blocks.DIRT) || st.is(Blocks.COBBLESTONE)) level.setBlock(m, Blocks.MOSS_BLOCK.defaultBlockState(), 2);
        }
        level.scheduleTick(new BlockPos(x, bottom + 1, z), Fluids.WATER, 2);
    }
}
