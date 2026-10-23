package com.ronald.rivergence.plan;

import com.ronald.rivergence.Rivergence;
import com.ronald.rivergence.RivergenceConfig;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

/**
 * The water plans of one world, region by region: worked out in the background the first time anything nearby asks,
 * and kept for a while. Answers, for any block column, what the water does there (see {@link Column}).
 */
public final class Planner {
    private static final ExecutorService REGIONS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Rivergence planner");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final ForkJoinPool SAMPLERS = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
    private static final int KEEP = 24;

    private final RandomState rs;
    /** The world's biomes (set once the generator is known; used to find vanilla rivers and swamps). */
    private volatile net.minecraft.world.level.biome.BiomeSource biomes;

    public void useBiomes(net.minecraft.world.level.biome.BiomeSource b) { if (biomes == null) biomes = b; }

    /** Per 4x4 column (quart coords): 1 vanilla river, 2 ocean, 4 swamp. */
    private int biomeFlags(int qx, int qz) {
        var b = biomes;
        if (b == null) return 0;
        var h = b.getNoiseBiome(qx, 16, qz, rs.sampler());
        if (h.is(net.minecraft.tags.BiomeTags.IS_RIVER)) return 1;
        if (h.is(net.minecraft.tags.BiomeTags.IS_OCEAN)) return 2;
        if (h.is(net.minecraftforge.common.Tags.Biomes.IS_SWAMP)) return 4;
        return 0;
    }
    private final int minY, maxY;
    private final Map<Long, CompletableFuture<RegionPlan>> plans = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<RegionPlan>> e) { return size() > KEEP; }
    };

    public Planner(RandomState rs, int minY, int maxY) { this.rs = rs; this.minY = minY; this.maxY = maxY; }

    private CompletableFuture<RegionPlan> future(int rx, int rz) {
        long k = RegionPlan.key(rx, rz);
        synchronized (plans) {
            CompletableFuture<RegionPlan> f = plans.get(k);
            if (f == null) {
                f = CompletableFuture.supplyAsync(() -> {
                    try {
                        RegionPlan p = new RegionPlan(rx, rz, () -> new FastHeights(rs, minY, maxY), biomes == null ? null : this::biomeFlags, SAMPLERS);
                        Rivergence.LOGGER.debug("Planned the water of region {},{} in {} ms ({} channels, {} lakes)", rx, rz, p.nanos / 1000000, p.segments.size(), p.lakes.size());
                        return p;
                    } catch (Throwable t) {
                        Rivergence.LOGGER.error("Couldn't plan the water of region {},{}", rx, rz, t);
                        return null;
                    }
                }, REGIONS);
                plans.put(k, f);
            }
            return f;
        }
    }

    /** The plan of the region holding this block column; null if it isn't ready and we may not wait. */
    public RegionPlan region(int x, int z, boolean wait) {
        int rx = x >> RegionPlan.SHIFT, rz = z >> RegionPlan.SHIFT;
        CompletableFuture<RegionPlan> f = future(rx, rz);
        // getting near the region's edge: start on the neighbour
        int ox = x - (rx << RegionPlan.SHIFT), oz = z - (rz << RegionPlan.SHIFT), near = 512;
        if (ox < near) future(rx - 1, rz); else if (ox >= RegionPlan.SIZE - near) future(rx + 1, rz);
        if (oz < near) future(rx, rz - 1); else if (oz >= RegionPlan.SIZE - near) future(rx, rz + 1);
        if (!wait && !f.isDone()) return null;
        return f.join();
    }

    /** What the water does in this column (out is filled in; out.plan is null when nothing's known). */
    public void column(int x, int z, Column out, boolean wait) {
        out.reset(x, z);
        if (excluded(x, z)) return;
        RegionPlan p = region(x, z, wait);
        if (p == null) return;
        out.plan = p;
        double px = x + 0.5, pz = z + 0.5;
        // vanilla's river valleys are filled in: the only rivers are ours (which run down the same valleys)
        if (RivergenceConfig.replaceVanillaRivers()) {
            double v = p.fillAt(px, pz);
            // soft, irregular rises of a block here and there, so the filled ground isn't flat
            if (!Double.isNaN(v)) out.fill = v + (v >= 63 && valueNoise(x / 19.0, z / 19.0, 31) > 0.25 ? 1 : 0);
        }
        double best = Double.MAX_VALUE;
        for (int si : p.segmentsAt(x, z)) {
            RegionPlan.Segment s = p.segments.get(si);
            if (x < s.minX || x > s.maxX || z < s.minZ || z > s.maxZ) continue;
            // the nearest point on the curve
            double bd = Double.MAX_VALUE, bl = 0, bw = s.hw;
            for (int i = 0; i + 1 < s.x.length; i++) {
                double ax = s.x[i], az = s.z[i], dx = s.x[i + 1] - ax, dz = s.z[i + 1] - az;
                double l2 = dx * dx + dz * dz, t = l2 < 1e-9 ? 0 : ((px - ax) * dx + (pz - az) * dz) / l2;
                t = t < 0 ? 0 : t > 1 ? 1 : t;
                double ex = ax + dx * t - px, ez = az + dz * t - pz, dd = ex * ex + ez * ez;
                if (dd < bd) { bd = dd; bl = t < 0.5 ? s.level[i] : s.level[i + 1]; bw = s.w[i] + (s.w[i + 1] - s.w[i]) * t; }
            }
            double dist = Math.sqrt(bd), t = dist - bw;
            if (t > RegionPlan.REACH) continue;
            double lv = Math.floor(bl), depth = Math.min(8, 1 + 0.35 * bw);
            double top = t < 0 ? lv - depth * (1 - (dist / bw) * (dist / bw)) : lv + 0.9 * t + 0.06 * t * t;
            out.carve = Math.min(out.carve, top);
            if (t < 0) out.bed = Math.min(out.bed, top);
            // the water: from the channel this column lies deepest in
            double rel = dist / bw;
            if (t < 0 && rel < best) {
                best = rel;
                out.water = (int) lv; out.channel = true; out.hw = (float) bw; out.dist = (float) dist;
            }
        }
        for (int si : p.springsAt(x, z)) spring(p.springs.get(si), x, z, out);
        for (int li : p.lakesAt(x, z)) {
            RegionPlan.Lake l = p.lakes.get(li);
            double sd = l.sd(px, pz);
            out.lakeSd = (float) Math.max(out.lakeSd, sd);
            if (sd > 0) {
                out.water = l.level; out.lake = true;
                // the basin: shelving from the shore, deepest toward the middle, a little uneven
                double wob = RegionPlan.jit(x >> 2, z >> 2, 7) * 0.6;
                double bed = l.level - Math.min(l.depth, 0.8 + sd * 0.3) + wob;
                out.carve = Math.min(out.carve, bed);
                out.bed = Math.min(out.bed, bed);
            }
        }
    }

    /**
     * A spring: inside it, dotted pools (water one or two deep) between hummocks of mud and moss flush with the water;
     * round it, the little hollow it sits in, with a low rim except where the stream runs out.
     */
    private static void spring(RegionPlan.Spring sp, int x, int z, Column out) {
        double dx = x + 0.5 - sp.x, dz = z + 0.5 - sp.z, d = Math.hypot(dx, dz);
        double a = Math.atan2(dz, dx);
        double shape = 1 + 0.22 * Math.sin(3 * a + sp.seed % 7) + 0.12 * Math.sin(5 * a + sp.seed % 11);
        double q = d / (sp.r * shape);
        if (q >= 1.9 || q >= out.springQ) return;
        out.springQ = (float) q;
        out.spring = sp;
        double toward = d < 1e-6 ? 1 : (dx * sp.dx + dz * sp.dz) / d;            // 1 = the side the stream leaves by
        int L = sp.level;
        if (q < 1) {
            double blot = valueNoise(x / 2.2 + (sp.seed & 255), z / 2.2, sp.seed) + 0.5 * valueNoise(x / 1.3, z / 1.3 - (sp.seed & 127), sp.seed + 1);
            boolean wet = q < 0.35 || blot > -0.1 - 0.35 * (1 - q);
            double bed = !wet ? L : q < 0.55 ? L - 2 : L - 1;
            out.carve = Math.min(out.carve, bed);
            out.raise = Math.max(out.raise, bed);
            if (wet) { out.water = Math.max(out.water == Column.DRY ? L : out.water, L); out.bed = Math.min(out.bed, bed); }
        } else {
            out.carve = Math.min(out.carve, L + 1.5 + (q - 1) * 5);
            if (toward < 0.85 && q < 1.45) out.raise = Math.max(out.raise, L + 1);   // a low rim, open at the outlet
            else if (toward >= 0.85) out.carve = Math.min(out.carve, L + 0.5);
        }
    }

    /** Smooth noise in -1..1 from hashed lattice values. */
    static double valueNoise(double x, double z, long seed) {
        int i = (int) Math.floor(x), j = (int) Math.floor(z);
        double fx = x - i, fz = z - j;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double a = RegionPlan.jit(i, j, (int) seed), b = RegionPlan.jit(i + 1, j, (int) seed), c = RegionPlan.jit(i, j + 1, (int) seed), e = RegionPlan.jit(i + 1, j + 1, (int) seed);
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + e * fx) * fz;
    }

    // ------------------------------------------------------------------ land other mods look after

    private volatile boolean looked;
    private Object ruins;
    private java.lang.reflect.Method islandAt;

    /** Ruins of Logres' island has water of its own: none of ours there. */
    private boolean excluded(int x, int z) {
        if (!looked) {
            synchronized (this) {
                if (!looked) {
                    try {
                        Object s = rs.getClass().getMethod("ancientpeaks$sampler").invoke(rs);
                        if (s != null) { islandAt = s.getClass().getMethod("islandAt", int.class, int.class); ruins = s; }
                    } catch (Throwable ignored) { }
                    looked = true;
                }
            }
        }
        if (ruins == null) return false;
        try { return islandAt.invoke(ruins, x, z) != null; } catch (Throwable t) { return false; }
    }

    /** The answer for one block column. */
    public static final class Column {
        public int x, z;
        public RegionPlan plan;
        /** The highest the ground may stand here (carved down to it), or +infinity. */
        public double carve;
        /** The bed under the water itself (no banks), or +infinity: kept clear even where a structure built up the ground. */
        public double bed;
        /** The water's surface (the top water block's y), or {@link #DRY}. */
        public int water;
        public boolean channel, lake;
        public float hw, dist, lakeSd, springQ;
        /** The lowest the ground may stand here (built up to it, a few blocks at most), or -infinity. */
        public double raise;
        /** Hollows below this are filled in (a vanilla river's valley), or -infinity. */
        public double fill;
        public RegionPlan.Spring spring;
        public static final int DRY = Integer.MIN_VALUE;

        public void reset(int x, int z) {
            this.x = x; this.z = z; plan = null; carve = Double.POSITIVE_INFINITY; bed = Double.POSITIVE_INFINITY; water = DRY;
            channel = false; lake = false; hw = 0; dist = 0; lakeSd = -99; springQ = 9; spring = null; raise = Double.NEGATIVE_INFINITY; fill = Double.NEGATIVE_INFINITY;
        }
        public boolean wet() { return water != DRY; }
    }
}
