package com.ronald.rivergence.world;

import com.ronald.rivergence.plan.Planner;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Trees standing in our water (a lake drowned the wood they grew in, or a tree mod planted one after the water went
 * in): each one rooted in the water comes out whole, branches and crown, and the water closes over where it stood.
 * Trees on the shore stay; only leaves trailing under the surface are trimmed. Run once every tree in a chunk has been
 * placed, over that chunk and the ones round it (trees reach in from their neighbours).
 */
final class TreeClear {
    private TreeClear() {}

    static boolean wood(BlockState st) {
        if (st.is(BlockTags.LOGS)) return true;
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock());
        if (id == null || id.getNamespace().equals("minecraft")) return false;
        String p = id.getPath();
        return p.contains("branch") || p.contains("trunk") || (p.contains("log") && !p.contains("leaves"));
    }

    static boolean leaves(BlockState st) {
        if (st.is(BlockTags.LEAVES) || st.getBlock() instanceof LeavesBlock) return true;
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock());
        return id != null && !id.getNamespace().equals("minecraft") && id.getPath().contains("leaves");
    }

    private static boolean ours(BlockState st) {
        return st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT);   // the leaves we lay in the water
    }

    private static boolean rooty(BlockState st) {
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(st.getBlock());
        return id != null && !id.getNamespace().equals("minecraft") && id.getPath().contains("rooty");
    }

    static void run(WorldGenLevel level, Planner planner, ChunkPos cp) {
        int x0 = cp.getMinBlockX() - 16, z0 = cp.getMinBlockZ() - 16, x1 = x0 + 47, z1 = z0 + 47;
        int n = 48;
        int[] water = new int[n * n];
        Planner.Column c = new Planner.Column();
        boolean any = false;
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) {
            planner.column(x0 + a, z0 + b, c, true);
            boolean wet = c.wet() && c.water > WaterPlacer.SEA_TOP && c.wall == null;
            water[a + b * n] = wet ? c.water : Integer.MIN_VALUE;
            any |= wet;
        }
        if (!any) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        LongOpenHashSet gone = new LongOpenHashSet();
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) {
            int w = water[a + b * n];
            if (w == Integer.MIN_VALUE) continue;
            int x = x0 + a, z = z0 + b;
            for (int y = w; y > w - 24; y--) {
                BlockState st = level.getBlockState(m.set(x, y, z));
                if (wood(st)) { fell(level, m.immutable(), x0, z0, x1, z1, gone); continue; }
                if (leaves(st) && !ours(st)) { level.setBlock(m, Blocks.AIR.defaultBlockState(), 2); gone.add(m.asLong()); continue; }
                if (rooty(st)) { level.setBlock(m, Blocks.DIRT.defaultBlockState(), 2); break; }
                if (!st.isAir() && st.getFluidState().isEmpty() && !st.canBeReplaced()) break;
            }
        }
        if (gone.isEmpty()) return;
        // the water closes over: in every column something was cleared from, down from the water to the ground
        boolean[] touched = new boolean[n * n];
        for (long p : gone) {
            int a = BlockPos.getX(p) - x0, b = BlockPos.getZ(p) - z0;
            if (a >= 0 && b >= 0 && a < n && b < n) touched[a + b * n] = true;
        }
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) {
            int w = water[a + b * n];
            if (w == Integer.MIN_VALUE || !touched[a + b * n]) continue;
            int x = x0 + a, z = z0 + b;
            for (int y = w; y > w - 24; y--) {
                BlockState st = level.getBlockState(m.set(x, y, z));
                if (!st.getFluidState().isEmpty()) continue;
                if (!WaterPlacer.fillable(st)) break;
                level.setBlock(m, Blocks.WATER.defaultBlockState(), 2);
            }
        }
    }

    /** The whole tree from a piece of its wood: all the wood joined to it, then its leaves. */
    private static void fell(WorldGenLevel level, BlockPos start, int x0, int z0, int x1, int z1, LongOpenHashSet gone) {
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        LongOpenHashSet seen = new LongOpenHashSet();
        q.enqueue(start.asLong()); seen.add(start.asLong());
        java.util.List<BlockPos> woods = new java.util.ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!q.isEmpty() && woods.size() < 400) {
            long p = q.dequeueLong();
            BlockPos at = BlockPos.of(p);
            woods.add(at);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                if (x < x0 || x > x1 || z < z0 || z > z1 || Math.abs(x - start.getX()) > 12 || Math.abs(z - start.getZ()) > 12) continue;
                long k = BlockPos.asLong(x, at.getY() + dy, z);
                if (!seen.add(k)) continue;
                if (wood(level.getBlockState(m.set(x, at.getY() + dy, z)))) q.enqueue(k);
            }
        }
        for (BlockPos p : woods) { level.setBlock(p, Blocks.AIR.defaultBlockState(), 2); gone.add(p.asLong()); }
        // its leaves: those within a few blocks of its wood (a neighbour's crown just touching is left mostly whole)
        for (BlockPos p : woods)
            for (int dx = -3; dx <= 3; dx++) for (int dy = -2; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) {
                int x = p.getX() + dx, z = p.getZ() + dz;
                if (x < x0 || x > x1 || z < z0 || z > z1) continue;
                BlockState st = level.getBlockState(m.set(x, p.getY() + dy, z));
                if (leaves(st) && !ours(st)) { level.setBlock(m, Blocks.AIR.defaultBlockState(), 2); gone.add(m.asLong()); }
            }
    }
}
