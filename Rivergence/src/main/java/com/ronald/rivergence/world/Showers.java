package com.ronald.rivergence.world;

import com.ronald.rivergence.RivergenceConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where the passing local showers are. Worked out the same way on the server and on every client, from the world's
 * clock, its seed-free cell grid and the land itself, so the rain you see is real rain: it puts out fires, douses
 * anything burning and fills cauldrons - wherever it falls, not only round the player who sees it.
 */
public final class Showers {
    private Showers() {}

    public static final int CELL = 320, TIME = 4800;

    /** One shower: where, how wide, how heavy just now (it builds and dies away over a few minutes), the ground under it, its life so far. */
    public record Shower(float x, float z, float radius, float strength, float ground, long seed, float phase) {}

    private record Site(long window, boolean ok, float x, float z, float r, int ground, long retry) {}

    private static final Map<Level, Map<Long, Site>> SITES = new WeakHashMap<>();

    static double hash(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** The showers within reach of a spot just now (none while the whole world is rained on anyway). */
    public static List<Shower> around(Level level, double ax, double az, float reach) {
        List<Shower> out = new ArrayList<>();
        if (!RivergenceConfig.weather()) return out;
        long now = level.getGameTime();
        int c0x = Math.floorDiv((int) (ax - reach), CELL), c1x = Math.floorDiv((int) (ax + reach), CELL);
        int c0z = Math.floorDiv((int) (az - reach), CELL), c1z = Math.floorDiv((int) (az + reach), CELL);
        for (int cx = c0x; cx <= c1x; cx++) for (int cz = c0z; cz <= c1z; cz++) {
            Shower s = at(level, cx, cz, now);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static Shower at(Level level, int cx, int cz, long now) {
        // each cell keeps its own clock, so showers don't all come and go together
        long offset = (long) (hash(cx, cz, 91) * TIME);
        long w = Math.floorDiv(now + offset, TIME);
        double roll = hash(cx, cz, w);
        if (roll > 0.38) return null;
        float ph = Math.floorMod(now + offset, TIME) / (float) TIME;
        float env = Mth.clamp(ph / 0.15f, 0, 1) * Mth.clamp((1 - ph) / 0.15f, 0, 1);
        if (env <= 0) return null;
        Site site = site(level, cx, cz, w, roll, now);
        if (site == null || !site.ok) return null;
        return new Shower(site.x, site.z, site.r, env, site.ground, cx * 341873128712L + cz * 132897987541L + w, ph);
    }

    /** Whether this cell's shower happens this time round: over big water, and (more often) over high ground. Worked out once. */
    private static Site site(Level level, int cx, int cz, long w, double roll, long now) {
        Map<Long, Site> sites;
        synchronized (SITES) { sites = SITES.computeIfAbsent(level, k -> new HashMap<>()); }
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        synchronized (sites) {
            Site s = sites.get(key);
            if (s != null && s.window == w && (s.retry == 0 || now < s.retry)) return s.retry == 0 ? s : null;
            float x = cx * CELL + CELL / 2f + (float) (hash(cx, cz, w + 3) - 0.5) * 110;
            float z = cz * CELL + CELL / 2f + (float) (hash(cx, cz, w + 5) - 0.5) * 110;
            int gx = (int) x, gz = (int) z;
            int water = 0, seen = 0;
            for (int i = -3; i <= 3; i++) for (int j = -3; j <= 3; j++) {
                int sx = gx + i * 40, sz = gz + j * 40;
                if (!level.hasChunk(sx >> 4, sz >> 4)) continue;
                seen++;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz) - 1;
                if (level.getFluidState(new BlockPos(sx, top, sz)).is(FluidTags.WATER)) water++;
            }
            if (seen < 12 || !level.hasChunk(gx >> 4, gz >> 4)) {
                // not enough of the land is loaded to tell: undecided, look again in a little while
                sites.put(key, new Site(w, false, x, z, 0, 0, now + 100));
                return null;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, gx, gz);
            boolean wet = water / (float) seen >= 0.3f;
            boolean high = ground >= 100;
            boolean ok = (wet && roll <= 0.2) || (high && roll <= 0.2 + Mth.clamp((ground - 100) / 60f, 0, 1) * 0.18);
            float r = 120 + (float) hash(cx, cz, w + 9) * 70;
            if (sites.size() > 512) sites.clear();
            Site made = new Site(w, ok, x, z, r, ground, 0);
            sites.put(key, made);
            return made;
        }
    }

    /** How hard a shower rains here (0..1). */
    public static float strengthAt(Level level, double x, double z) {
        float best = 0;
        for (Shower s : around(level, x, z, 0)) {
            float d = (float) Math.hypot(s.x - x, s.z - z) / s.radius;
            best = Math.max(best, s.strength * Mth.clamp((1 - d) * 2.5f, 0, 1));
        }
        return best;
    }

    /** Rain from a shower falling on this block, as vanilla's rain would: open sky above, and a biome where it rains. */
    public static boolean rainingAt(Level level, BlockPos pos) {
        if (strengthAt(level, pos.getX() + 0.5, pos.getZ() + 0.5) < 0.3f) return false;
        if (!level.canSeeSky(pos)) return false;
        if (level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) return false;
        return level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
    }
}
