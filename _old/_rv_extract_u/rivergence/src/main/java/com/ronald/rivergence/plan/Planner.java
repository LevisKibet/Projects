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
    private final HeightCache heights = new HeightCache();
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
        if (h.is(net.minecraftforge.common.Tags.Biomes.IS_DESERT) || h.is(net.minecraft.tags.BiomeTags.IS_BADLANDS)
                || h.is(net.minecraftforge.common.Tags.Biomes.IS_SANDY) && h.is(net.minecraftforge.common.Tags.Biomes.IS_HOT)) return 8;
        if ((h.is(net.minecraft.tags.BiomeTags.IS_FOREST) || h.is(net.minecraft.tags.BiomeTags.IS_TAIGA)) && !h.is(net.minecraft.tags.BiomeTags.IS_JUNGLE)) return 16;
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
                        RegionPlan p = new RegionPlan(rx, rz, () -> new FastHeights(rs, minY, maxY), biomes == null ? null : this::biomeFlags, SAMPLERS, heights);
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
            double bd = Double.MAX_VALUE, bl = 0, bw = s.hw, bu = 0;
            for (int i = 0; i + 1 < s.x.length; i++) {
                double ax = s.x[i], az = s.z[i], dx = s.x[i + 1] - ax, dz = s.z[i + 1] - az;
                double l2 = dx * dx + dz * dz, t = l2 < 1e-9 ? 0 : ((px - ax) * dx + (pz - az) * dz) / l2;
                t = t < 0 ? 0 : t > 1 ? 1 : t;
                double ex = ax + dx * t - px, ez = az + dz * t - pz, dd = ex * ex + ez * ez;
                if (dd < bd) {
                    bd = dd; bl = t < 0.5 ? s.level[i] : s.level[i + 1]; bw = s.w[i] + (s.w[i + 1] - s.w[i]) * t; bu = s.level[Math.max(0, i - 3)];
                    if (s.level[i] - s.level[i + 1] >= 2) bw = Math.min(bw, RegionPlan.lipHalf((float) bw));   // over a step: a narrow lip
                }
            }
            double dist = Math.sqrt(bd), t = dist - bw;
            if (t > RegionPlan.REACH) continue;
            double lv = Math.floor(bl), depth = Math.min(8, 1 + 0.35 * bw);
            // the banks stand as high as the water just upstream, so where it steps down the falling water stays in its bed
            double bank = Math.max(lv, Math.floor(bu));
            double top = t < 0 ? lv - depth * (1 - (dist / bw) * (dist / bw)) : bank + 0.9 * t + 0.06 * t * t;
            out.carve = Math.min(out.carve, top);
            if (t < 0) out.bed = Math.min(out.bed, top);
            if (t < out.edge) { out.edge = (float) t; out.nearLevel = (int) lv; out.nearWidth = (float) bw; }
            // the water: from the channel this column lies deepest in
            double rel = dist / bw;
            if (t < 0 && rel < best) {
                best = rel;
                out.water = (int) lv; out.channel = true; out.hw = (float) bw; out.dist = (float) dist;
            }
        }
        for (int si : p.springsAt(x, z)) spring(p.springs.get(si), x, z, out);
        for (int pi : p.poolsAt(x, z)) pool(p.pools.get(pi), x, z, out);
        for (int di : p.deltasAt(x, z)) delta(p.deltas.get(di), x, z, out);
        for (int bi : p.baysAt(x, z)) bay(p.bays.get(bi), x, z, out);
        for (int oi : p.oasesAt(x, z)) oasis(p.oases.get(oi), x, z, out);
        for (int bi : p.beaversAt(x, z)) beaverPond(p.beaverPonds.get(bi), x, z, out);
        for (int wi : p.wallsAt(x, z)) wall(p.walls.get(wi), x, z, out);
        if (!out.wet() && out.edge > 1.5) {
            double[] wl = p.wetlandAt(px, pz);
            // not where the land climbs well above the water (no pits cut into hillsides)
            if (wl != null) {
                double hEst = p.heightAt(px, pz);
                double m = wl[1] * Math.max(0, Math.min(1, (wl[0] + 5 - hEst) / 3));
                if (m > 0) wetland((int) wl[0], m, hEst <= wl[0] + 2.5, x, z, out);
            }
        }
        for (int li : p.lakesAt(x, z)) {
            RegionPlan.Lake l = p.lakes.get(li);
            double sd = l.sd(px, pz);
            out.lakeSd = (float) Math.max(out.lakeSd, sd);
            if (-sd < out.edge) { out.edge = (float) -sd; out.nearLevel = l.level; out.nearWidth = 30; }
            if (sd > 0 && out.wall == null) {
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

    /**
     * A plunge pool: a bowl at the foot of a fall, deepest under where the water lands; a rim of rock a block above
     * its water all round, except the lip on the downstream side where it spills on down the stream.
     */
    private static void pool(RegionPlan.Pool pl, int x, int z, Column out) {
        double dx = x + 0.5 - pl.x, dz = z + 0.5 - pl.z, d = Math.hypot(dx, dz);
        double a = Math.atan2(dz, dx);
        double shape = 1 + 0.15 * Math.sin(3 * a + pl.level) + 0.08 * Math.sin(5 * a + pl.x);
        double q = d / (pl.r * shape);
        if (q >= 1.45) return;
        double along = dx * pl.dx + dz * pl.dz, lateral = Math.abs(dx * pl.dz - dz * pl.dx);
        boolean lip = along > 0 && lateral <= pl.lip + 0.6 && out.channel;           // the narrow notch it pours out of, where its stream runs on
        int L = pl.level;
        if (q < 1) {
            double bed = L - pl.depth * (1 - q * q) - (q > 0.8 ? 0 : 0.5);
            bed = Math.min(bed, L - 1);
            out.carve = Math.min(out.carve, bed);
            out.bed = Math.min(out.bed, bed);
            if (!(out.pool != null && out.poolQ < 1 && out.water < L)) { out.water = L; out.pool = pl; out.poolQ = (float) q; }   // two bowls meeting: the lower one's water
            out.raise = Double.NEGATIVE_INFINITY;
        } else if (lip) {
            out.carve = Math.min(out.carve, L - 1);                                 // the lip it spills over
        } else if (out.poolQ >= 1 && !out.lake && !(out.channel && out.water >= L)) {   // not across the stream falling in
            out.raise = Math.max(out.raise, L + 1);                                  // the rim
            out.carve = Math.max(out.carve, L + 1);
            out.pool = pl; out.poolQ = (float) Math.min(out.poolQ, q);
            if (out.water != Column.DRY && out.water < L) out.water = Column.DRY;   // no lower stream water through the rim
        }
    }

    /**
     * A delta's fan: the land between its channels is brought down to a block or two above the sea (sandbars, low
     * islets, marshy flats), rising gently again toward the fan's edges so it meets the land round it without a wall.
     */
    private static void delta(RegionPlan.Delta dl, int x, int z, Column out) {
        double dx = x + 0.5 - dl.x, dz = z + 0.5 - dl.z, d = Math.hypot(dx, dz);
        if (d > dl.r + 4) return;
        double along = (dx * dl.dx + dz * dl.dz), ang = Math.atan2(Math.abs(dx * dl.dz - dz * dl.dx), along);
        if (along < -6) return;
        double edgeA = Math.max(0, ang / dl.spread - 0.75) * 4, edgeR = Math.max(0, d / dl.r - 0.92) * 12, back = Math.max(0, -along) * 1.2;
        double rise = (edgeA + edgeR) * 6 + back;
        double islet = valueNoise(x / 9.0, z / 9.0, 51) > 0.25 ? 1 : 0;
        double top = 63 + islet + rise;
        if (top < out.carve) out.carve = top;
        if (rise < 3) out.delta = true;
    }

    /**
     * A wetland: flat ground a block above its water, broken into shallow pools in an irregular patchwork (more water
     * toward the middle, only scattered pools near its edges), so it reads as marsh, swamp or bog rather than a pond.
     */
    private static void wetland(int W, double m, boolean low, int x, int z, Column out) {
        double t = Math.min(1, (m - 0.35) / 0.4);
        if (t <= 0) return;
        double blot = valueNoise(x / 5.5, z / 5.5, 71) + 0.55 * valueNoise(x / 2.3, z / 2.3, 72);
        boolean wet = low && blot > 0.55 - 0.75 * t;
        out.wetland = true;
        out.wetlandLevel = W;
        if (wet) {
            double bed = W - 1 - (blot > 0.9 ? 1 : 0);
            out.carve = Math.min(out.carve, bed);
            out.bed = Math.min(out.bed, bed);
            out.water = W;
        } else {
            out.raise = Math.max(out.raise, W + 1);                              // built up to hold the pools in, never cut down
        }
    }

    /**
     * An oasis: a spring-fed pool sunk into a dip in the sand, deepest in the middle, a low rim a block above its
     * water all round, and a shallow hollow round it where the grass and palms grow.
     */
    private static void oasis(RegionPlan.Oasis o, int x, int z, Column out) {
        double dx = x + 0.5 - o.x, dz = z + 0.5 - o.z, d = Math.hypot(dx, dz);
        double a = Math.atan2(dz, dx);
        double shape = 1 + 0.2 * Math.sin(2 * a + o.seed % 7) + 0.12 * Math.sin(5 * a + o.seed % 13);
        double q = d / (o.r * shape);
        if (q >= 2.4 || q >= out.oasisQ) return;
        out.oasis = o; out.oasisQ = (float) q;
        int L = o.level;
        if (q < 1) {
            double bed = Math.min(L - 1, L - o.depth * (1 - q * q) + valueNoise(x / 3.0, z / 3.0, 33) * 0.6);
            out.carve = Math.min(out.carve, bed);
            out.bed = Math.min(out.bed, bed);
            out.water = L;
        } else {
            out.carve = Math.min(out.carve, L + 1 + (q - 1) * 4.5);               // the hollow it sits in
            if (q < 1.35) out.raise = Math.max(out.raise, L + 1);                    // the rim that holds it
        }
    }

    /**
     * A dam: a wall across the stream from bank to bank, built up from below the stream bed to a block above the pond
     * behind it, with a notch where the stream crosses so the overflow spills down its face. (The pond itself is a
     * lake in the plan.)
     */
    private static void wall(RegionPlan.Wall w, int x, int z, Column out) {
        double px = x + 0.5, pz = z + 0.5, dx = w.bx - w.ax, dz = w.bz - w.az, l2 = dx * dx + dz * dz;
        double t = l2 < 1e-9 ? 0 : ((px - w.ax) * dx + (pz - w.az) * dz) / l2;
        if (t < -0.02 || t > 1.02) return;
        t = Math.max(0, Math.min(1, t));
        double ex = w.ax + dx * t - px, ez = w.az + dz * t - pz, d = Math.hypot(ex, ez);
        if (d > w.half) return;
        double notch = Math.hypot(px - w.nx, pz - w.nz);
        out.wall = w;
        out.wallBase = w.base;
        out.wallTop = notch < w.notchHalf ? w.notchTop : w.top;
        out.wallNotch = notch < w.notchHalf;
        out.water = Column.DRY; out.channel = false; out.lake = false;
    }

    /**
     * A bay: an oval of sea reaching in from the coast (open on the sea's side), shelving deeper toward its mouth, then
     * a beach climbing gently from the waterline round it.
     */
    private static void bay(RegionPlan.Bay b, int x, int z, Column out) {
        double dx = x + 0.5 - b.x, dz = z + 0.5 - b.z;
        double along = dx * b.ax + dz * b.az, across = dx * b.az - dz * b.ax;   // along: + toward the sea
        if (along > b.ra * 2.4) return;
        double wob = 1 + 0.12 * valueNoise(x / 9.0, z / 9.0, 71);
        double qa = along < 0 ? -along / b.ra : 0;
        double q = Math.hypot(qa, across / b.rb) / wob;
        if (q >= 1.5) return;
        if (q < 1) {
            double toMouth = Math.max(0, Math.min(1, along / b.ra + 0.6));
            double top = Math.min(SEA_LEVEL - 1, SEA_LEVEL - b.depth * (1 - q * q) * (0.55 + 0.45 * toMouth) - 0.5);
            out.carve = Math.min(out.carve, top);
            out.bed = Math.min(out.bed, top);
            if (!out.wet() || out.water < SEA_LEVEL) { out.water = SEA_LEVEL; out.channel = false; out.lake = false; }
            out.bay = true;
        } else {
            out.carve = Math.min(out.carve, SEA_LEVEL + 0.6 + (q - 1) / 0.5 * 4.5);   // the beach
        }
        out.bayQ = (float) Math.min(out.bayQ, q);
    }

    static final int SEA_LEVEL = RegionPlan.SEA_TOP;

    /** A beaver pond: still water a block above the stream, a soft muddy bed, a low rim round it; drowned in it, the stream. */
    private static void beaverPond(RegionPlan.Pool pl, int x, int z, Column out) {
        double dx = x + 0.5 - pl.x, dz = z + 0.5 - pl.z, d = Math.hypot(dx, dz);
        double a = Math.atan2(dz, dx), shape = 1 + 0.18 * Math.sin(3 * a + pl.x) + 0.1 * Math.sin(5 * a + pl.z);
        double q = d / (pl.r * shape);
        if (q >= 1.4) return;
        int L = pl.level;
        if (q < 1) {
            double bed = L - 1 - (q < 0.55 ? 1 : 0) + (valueNoise(x / 2.5, z / 2.5, 115) > 0.5 ? 1 : 0) * (q > 0.75 ? 1 : 0);
            bed = Math.min(bed, L - 1);
            out.carve = Math.min(out.carve, bed);
            out.bed = Math.min(out.bed, bed);
            out.water = L; out.lake = true; out.beaver = true;
            out.raise = Double.NEGATIVE_INFINITY;
        } else if (!out.channel && !out.lake && out.poolQ >= 1) {                      // the stream itself runs in freely
            out.raise = Math.max(out.raise, L + 1);                                  // the rim that holds it
            out.carve = Math.max(out.carve, L + 1);
        }
    }

    /** Smooth noise in -1..1 from hashed lattice values. */
    public static double valueNoise(double x, double z, long seed) {
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
        public RegionPlan.Pool pool;
        /** In a delta's low flats. */
        public boolean delta;
        /** In a beaver pond. */
        public boolean beaver;
        /** Part of a dam: built solid from wallBase to wallTop (wallNotch: the low spillway it overflows through). */
        public RegionPlan.Wall wall;
        public int wallBase, wallTop;
        public boolean wallNotch;
        /** In or round an oasis (oasisQ: distance from its middle, 1 at its water's edge). */
        public RegionPlan.Oasis oasis;
        public float oasisQ;
        /** In a wetland, and its water level. */
        public boolean wetland;
        public int wetlandLevel;
        /** In a bay (bayQ: distance from its middle, 1 at the waterline, up to 1.5 across its beach). */
        public boolean bay;
        public float bayQ;
        /** How far outside the nearest channel's or lake's water this column is (blocks; negative inside), that water's level and half-width. */
        public float edge, nearWidth;
        public int nearLevel;
        public float poolQ;
        public static final int DRY = Integer.MIN_VALUE;

        public void reset(int x, int z) {
            this.x = x; this.z = z; plan = null; carve = Double.POSITIVE_INFINITY; bed = Double.POSITIVE_INFINITY; water = DRY;
            channel = false; lake = false; hw = 0; dist = 0; lakeSd = -99; springQ = 9; spring = null; raise = Double.NEGATIVE_INFINITY; fill = Double.NEGATIVE_INFINITY; pool = null; poolQ = 9; delta = false; beaver = false; wall = null; wallBase = 0; wallTop = Integer.MIN_VALUE; wallNotch = false; oasis = null; oasisQ = 9; wetland = false; wetlandLevel = DRY; bay = false; bayQ = 9; edge = Float.POSITIVE_INFINITY; nearLevel = DRY; nearWidth = 0;
        }
        public boolean wet() { return water != DRY; }
    }
}
