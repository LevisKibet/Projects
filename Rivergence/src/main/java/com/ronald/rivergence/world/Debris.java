package com.ronald.rivergence.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a felled tree leaves behind, tidied away: leaves that no longer reach any wood (but not the crown of a
 * neighbouring tree, which still does), vines that hung from it, snow that lay on its crown, bee nests, cocoa and
 * anything else that can't stay where it is once the tree is gone.
 */
final class Debris {
    private Debris() {}

    private static final Direction[] ALL = Direction.values();

    static void tidy(WorldGenLevel level, LongOpenHashSet gone, int x0, int z0, int x1, int z1) {
        if (gone.isEmpty()) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // 1. leaves round what went: everything joined to it through leaves, up to the 6 steps leaves can reach from wood
        Long2IntOpenHashMap cand = new Long2IntOpenHashMap();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        for (long p : gone) { cand.put(p, 0); q.enqueue(p); }
        while (!q.isEmpty()) {
            long p = q.dequeueLong();
            int d = cand.get(p);
            if (d >= 7) continue;
            for (Direction dir : ALL) {
                long n = BlockPos.offset(p, dir);
                if (cand.containsKey(n) || !inside(n, x0, z0, x1, z1)) continue;
                BlockState st = level.getBlockState(m.set(n));
                if (!TreeClear.leaves(st) || persistent(st)) continue;
                cand.put(n, d + 1); q.enqueue(n);
            }
        }
        // those still held: within 6 leaf-steps of wood that's still standing (another tree's)
        Long2IntOpenHashMap held = new Long2IntOpenHashMap();
        for (long p : cand.keySet()) {
            if (gone.contains(p) || !inside(p, x0, z0, x1, z1)) continue;
            for (Direction dir : ALL) {
                long n = BlockPos.offset(p, dir);
                if (gone.contains(n)) continue;
                if (TreeClear.wood(level.getBlockState(m.set(n)))) { held.put(p, 1); q.enqueue(p); break; }
            }
        }
        while (!q.isEmpty()) {
            long p = q.dequeueLong();
            int d = held.get(p);
            if (d >= 6) continue;
            for (Direction dir : ALL) {
                long n = BlockPos.offset(p, dir);
                if (held.containsKey(n) || gone.contains(n) || !cand.containsKey(n)) continue;
                held.put(n, d + 1); q.enqueue(n);
            }
        }
        for (long p : cand.keySet()) {
            if (gone.contains(p) || held.containsKey(p)) continue;
            level.setBlock(m.set(p), Blocks.AIR.defaultBlockState(), 2);
            gone.add(p);
        }
        // 2. whatever hung from it or lay on it and can't stay now: vines (and the vines below them), snow, carpets, cocoa...
        LongOpenHashSet looked = new LongOpenHashSet();
        for (long p : gone) for (Direction dir : ALL) q.enqueue(BlockPos.offset(p, dir));
        int budget = 20000;
        while (!q.isEmpty() && budget-- > 0) {
            long p = q.dequeueLong();
            if (!looked.add(p) || gone.contains(p) || !inside(p, x0, z0, x1, z1)) continue;
            BlockState st = level.getBlockState(m.set(p));
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            boolean drop = !st.canSurvive(level, m)
                    || st.is(Blocks.BEE_NEST) && !touchesWood(level, p, gone, m);
            if (!drop) continue;
            level.setBlock(m.set(p), Blocks.AIR.defaultBlockState(), 2);
            gone.add(p);
            for (Direction dir : ALL) q.enqueue(BlockPos.offset(p, dir));
        }
    }

    private static boolean touchesWood(WorldGenLevel level, long p, LongOpenHashSet gone, BlockPos.MutableBlockPos m) {
        for (Direction dir : ALL) {
            long n = BlockPos.offset(p, dir);
            if (!gone.contains(n) && TreeClear.wood(level.getBlockState(m.set(n)))) return true;
        }
        return false;
    }

    private static boolean persistent(BlockState st) {
        return st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT);
    }

    private static boolean inside(long p, int x0, int z0, int x1, int z1) {
        int x = BlockPos.getX(p), z = BlockPos.getZ(p);
        return x > x0 && x < x1 && z > z0 && z < z1;            // one in from the edge, so nothing looks past what's loaded
    }
}
