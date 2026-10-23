package com.ronald.chroma.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Counts block changes per chunk section on the client, so cached work about a place (the light a line throws round
 * it) can tell cheaply whether anything there has changed since, instead of looking at every block again.
 */
public final class BlockChanges {
    private BlockChanges() {}

    private static final Long2IntOpenHashMap VERSION = new Long2IntOpenHashMap();

    /** Called whenever the client sees a block change (from a mixin into LevelRenderer.blockChanged). */
    public static void changed(BlockPos pos) {
        VERSION.addTo(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4), 1);
    }

    /** A chunk came into view: anything worked out before it was there may be missing its blocks. */
    public static void chunkLoaded(int cx, int cz, int minSection, int maxSection) {
        for (int sy = minSection; sy <= maxSection; sy++) VERSION.addTo(SectionPos.asLong(cx, sy, cz), 1);
    }

    /** A number that changes whenever any block in these sections does. */
    static long stamp(long[] sections) {
        long s = 0;
        for (long k : sections) s = s * 31 + VERSION.get(k);
        return s;
    }

    static void clear() { VERSION.clear(); }
}
