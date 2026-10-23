package com.ronald.rivergence.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The water for one square region of the world (2048 blocks a side), worked out once and kept.
 *
 * The drainage is planned over the region plus a wide margin round it, so the streams and rivers crossing its edges
 * come out the same as in the neighbouring regions (everything is laid on one world-wide grid of cells, and the
 * shapes of the channels depend only on the cells they pass through). From the drainage:
 *   - every cell with enough water gathered above it carries a channel to the next cell downstream: a curve through
 *     the two cells' (slightly shuffled) centres, as wide and deep as the water it carries;
 *   - its water level falls from cell to cell, never rising, and along each channel it is pinned below the banks by
 *     sampling the ground every few blocks (so it never stands above the land beside it);
 *   - hollows deep and big enough hold lakes, whose shores are found by flooding the hollow on a finer grid.
 * Only what can reach the region (plus a little round it) is kept, indexed by chunk for quick lookups.
 */
public final class RegionPlan {
    public static final int SHIFT = 11, SIZE = 1 << SHIFT, MARGIN = 1024, CELL = 32;
    static final int N = (SIZE + 2 * MARGIN) / CELL;
    /** Kept: what lies within this far of the region. */
    static final int BAND = 96;
    /** Cells (of CELL x CELL blocks) that must drain through a cell before it carries a stream. */
    public static final float STREAM_CELLS = 8;
    /** How far the carving of a channel's banks reaches beyond its water's edge. */
    public static final int REACH = 26;
    static final double JITTER = 9;
    public static final int SEA_TOP = 62;

    public final int rx, rz, bx0, bz0;
    public final Drainage d;
    /** The land's exact heights (for digging the rice terraces into the hill as it really is). */
    private final Supplier<Drainage.Heights> heights;
    public final List<Segment> segments = new ArrayList<>();
    public final List<Lake> lakes = new ArrayList<>();
    public final List<Spring> springs = new ArrayList<>();
    public final List<Pool> pools = new ArrayList<>();
    public final List<Delta> deltas = new ArrayList<>();
    public final List<Oasis> oases = new ArrayList<>();
    public final List<Wall> walls = new ArrayList<>();
    public final List<Bay> bays = new ArrayList<>();
    public final List<Canal> canals = new ArrayList<>();
    private int[][] chunkCanals;
    public final List<Terrace> terraces = new ArrayList<>();
    private int[][] chunkTerraces;
    /** Per chunk of the region and a little round it: the segments and lakes that may touch it. */
    private final int cx0, cz0, cw;
    private final int[][] chunkSegs, chunkLakes, chunkSprings, chunkPools, chunkDeltas, chunkOases, chunkWalls, chunkBeavers, chunkBays;
    /** Per cell: biome facts at its centre (see Biomes), or null when biomes aren't known. */
    private byte[] cellBiome;
    public long nanos, sampleNanos;
    public String timing = "";

    /** A stretch of channel from one cell's centre to the next, as points two blocks apart with their water levels. */
    public static final class Segment {
        /** Per point: position, water level, half the water's width. */
        public final float[] x, z, level, w;
        public final float hw, depth, acc;
        public int minX, minZ, maxX, maxZ;
        Segment(float[] x, float[] z, float[] level, float[] w, float hw, float depth, float acc) { this.x = x; this.z = z; this.level = level; this.w = w; this.hw = hw; this.depth = depth; this.acc = acc; }
    }

    /** A lake: its water level and its shape on a grid (signed distance to its shore, in blocks; inside > 0). */
    public static final class Lake {
        public final int level, gx0, gz0, step, w, h;
        public final float[] sd;
        /** How deep its basin goes at the middle (blocks below the surface). */
        public final float depth;
        Lake(int level, int gx0, int gz0, int step, int w, int h, float[] sd, float depth) { this.level = level; this.gx0 = gx0; this.gz0 = gz0; this.step = step; this.w = w; this.h = h; this.sd = sd; this.depth = depth; }

        /** Signed distance to the shore at a block column (inside the lake > 0). */
        public double sd(double x, double z) {
            double u = (x - gx0) / step, v = (z - gz0) / step;
            int i = (int) Math.floor(u), j = (int) Math.floor(v);
            if (i < 0 || j < 0 || i >= w - 1 || j >= h - 1) return -99;
            double fu = u - i, fv = v - j;
            double a = sd[i + j * w], b = sd[i + 1 + j * w], c = sd[i + (j + 1) * w], e = sd[i + 1 + (j + 1) * w];
            return (a * (1 - fu) + b * fu) * (1 - fv) + (c * (1 - fu) + e * fu) * fv;
        }
    }

    /**
     * Where a stream rises, high in the hills: a shallow seep of dotted pools between hummocks of mud and moss, in a
     * little hollow, open on the side the stream runs out of.
     */
    public static final class Spring {
        public final float x, z, r, dx, dz;
        public final int level;
        public final long seed;
        Spring(float x, float z, float r, int level, float dx, float dz, long seed) { this.x = x; this.z = z; this.r = r; this.level = level; this.dx = dx; this.dz = dz; this.seed = seed; }
    }

    /** Where a stream falls over a ledge: a rock-rimmed bowl it lands in, spilling on down its course. */
    public static final class Pool {
        public final float x, z, r, depth, dx, dz, drop;
        public final int level;
        /** Half the width of the lip it pours out over (2 to 3 blocks across for a stream). */
        public float lip = 1.2f;
        Pool(float x, float z, float r, int level, float depth, float dx, float dz, float drop) { this.x = x; this.z = z; this.r = r; this.level = level; this.depth = depth; this.dx = dx; this.dz = dz; this.drop = drop; }
    }

    /** Where a big river meets the sea over low ground: a fan of channels, the land between brought down to sandbars and islets. */
    public static final class Delta {
        public final float x, z, dx, dz, r, spread;
        Delta(float x, float z, float dx, float dz, float r, float spread) { this.x = x; this.z = z; this.dx = dx; this.dz = dz; this.r = r; this.spread = spread; }
    }

    /** A bay cut into a low coast: an oval of sea reaching inland, open to the sea on one side, beaches round it. */
    public static final class Bay {
        public final float x, z, ax, az, ra, rb, depth;
        Bay(float x, float z, float ax, float az, float ra, float rb, float depth) { this.x = x; this.z = z; this.ax = ax; this.az = az; this.ra = ra; this.rb = rb; this.depth = depth; }
    }

    /**
     * One straight run of a canal: from (x0, z0) to (x1, z1) along x or along z, its water at level, three blocks
     * wide and a block deep, lined with stone at the water's edge.
     */
    public static final class Canal {
        public final int x0, z0, x1, z1, level;
        public final boolean alongX;
        public final float vx, vz;               // the village it serves
        Canal(int x0, int z0, int x1, int z1, int level, float vx, float vz) {
            this.x0 = Math.min(x0, x1); this.x1 = Math.max(x0, x1); this.z0 = Math.min(z0, z1); this.z1 = Math.max(z0, z1);
            this.alongX = z0 == z1; this.level = level; this.vx = vx; this.vz = vz;
        }
    }

    /**
     * Rice terraces on a hillside near a village by the cherry groves: flat paddies of shallow water stepping down
     * the slope, each following the lie of the hill (its lines curve with the contours), held by a low earth lip.
     */
    public static final class Terrace {
        public final float x, z, r;
        public final long seed;
        /** Downhill (unit), the fall per block and the height at the middle. */
        public final float ax, az, g, h0;
        /** No paddy above this: about three quarters of the way from the foot to the hill's top, so none spills over. */
        public float top = Float.MAX_VALUE;
        /** The paddies from the top down: where each starts (downhill distance from the middle) and its water level. */
        public final float[] edge;
        public final int[] level;
        /**
         * What each column of the site became once dug into the hill as it really is: 0 left alone, 1 a paddy of water
         * (dug into ground at least as high as its water), 2 dry earth at the paddy's level (a bund, or a paddy's edge
         * where the land beside it is too low to hold water: nothing is ever built up to hold it).
         */
        public int gx0, gz0, gw;
        public byte[] kind;
        /** The hill's own height at each column of the site (before anything is cut). */
        public float[] ground;

        public double groundAt(int x, int z) {
            int a = x - gx0, b = z - gz0;
            if (ground == null || a < 0 || b < 0 || a >= gw || b >= gw) return Double.NaN;
            return ground[a + b * gw];
        }

        public int kindAt(int x, int z) {
            int a = x - gx0, b = z - gz0;
            if (kind == null || a < 0 || b < 0 || a >= gw || b >= gw) return 0;
            return kind[a + b * gw];
        }

        void dig(Drainage.Heights hs) {
            int R = (int) Math.ceil(r * 1.16) + 2;
            gx0 = (int) Math.floor(x) - R; gz0 = (int) Math.floor(z) - R; gw = 2 * R + 1;
            int nn = gw * gw;
            float[] H = new float[nn];
            int[] n = new int[nn];
            boolean[] bund = new boolean[nn];
            java.util.List<java.util.List<Float>> per = new java.util.ArrayList<>();
            for (int k = 0; k < level.length; k++) per.add(new java.util.ArrayList<>());
            for (int b = 0; b < gw; b++) for (int a = 0; a < gw; a++) {
                int i = a + b * gw, xx = gx0 + a, zz = gz0 + b;
                double[] L = terraceLocate(this, xx, zz);
                double q = L[2] / L[3];
                n[i] = q < 1 ? terracePaddy(this, L[0]) : -1;
                // only the site and a rim round it are looked at (the corners of the square are never used)
                H[i] = q < 1.1 ? (float) hs.at(xx, zz) : Float.NaN;
                if (n[i] < 0) continue;
                bund[i] = L[0] >= edge[n[i] + 1] - 1 || q > 0.88;
                per.get(n[i]).add(H[i]);
            }
            // each paddy's water no higher than most of the ground along it (so it's dug in, not built up), and
            // never higher than the one above it
            for (int k = 0; k < level.length; k++) {
                java.util.List<Float> v = per.get(k);
                if (v.isEmpty()) continue;
                java.util.Collections.sort(v);
                int low = (int) Math.floor(v.get((int) (v.size() * 0.35)));
                level[k] = Math.min(level[k], low);
                if (k > 0) level[k] = Math.min(level[k], level[k - 1]);
            }
            boolean[] cand = new boolean[nn];
            for (int i = 0; i < nn; i++) cand[i] = n[i] >= 0 && level[n[i]] <= top && H[i] >= level[n[i]];
            kind = new byte[nn];
            int[] da = {1, -1, 0, 0}, db = {0, 0, 1, -1};
            for (int b = 0; b < gw; b++) for (int a = 0; a < gw; a++) {
                int i = a + b * gw;
                if (!cand[i]) continue;
                int L = level[n[i]];
                boolean holds = !bund[i];
                for (int d = 0; d < 4 && holds; d++) {
                    int a2 = a + da[d], b2 = b + db[d];
                    if (a2 < 0 || b2 < 0 || a2 >= gw || b2 >= gw) { holds = false; break; }
                    int j = a2 + b2 * gw;
                    // beside it: the same paddy or a higher one (water or earth at least as high), or the hill itself
                    // standing at least as high as the water
                    if (cand[j]) holds = level[n[j]] >= L;
                    else if (n[j] >= 0 && level[n[j]] <= top) holds = false;      // a lower patch of the terraces
                    else holds = H[j] >= L;                                          // outside: the hill, if it stands as high
                }
                kind[i] = (byte) (holds ? 1 : 2);
            }
            ground = H;
        }
        Terrace(float x, float z, float r, float ax, float az, float g, float h0, long seed) {
            this.x = x; this.z = z; this.r = r; this.ax = ax; this.az = az; this.g = g; this.h0 = h0; this.seed = seed;
            // narrow up the hill (two blocks of water), wider toward the foot (up to five), each with a one-block bund
            java.util.List<Float> e = new java.util.ArrayList<>();
            java.util.List<Integer> l = new java.util.ArrayList<>();
            float a = -r * 1.4f;
            int i = 0;
            while (a < r * 1.4f) {
                double t = Math.max(0, Math.min(1, (a + r * 1.2) / (r * 2.4)));
                long hsh = (seed + i * 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
                double jit = ((hsh >>> 40) & 1023) / 1023.0 - 0.5;
                int w = (int) Math.max(2, Math.min(5, Math.round(2 + 3 * t + jit * 1.2)));
                e.add(a);
                l.add((int) Math.floor(h0 - g * (a + (w + 1) / 2.0) + 0.5));
                a += w + 1;
                i++;
            }
            e.add(a);
            edge = new float[e.size()];
            for (int k = 0; k < edge.length; k++) edge[k] = e.get(k);
            level = l.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** Where a column lies on a terrace: {along (downhill, lines curved), across, distance from the middle, its radius there}. */
    static double[] terraceLocate(Terrace t, int x, int z) {
        double dx = x + 0.5 - t.x, dz = z + 0.5 - t.z;
        double wob = 1 + 0.15 * Planner.valueNoise(x / 13.0, z / 13.0, t.seed);
        double along = dx * t.ax + dz * t.az, across = dx * t.az - dz * t.ax;
        along += Planner.valueNoise(across / 16.0, 0.5, t.seed + 5) * 3.0;
        return new double[]{along, across, Math.hypot(dx, dz), t.r * wob};
    }

    static int terracePaddy(Terrace t, double along) {
        for (int k = 0; k + 1 < t.edge.length; k++) if (along >= t.edge[k] && along < t.edge[k + 1]) return k;
        return -1;
    }

    /** A spring-fed pool in the desert: where, how big, its water level, how deep. */
    public static final class Oasis {
        public final float x, z, r, depth;
        public final int level;
        public final long seed;
        Oasis(float x, float z, float r, int level, float depth, long seed) { this.x = x; this.z = z; this.r = r; this.level = level; this.depth = depth; this.seed = seed; }
    }

    /** A dam across a stream: a beaver's dam of logs and mud, or an old stone wall holding a reservoir. */
    public static final class Wall {
        public final float ax, az, bx, bz, half, nx, nz, notchHalf;
        public final int top, base, notchTop;
        public final boolean ancient;
        Wall(float ax, float az, float bx, float bz, float half, int top, int base, float nx, float nz, float notchHalf, int notchTop, boolean ancient) {
            this.ax = ax; this.az = az; this.bx = bx; this.bz = bz; this.half = half; this.top = top; this.base = base;
            this.nx = nx; this.nz = nz; this.notchHalf = notchHalf; this.notchTop = notchTop; this.ancient = ancient;
        }
    }

    public static long key(int rx, int rz) { return ((long) rx << 32) ^ (rz & 0xffffffffL); }

    /**
     * @param heights gives each worker thread its own height sampler
     */
    /** Biome facts per 4x4 column (quart coordinates): 1 vanilla river, 2 ocean, 4 swamp. */
    public interface Biomes { int at(int qx, int qz); }

    public RegionPlan(int rx, int rz, Supplier<Drainage.Heights> heights, Biomes biomes, ForkJoinPool pool) { this(rx, rz, heights, biomes, pool, null); }

    public RegionPlan(int rx, int rz, Supplier<Drainage.Heights> heights, Biomes biomes, ForkJoinPool pool, HeightCache cache) {
        long t0 = System.nanoTime();
        this.rx = rx; this.rz = rz;
        this.heights = heights;
        bx0 = rx * SIZE; bz0 = rz * SIZE;
        int x0 = bx0 - MARGIN, z0 = bz0 - MARGIN;
        ThreadLocal<Drainage.Heights> hs = ThreadLocal.withInitial(heights);

        // 1. the land, one height per cell
        float[] h = new float[N * N];
        pool.submit(() -> IntStream.range(0, N).parallel().forEach(j -> {
            Drainage.Heights s = hs.get();
            for (int i = 0; i < N; i++) {
                int x = x0 + i * CELL + CELL / 2, z = z0 + j * CELL + CELL / 2;
                float v = cache == null ? Float.NaN : cache.get(x, z);
                if (Float.isNaN(v)) { v = (float) s.at(x, z); if (cache != null) cache.put(x, z, v); }
                h[i + j * N] = v;
            }
        })).join();
        sampleNanos = System.nanoTime() - t0;
        boolean[] riv = null;
        if (biomes != null) {
            boolean[] r = new boolean[N * N];
            byte[] fl = new byte[N * N];
            pool.submit(() -> IntStream.range(0, N).parallel().forEach(j -> {
                for (int i = 0; i < N; i++) {
                    fl[i + j * N] = (byte) biomes.at((x0 + i * CELL + CELL / 2) >> 2, (z0 + j * CELL + CELL / 2) >> 2);
                    r[i + j * N] = (fl[i + j * N] & 1) != 0;
                }
            })).join();
            riv = r;
            cellBiome = fl;
        }
        long tb = System.nanoTime();
        d = new Drainage(x0, z0, CELL, N, h, riv);
        long td = System.nanoTime();
        networks();

        // 2. lakes kept near the region, each traced on a finer grid
        int[] lakeOf = refineLakes(hs, pool);
        long tl = System.nanoTime();
        // vanilla's water inland (river valleys, pools at sea level) that isn't one of our lakes: filled in
        fillCell = new boolean[N * N];
        for (int k = 0; k < N * N; k++) fillCell[k] = d.h[k] < Drainage.SEA && !d.sea(k) && lakeOf[k] < 0;
        fillGrid(biomes, lakeOf, pool);
        long tf = System.nanoTime();

        // 3. water levels at the cells' centres, upstream first
        int NN = N * N;
        float[] L = new float[NN];
        Arrays.fill(L, Float.NaN);
        Integer[] order = new Integer[NN];
        for (int k = 0; k < NN; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Float.compare(d.fill[b], d.fill[a]));
        float[] up = new float[NN];
        Arrays.fill(up, Float.POSITIVE_INFINITY);
        int[] main = new int[NN];
        Arrays.fill(main, -1);
        for (int k : order) {
            boolean ch = channel(k);
            if (d.sea(k)) L[k] = SEA_TOP;
            else if (lakeOf[k] >= 0) L[k] = lakes.get(lakeOf[k]).level;
            else if (ch) L[k] = (float) Math.max(SEA_TOP, Math.min(up[k], Math.floor(d.h[k]) - 1));
            int dn = d.down(k);
            if (dn >= 0 && (ch || lakeOf[k] >= 0) && !Float.isNaN(L[k])) {
                up[dn] = Math.min(up[dn], L[k]);
                if (main[dn] < 0 || d.acc[k] > d.acc[main[dn]]) main[dn] = k;
            }
        }

        // 4. channel segments that can reach the region
        int lo = MARGIN / CELL - (BAND + REACH + 64) / CELL - 1, hi = (MARGIN + SIZE) / CELL + (BAND + REACH + 64) / CELL + 1;
        List<int[]> todo = new ArrayList<>();
        for (int j = Math.max(1, lo); j < Math.min(N - 1, hi); j++) for (int i = Math.max(1, lo); i < Math.min(N - 1, hi); i++) {
            int k = i + j * N, dn = d.down(k);
            if (dn < 0 || d.sea(k) || Float.isNaN(L[k])) continue;
            boolean inLake = lakeOf[k] >= 0;
            if (inLake && lakeOf[dn] == lakeOf[k]) {                       // inside one lake: the lake covers it, if it really reaches both
                Lake lk = lakes.get(lakeOf[k]);
                if (lk.sd(px(k), pz(k)) > 2 && lk.sd(px(dn), pz(dn)) > 2) continue;
            }
            if (!inLake && !channel(k)) continue;
            if (Float.isNaN(L[dn])) L[dn] = (float) Math.max(SEA_TOP, Math.min(L[k], Math.floor(d.h[dn]) - 1));
            todo.add(new int[]{k, dn});
        }
        Segment[] made = new Segment[todo.size()];
        pool.submit(() -> IntStream.range(0, todo.size()).parallel().forEach(t -> {
            int k = todo.get(t)[0], dn = todo.get(t)[1];
            made[t] = segment(k, dn, main[k], d.down(dn), L[k], L[dn], lakeOf[k] >= 0 ? lakes.get(lakeOf[k]) : null, hs.get());
        })).join();
        wetlands(L, lakeOf);
        oases(lakeOf);
        for (int t = 0; t < made.length; t++) {
            Segment s = made[t];
            if (s == null) continue;
            segments.add(s);
            findFalls(s);
            int k = todo.get(t)[0];
            damCandidates.add(new Object[]{s, k});
            if (d.sea(todo.get(t)[1])) delta(k, todo.get(t)[1], s);
            if (!head[k]) continue;
            int gi = Math.floorDiv(d.x0, CELL) + k % N, gj = Math.floorDiv(d.z0, CELL) + k / N;
            double ex = s.x[Math.min(3, s.x.length - 1)] - s.x[0], ez = s.z[Math.min(3, s.z.length - 1)] - s.z[0], el = Math.max(1e-6, Math.hypot(ex, ez));
            float r = (float) (4.2 + 2.3 * (jit(gi, gj, 21) + 1) / 2);
            springs.add(new Spring(s.x[0], s.z[0], r, (int) s.level[0], (float) (ex / el), (float) (ez / el), (long) (jit(gi, gj, 22) * 1e12)));
        }

        long ts = System.nanoTime();
        dams(damCandidates, hs.get());
        if (com.ronald.rivergence.RivergenceConfig.bays()) bays(lakeOf);
        // 5. index by chunk
        cx0 = (bx0 - BAND) >> 4; cz0 = (bz0 - BAND) >> 4;
        cw = (SIZE + 2 * BAND) >> 4;
        chunkSegs = new int[cw * cw][];
        chunkLakes = new int[cw * cw][];
        chunkSprings = new int[cw * cw][];
        chunkPools = new int[cw * cw][];
        chunkDeltas = new int[cw * cw][];
        chunkOases = new int[cw * cw][];
        chunkWalls = new int[cw * cw][];
        chunkBeavers = new int[cw * cw][];
        chunkBays = new int[cw * cw][];
        List<List<Integer>> cby = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) cby.add(null);
        for (int i = 0; i < bays.size(); i++) {
            Bay g = bays.get(i);
            int reach = (int) Math.ceil(Math.max(g.ra * 2.4, g.rb * 1.6)) + 4 + Planner.BLEND;
            mark(cby, (int) g.x - reach, (int) g.z - reach, (int) g.x + reach, (int) g.z + reach, i);
        }
        for (int c = 0; c < cw * cw; c++) if (cby.get(c) != null) chunkBays[c] = cby.get(c).stream().mapToInt(Integer::intValue).toArray();
        List<List<Integer>> cbv = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) cbv.add(null);
        for (int i = 0; i < beaverPonds.size(); i++) {
            Pool g = beaverPonds.get(i);
            int reach = (int) Math.ceil(g.r * 1.5) + 2;
            mark(cbv, (int) g.x - reach, (int) g.z - reach, (int) g.x + reach, (int) g.z + reach, i);
        }
        for (int c = 0; c < cw * cw; c++) if (cbv.get(c) != null) chunkBeavers[c] = cbv.get(c).stream().mapToInt(Integer::intValue).toArray();
        List<List<Integer>> cwl = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) cwl.add(null);
        for (int i = 0; i < walls.size(); i++) {
            Wall g = walls.get(i);
            int pad = (int) Math.ceil(g.half) + 2;
            mark(cwl, (int) Math.min(g.ax, g.bx) - pad, (int) Math.min(g.az, g.bz) - pad, (int) Math.max(g.ax, g.bx) + pad, (int) Math.max(g.az, g.bz) + pad, i);
        }
        for (int c = 0; c < cw * cw; c++) if (cwl.get(c) != null) chunkWalls[c] = cwl.get(c).stream().mapToInt(Integer::intValue).toArray();
        List<List<Integer>> co = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) co.add(null);
        for (int i = 0; i < oases.size(); i++) {
            Oasis g = oases.get(i);
            int reach = (int) Math.ceil(g.r * 3.2) + 2 + Planner.BLEND;
            mark(co, (int) g.x - reach, (int) g.z - reach, (int) g.x + reach, (int) g.z + reach, i);
        }
        for (int c = 0; c < cw * cw; c++) if (co.get(c) != null) chunkOases[c] = co.get(c).stream().mapToInt(Integer::intValue).toArray();
        List<List<Integer>> cd = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) cd.add(null);
        for (int i = 0; i < deltas.size(); i++) {
            Delta g = deltas.get(i);
            int reach = (int) Math.ceil(g.r) + 4;
            mark(cd, (int) g.x - reach, (int) g.z - reach, (int) g.x + reach, (int) g.z + reach, i);
        }
        for (int c = 0; c < cw * cw; c++) if (cd.get(c) != null) chunkDeltas[c] = cd.get(c).stream().mapToInt(Integer::intValue).toArray();
        List<List<Integer>> cq = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) cq.add(null);
        for (int i = 0; i < pools.size(); i++) {
            Pool g = pools.get(i);
            int reach = (int) Math.ceil(g.r * 1.6) + 2;
            mark(cq, (int) g.x - reach, (int) g.z - reach, (int) g.x + reach, (int) g.z + reach, i);
        }
        for (int c = 0; c < cw * cw; c++) if (cq.get(c) != null) chunkPools[c] = cq.get(c).stream().mapToInt(Integer::intValue).toArray();
        List<List<Integer>> cs = new ArrayList<>(), cl = new ArrayList<>(), cp = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) { cs.add(null); cl.add(null); cp.add(null); }
        for (int i = 0; i < springs.size(); i++) {
            Spring g = springs.get(i);
            int reach = (int) Math.ceil(g.r * 2.3) + 2 + Planner.BLEND;
            mark(cp, (int) g.x - reach, (int) g.z - reach, (int) g.x + reach, (int) g.z + reach, i);
        }
        for (int s = 0; s < segments.size(); s++) {
            Segment g = segments.get(s);
            mark(cs, g.minX, g.minZ, g.maxX, g.maxZ, s);
        }
        for (int l = 0; l < lakes.size(); l++) {
            Lake k = lakes.get(l);
            mark(cl, k.gx0, k.gz0, k.gx0 + (k.w - 1) * k.step, k.gz0 + (k.h - 1) * k.step, l);
        }
        for (int c = 0; c < cw * cw; c++) {
            if (cs.get(c) != null) chunkSegs[c] = cs.get(c).stream().mapToInt(Integer::intValue).toArray();
            if (cl.get(c) != null) chunkLakes[c] = cl.get(c).stream().mapToInt(Integer::intValue).toArray();
            if (cp.get(c) != null) chunkSprings[c] = cp.get(c).stream().mapToInt(Integer::intValue).toArray();
        }
        nanos = System.nanoTime() - t0;
        timing = String.format("heights %d, river biomes %d, drainage %d, lakes %d, fill %d, channels %d ms", sampleNanos / 1000000, (tb - t0 - sampleNanos) / 1000000, (td - tb) / 1000000, (tl - td) / 1000000, (tf - tl) / 1000000, (ts - tf) / 1000000);
    }

    private void mark(List<List<Integer>> idx, int x0, int z0, int x1, int z1, int v) {
        for (int cx = Math.max(cx0, x0 >> 4); cx <= Math.min(cx0 + cw - 1, x1 >> 4); cx++)
            for (int cz = Math.max(cz0, z0 >> 4); cz <= Math.min(cz0 + cw - 1, z1 >> 4); cz++) {
                int c = (cx - cx0) + (cz - cz0) * cw;
                if (idx.get(c) == null) idx.set(c, new ArrayList<>(4));
                idx.get(c).add(v);
            }
    }

    private static final int[] NONE = new int[0];
    public int[] segmentsAt(int x, int z) { int[] r = at(chunkSegs, x, z); return r == null ? NONE : r; }
    public int[] lakesAt(int x, int z) { int[] r = at(chunkLakes, x, z); return r == null ? NONE : r; }
    public int[] springsAt(int x, int z) { int[] r = at(chunkSprings, x, z); return r == null ? NONE : r; }
    public int[] poolsAt(int x, int z) { int[] r = at(chunkPools, x, z); return r == null ? NONE : r; }
    public int[] beaversAt(int x, int z) { int[] r = at(chunkBeavers, x, z); return r == null ? NONE : r; }
    public int[] wallsAt(int x, int z) { int[] r = at(chunkWalls, x, z); return r == null ? NONE : r; }

    private final List<Object[]> damCandidates = new ArrayList<>();
    /** Share of modest rivers given an old dam (a test can raise it with -Drivergence.damChance). */
    static final double ANCIENT_CHANCE = Double.parseDouble(System.getProperty("rivergence.damChance", "0.025"));
    public final int[] damWhy = new int[5], damTries = new int[2];

    /**
     * Dams. On streams through forest and taiga, now and then a beaver's dam (a couple of blocks high) with its pond
     * behind; rarely, on a modest river in a valley between hills, an old stone wall several blocks high holding a
     * long reservoir. Either is only built where the valley sides rise above the dam's top (found by sampling across
     * the stream), and the water behind is traced on a grid over the real ground and must stay inside its box, so a
     * pond or reservoir never spills round the dam's ends or out of its valley.
     */
    private void dams(List<Object[]> cands, Drainage.Heights hs) {
        int gi0 = Math.floorDiv(d.x0, CELL), gj0 = Math.floorDiv(d.z0, CELL);
        int band = BAND + 64;
        for (Object[] o : cands) {
            Segment s = (Segment) o[0];
            int k = (Integer) o[1];
            if (!fed[k] || head[k] || d.sea(k) || d.valley[k]) continue;
            int n = s.level.length, i = (int) (n * 0.55);
            if (i < 3 || i > n - 4) continue;
            float sx = s.x[i], sz = s.z[i];
            if (sx < bx0 - band || sx > bx0 + SIZE + band || sz < bz0 - band || sz > bz0 + SIZE + band) continue;
            int gi = gi0 + k % N, gj = gj0 + k / N;
            double roll = (jit(gi, gj, 111) + 1) / 2;
            boolean forest = cellBiome != null && (cellBiome[k] & 16) != 0;
            boolean beaver = forest && order[k] <= 2 && s.hw <= 3.2 && s.level[0] - s.level[n - 1] <= 4 && roll < 0.35;
            boolean ancient = !beaver && order[k] >= 2 && order[k] <= 4 && s.hw >= 2 && s.hw <= 7 && roll > 1 - ANCIENT_CHANCE
                    && (cellBiome == null || (cellBiome[k] & 8) == 0);
            if (!beaver && !ancient) continue;
            if (beaver) damTries[0]++; else damTries[1]++;
            Wall w = ancient ? dam(s, i, true, hs, gi, gj) : beaver(s, i, gi, gj);
            if (w != null) walls.add(w);
        }
    }

    /** Beaver ponds: round-ish, a block or so above the stream, held in by a low rim; the dam closes its downstream side. */
    public final List<Pool> beaverPonds = new ArrayList<>();

    private Wall beaver(Segment s, int i, int gi, int gj) {
        int n = s.level.length;
        double fx = s.x[Math.min(n - 1, i + 2)] - s.x[Math.max(0, i - 2)], fz = s.z[Math.min(n - 1, i + 2)] - s.z[Math.max(0, i - 2)], fl = Math.hypot(fx, fz);
        if (fl < 1e-6) return null;
        fx /= fl; fz /= fl;
        double px = -fz, pz = fx, cx = s.x[i], cz = s.z[i];
        int base = (int) Math.floor(s.level[i]), C = base + 1;
        float r = (float) (4.5 + 3 * (jit(gi, gj, 113) + 1) / 2 + s.w[i]);
        double ox = cx - fx * r * 0.85, oz = cz - fz * r * 0.85;                   // the pond, just upstream of the dam
        beaverPonds.add(new Pool((float) ox, (float) oz, r, C, 1.6f, (float) fx, (float) fz, 0));
        double half = r * 0.95;
        return new Wall((float) (cx - px * half), (float) (cz - pz * half), (float) (cx + px * half), (float) (cz + pz * half), 1.1f,
                C + 1, base - 2, (float) cx, (float) cz, 0.9f, C - 1, false);
    }

    private Wall dam(Segment s, int i, boolean ancient, Drainage.Heights hs, int gi, int gj) {
        int n = s.level.length;
        double fx = s.x[Math.min(n - 1, i + 2)] - s.x[Math.max(0, i - 2)], fz = s.z[Math.min(n - 1, i + 2)] - s.z[Math.max(0, i - 2)], fl = Math.hypot(fx, fz);
        if (fl < 1e-6) return null;
        fx /= fl; fz /= fl;
        double px = -fz, pz = fx;                                                   // across the stream
        double cx = s.x[i], cz = s.z[i];
        int base = (int) Math.floor(s.level[i]);
        int H = ancient ? 4 + (int) ((jit(gi, gj, 112) + 1) / 2 * 4) : 2;
        int C = base + H;                                                          // the water held behind it
        // the valley's sides: the wall runs out to where the ground stands above its top
        double reach = ancient ? 72 : 22, stepA = ancient ? 2 : 1;
        double[] ext = new double[2];
        for (int side = 0; side < 2; side++) {
            double sg = side == 0 ? -1 : 1;
            ext[side] = -1;
            for (double t = s.w[i] + 1; t <= reach; t += stepA)
                if (hs.at((int) Math.floor(cx + px * sg * t), (int) Math.floor(cz + pz * sg * t)) >= C + 1) { ext[side] = t + (ancient ? 3 : 1.5); break; }
            if (ext[side] < 0) { damWhy[0]++; return null; }
        }
        // the water behind: flooded over a grid of the real ground, upstream of the wall only
        int step = ancient ? 4 : 2, half = ancient ? 128 : 30;
        double ox = cx - fx * half * 0.7, oz = cz - fz * half * 0.7;
        int gx0 = (int) Math.floor(ox) - half, gz0 = (int) Math.floor(oz) - half, w = 2 * half / step + 1;
        float[] g = new float[w * w];
        boolean[] blocked = new boolean[w * w];
        for (int b = 0; b < w; b++) for (int a = 0; a < w; a++) {
            int x = gx0 + a * step, z = gz0 + b * step;
            blocked[a + b * w] = (x - cx) * fx + (z - cz) * fz > -1.0;              // the wall and everything past it
            g[a + b * w] = blocked[a + b * w] ? 1e4f : (float) hs.at(x, z);
        }
        int sa = (int) Math.round((cx - fx * (s.w[i] + 3) - gx0) / step), sb = (int) Math.round((cz - fz * (s.w[i] + 3) - gz0) / step);
        if (sa <= 0 || sb <= 0 || sa >= w - 1 || sb >= w - 1) { damWhy[1]++; return null; }
        int seed = sa + sb * w;
        if (g[seed] >= C) { damWhy[2]++; return null; }
        boolean[] in = new boolean[w * w];
        int[] q = new int[w * w];
        int qh = 0, qt = 0;
        q[qt++] = seed; in[seed] = true;
        while (qh < qt) {
            int c = q[qh++], a = c % w, b = c / w;
            if (a == 0 || b == 0 || a == w - 1 || b == w - 1) { damWhy[3]++; return null; }   // it would spill out of its valley
            int[] nb = {c - 1, c + 1, c - w, c + w};
            for (int kk : nb) if (!in[kk] && g[kk] < C) { in[kk] = true; q[qt++] = kk; }
        }
        if (qt * step * step < (ancient ? 900 : 24)) { damWhy[4]++; return null; }
        lakes.add(new Lake(C, gx0, gz0, step, w, w, signedDistance(in, w, w, step), ancient ? 1.5f : 1.2f));
        float wHalf = ancient ? 1.6f : 1.1f;
        float ax = (float) (cx - px * ext[0]), az = (float) (cz - pz * ext[0]), bx = (float) (cx + px * ext[1]), bz = (float) (cz + pz * ext[1]);
        return new Wall(ax, az, bx, bz, wHalf, C + 1, base - (ancient ? 4 : 2), (float) cx, (float) cz, ancient ? 1.6f : 0.9f, C - 1, ancient);
    }

    public int[] oasesAt(int x, int z) { int[] r = at(chunkOases, x, z); return r == null ? NONE : r; }

    /**
     * Oases: in deserts and badlands, now and then a hollow (a cell no higher than its neighbours) gets a spring-fed
     * pool. Spread thin: each is chosen by a hash of its place on the world grid, and none within a few hundred blocks
     * of another, so they're found, not stumbled over.
     */
    private void oases(int[] lakeOf) {
        if (cellBiome == null) return;
        int gi0 = Math.floorDiv(d.x0, CELL), gj0 = Math.floorDiv(d.z0, CELL);
        List<int[]> picked = new ArrayList<>();
        for (int j = 2; j < N - 2; j++) for (int i = 2; i < N - 2; i++) {
            int k = i + j * N;
            if ((cellBiome[k] & 8) != 0) dbgDesert++;
            if ((cellBiome[k] & 8) == 0 || d.sea(k) || fed[k] || lakeOf[k] >= 0 || d.h[k] < SEA_TOP + 2) continue;
            int gi = gi0 + i, gj = gj0 + j;
            dbgDry++;
            if ((jit(gi, gj, 91) + 1) / 2 > 0.06) continue;                            // rare
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            int desertN = 0;
            for (int dd = 0; dd < 8; dd++) {
                int q = (i + Drainage.DX[dd]) + (j + Drainage.DZ[dd]) * N;
                lo = Math.min(lo, d.h[q]); hi = Math.max(hi, d.h[q]);
                if ((cellBiome[q] & 8) != 0) desertN++;
            }
            boolean hollow = d.h[k] <= lo + 12 && hi - lo <= 34, desertAround = desertN >= 5;   // not on a peak or a cliff; the dip is ours to make
            dbgHash++;
            if (!hollow || !desertAround) continue;
            dbgHollow++;
            // no two close together (decided the same way in every region: the lower-hashed one wins)
            boolean clash = false;
            for (int dj = -5; dj <= 5 && !clash; dj++) for (int di = -5; di <= 5; di++) {
                if (di == 0 && dj == 0) continue;
                if ((jit(gi + di, gj + dj, 91) + 1) / 2 <= 0.06 && jit(gi + di, gj + dj, 92) < jit(gi, gj, 92)) { clash = true; break; }
            }
            if (clash) continue;
            float r = (float) (6 + 5 * (jit(gi, gj, 93) + 1) / 2);
            float depth = (float) (2.2 + 1.5 * (jit(gi, gj, 94) + 1) / 2);
            // its water no higher than the low side of the ground round it (it sinks into the slope instead of spilling)
            float low4 = Float.MAX_VALUE;
            for (int dd = 0; dd < 8; dd += 2) low4 = Math.min(low4, d.h[(i + Drainage.DX[dd]) + (j + Drainage.DZ[dd]) * N]);
            int level = (int) Math.floor(Math.min(d.h[k], low4 + 2)) - 1;
            oases.add(new Oasis((float) px(k), (float) pz(k), r, level, depth, (long) (jit(gi, gj, 95) * 1e12)));
        }
    }

    public int[] canalsAt(int x, int z) { if (chunkCanals == null) return NONE; int[] r = at(chunkCanals, x, z); return r == null ? NONE : r; }

    /**
     * Canals: from the villages round this region (where the village spacing and the biome say one stands), a straight
     * cut of water to the nearest stream or river, in one or two straight runs at the river's level, as long as the
     * ground keeps a little above that level. It stops short of the village itself, out among its fields.
     */
    public final int[] dbgCanal = new int[6];
    public final int[] dbgT = new int[4];
    public double dbgGmax;

    public int[] terracesAt(int x, int z) { if (chunkTerraces == null) return NONE; int[] r = at(chunkTerraces, x, z); return r == null ? NONE : r; }

    /** Whether rice would grow at a spot (warm, wet land). */
    public interface RiceLand { boolean test(int x, int y, int z); }

    public void canals(long seed, java.util.function.BiPredicate<Integer, Integer> villageLand, java.util.function.BiPredicate<Integer, Integer> cherryNear, RiceLand riceLand) {
        List<float[]> villages = new ArrayList<>();
        int spacing = 34, separation = 8, salt = 10387312;
        int lo = Math.floorDiv(bx0 - BAND - 256, 16 * spacing) - 1, hi = Math.floorDiv(bx0 + SIZE + BAND + 256, 16 * spacing) + 1;
        int loz = Math.floorDiv(bz0 - BAND - 256, 16 * spacing) - 1, hiz = Math.floorDiv(bz0 + SIZE + BAND + 256, 16 * spacing) + 1;
        var rnd = new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(0L));
        for (int gx = lo; gx <= hi; gx++) for (int gz = loz; gz <= hiz; gz++) {
            rnd.setLargeFeatureWithSalt(seed, gx, gz, salt);
            int cx = gx * spacing + rnd.nextInt(spacing - separation), cz = gz * spacing + rnd.nextInt(spacing - separation);
            float vx = cx * 16 + 8, vz = cz * 16 + 8;
            if (vx < bx0 - BAND - 200 || vx > bx0 + SIZE + BAND + 200 || vz < bz0 - BAND - 200 || vz > bz0 + SIZE + BAND + 200) continue;
            dbgCanal[0]++;
            double vh = heightAt(vx, vz);
            if (Double.isNaN(vh) || vh < 64 || !villageLand.test((int) vx, (int) vz)) continue;
            dbgCanal[1]++;
            // a village doesn't start within 40 blocks of our water (it's built elsewhere), so none would be here
            if (nearWater(vx, vz, 44)) continue;
            dbgCanal[2]++;
            villages.add(new float[]{vx, vz});
            canalFor(vx, vz);
            // by the cherry groves, more than half the villages have rice terraces up a hillside nearby
            boolean ch = cherryNear.test((int) vx, (int) vz); if (ch) dbgCanal[5]++;
            if (ch && jit(gx, gz, 301) > -0.2) terraceFor(vx, vz, gx * 7919L + gz);
        }
        looseTerraces(seed, villages, riceLand);
        // index by chunk
        chunkCanals = new int[cw * cw][];
        List<List<Integer>> cc = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) cc.add(null);
        for (int i = 0; i < canals.size(); i++) { Canal k = canals.get(i); mark(cc, k.x0 - 8 - Planner.BLEND, k.z0 - 8 - Planner.BLEND, k.x1 + 8 + Planner.BLEND, k.z1 + 8 + Planner.BLEND, i); }
        for (int c = 0; c < cw * cw; c++) if (cc.get(c) != null) chunkCanals[c] = cc.get(c).stream().mapToInt(Integer::intValue).toArray();
        chunkTerraces = new int[cw * cw][];
        List<List<Integer>> ct = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) ct.add(null);
        for (int i = 0; i < terraces.size(); i++) { Terrace t = terraces.get(i); int rr = (int) Math.ceil(t.r * 1.2) + 2 + Planner.BLEND; mark(ct, (int) t.x - rr, (int) t.z - rr, (int) t.x + rr, (int) t.z + rr, i); }
        for (int c = 0; c < cw * cw; c++) if (ct.get(c) != null) chunkTerraces[c] = ct.get(c).stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Besides those by the cherry-grove villages: in every 1000-block square (the part of it in this region) at least
     * one terraced hillside wherever there's warm, wet land with a fitting slope, by a village if one is near.
     */
    private void looseTerraces(long seed, List<float[]> villages, RiceLand riceLand) {
        final int CELL = 1000, M = 70, STEP = 24;
        int ax0 = bx0 + M, ax1 = bx0 + SIZE - M, az0 = bz0 + M, az1 = bz0 + SIZE - M;
        for (int cx = Math.floorDiv(ax0, CELL); cx <= Math.floorDiv(ax1, CELL); cx++)
            for (int cz = Math.floorDiv(az0, CELL); cz <= Math.floorDiv(az1, CELL); cz++) {
                int x0 = Math.max(ax0, cx * CELL), x1 = Math.min(ax1, cx * CELL + CELL - 1);
                int z0 = Math.max(az0, cz * CELL), z1 = Math.min(az1, cz * CELL + CELL - 1);
                if (x1 - x0 < 60 || z1 - z0 < 60) continue;
                boolean have = false;
                for (Terrace t : terraces) if (t.x >= x0 && t.x <= x1 && t.z >= z0 && t.z <= z1) have = true;
                if (have) continue;
                long cs = seed ^ (cx * 341873128712L + cz * 132897987541L);
                List<double[]> cand = new ArrayList<>();
                for (int x = x0; x <= x1; x += STEP) for (int z = z0; z <= z1; z += STEP) {
                    double px = x + jit(x, z, 311) * 8, pz = z + jit(z, x, 312) * 8;
                    double h = heightAt(px, pz);
                    if (Double.isNaN(h) || h < 68 || h > 150) continue;
                    double gx = (heightAt(px + 8, pz) - heightAt(px - 8, pz)) / 16, gz = (heightAt(px, pz + 8) - heightAt(px, pz - 8)) / 16;
                    double g = Math.hypot(gx, gz);
                    if (Double.isNaN(g) || g < 0.15 || g > 0.8) continue;
                    double vd = 1e9;
                    for (float[] v : villages) vd = Math.min(vd, Math.hypot(v[0] - px, v[1] - pz));
                    if (vd < 60) continue;                                       // not on the houses
                    double score = Math.abs(g - 0.4) + (vd < 220 ? -0.3 : 0) + (jit(x, z, 313) + 1) * 0.05;
                    cand.add(new double[]{px, pz, g, h, score});
                }
                cand.sort((a, b) -> Double.compare(a[4], b[4]));
                int tried = 0;
                for (double[] c : cand) {
                    if (++tried > 40) break;
                    if (nearWater(c[0], c[1], 36)) continue;
                    boolean clash = false;
                    for (Terrace t : terraces) if (Math.hypot(t.x - c[0], t.z - c[1]) < t.r + 40) clash = true;
                    if (clash || !riceLand.test((int) c[0], (int) c[3], (int) c[1])) continue;
                    float r = (float) (26 + 14 * (jit(cx, cz, 314) + 1) / 2);
                    if (makeTerrace(c[0], c[1], r, cs)) break;
                }
            }
    }

    /** A hillside near the village, neither flat nor a cliff, away from the water and the houses: terraced. */
    private void terraceFor(float vx, float vz, long seed) {
        double bestScore = 1e9; double[] best = null;
        for (double rad : new double[]{70, 95, 120})
            for (int k = 0; k < 16; k++) {
                double a = k * Math.PI / 8 + jit(seed, k, 302) * 0.2;
                double px = vx + Math.cos(a) * rad, pz = vz + Math.sin(a) * rad;
                double h = heightAt(px, pz);
                if (Double.isNaN(h) || h < 68 || h > 150) continue;
                double gx = (heightAt(px + 8, pz) - heightAt(px - 8, pz)) / 16, gz = (heightAt(px, pz + 8) - heightAt(px, pz - 8)) / 16;
                double g = Math.hypot(gx, gz);
                dbgT[0]++;
                if (Double.isNaN(g) || g < 0.15 || g > 0.8) { if (!Double.isNaN(g)) { dbgT[1]++; dbgGmax = Math.max(dbgGmax, g); } continue; }
                dbgT[2]++;
                if (nearWater(px, pz, 36)) continue;
                boolean clash = false;
                for (Terrace t : terraces) if (Math.hypot(t.x - px, t.z - pz) < t.r + 40) clash = true;
                if (clash) continue;
                double score = Math.abs(g - 0.4);
                if (score < bestScore) { bestScore = score; best = new double[]{px, pz, g}; }
            }
        if (best == null) return;
        float r = (float) (26 + 14 * (jit(seed, 1, 303) + 1) / 2);
        makeTerrace(best[0], best[1], r, seed);
    }

    /** The hill's fall over the whole site (a wide look, so one bump doesn't turn it). */
    private boolean makeTerrace(double px, double pz, float r, long seed) {
        double gx = (heightAt(px + 16, pz) - heightAt(px - 16, pz)) / 32, gz = (heightAt(px, pz + 16) - heightAt(px, pz - 16)) / 32;
        double g = Math.hypot(gx, gz);
        if (Double.isNaN(g) || g < 0.08) return false;
        // never down at the sea's edge: the land all round stays well above it
        for (int k = 0; k < 12; k++) {
            double a = k * Math.PI / 6, h = heightAt(px + Math.cos(a) * (r + 14), pz + Math.sin(a) * (r + 14));
            if (Double.isNaN(h) || h < SEA_TOP + 5) return false;
        }
        float ax = (float) (-gx / g), az = (float) (-gz / g);
        double crest = heightAt(px, pz);
        for (double d = 0; d <= r * 2.2; d += 4) {
            double h = heightAt(px - ax * d, pz - az * d);
            if (!Double.isNaN(h)) crest = Math.max(crest, h);
        }
        double foot = heightAt(px + ax * r, pz + az * r);
        if (Double.isNaN(foot)) return false;
        Terrace t = new Terrace((float) px, (float) pz, r, ax, az, (float) Math.min(g, 0.75), (float) heightAt(px, pz), seed);
        t.top = (float) (foot + 0.75 * (crest - foot));
        t.dig(heights.get());
        terraces.add(t);
        return true;
    }

    private boolean nearWater(double x, double z, double r) {
        for (Segment s : segments) {
            if (x < s.minX - r || x > s.maxX + r || z < s.minZ - r || z > s.maxZ + r) continue;
            for (int i = 0; i < s.x.length; i++) if (Math.hypot(s.x[i] - x, s.z[i] - z) < r + s.w[i]) return true;
        }
        for (Lake l : lakes) if (l.sd(x, z) > -r) return true;
        return false;
    }

    private void canalFor(float vx, float vz) {
        // the nearest running water, not too far off, and above the sea
        double best = 260; Segment bs = null; int bi = -1;
        for (Segment s : segments) {
            if (vx < s.minX - 270 || vx > s.maxX + 270 || vz < s.minZ - 270 || vz > s.maxZ + 270) continue;
            for (int i = 0; i < s.x.length; i++) {
                if (s.level[i] < SEA_TOP || s.w[i] > 12) continue;
                double d = Math.hypot(s.x[i] - vx, s.z[i] - vz);
                if (d < best) { best = d; bs = s; bi = i; }
            }
        }
        if (bs == null || best < 50) return;
        dbgCanal[3]++;
        int level = (int) Math.floor(bs.level[bi]);
        int sx = (int) Math.floor(bs.x[bi]), sz = (int) Math.floor(bs.z[bi]);
        // the canal ends out among the fields: 46 blocks from the village's middle, on the river's side
        double dl = Math.hypot(sx - vx, sz - vz);
        int tx = (int) Math.floor(vx + (sx - vx) / dl * 46), tz = (int) Math.floor(vz + (sz - vz) / dl * 46);
        boolean xFirst = Math.abs(tx - sx) >= Math.abs(tz - sz);
        int kx = xFirst ? tx : sx, kz = xFirst ? sz : tz;
        // walk out from the river: stop where the ground falls below the water or climbs too high to cut through
        int[] end1 = walk(sx, sz, kx, kz, level, true);
        if (end1 == null) return;
        dbgCanal[4]++;
        Canal a = new Canal(sx, sz, end1[0], end1[1], level, vx, vz);
        int len = Math.abs(end1[0] - sx) + Math.abs(end1[1] - sz);
        Canal b = null;
        int[] far = end1;
        if (end1[0] == kx && end1[1] == kz && (kx != tx || kz != tz)) {
            int[] end2 = walk(kx, kz, tx, tz, level, false);
            if (end2 != null) { b = new Canal(kx, kz, end2[0], end2[1], level, vx, vz); len += Math.abs(end2[0] - kx) + Math.abs(end2[1] - kz); far = end2; }
        }
        if (len < 24) return;
        canals.add(a);
        if (b != null) canals.add(b);
        // out among the fields the water is shared out: parallel channels beside the last run, a few blocks apart,
        // fed from a cross channel at its end, so it reads as a field of paddies, not a single ditch
        int fx = far[0], fz = far[1];
        int nx = b != null ? kx : sx, nz = b != null ? kz : sz;
        int ddx = Integer.signum(fx - nx), ddz = Integer.signum(fz - nz);
        int runLen = Math.abs(fx - nx) + Math.abs(fz - nz);
        if (runLen < 12 || (ddx != 0) == (ddz != 0)) return;
        int px = -ddz, pz = ddx;                                        // across the run
        long hs = (long) (vx * 31 + vz * 17);
        int count = 2 + (int) ((jit(hs, 3, 81) + 1) / 2 * 2.99);        // 2 to 4 more channels
        int lo = 0, hi = 0;
        for (int i = 0; i < count; i++) {
            int o = 4 * ((i / 2) + 1) * (i % 2 == 0 ? 1 : -1);
            int ox = fx + px * o, oz = fz + pz * o;
            double share = 0.55 + 0.45 * (jit(hs, i, 82) + 1) / 2;
            int want = Math.max(14, (int) (runLen * share));
            int[] end = walk(ox, oz, ox - ddx * want, oz - ddz * want, level, false);
            if (end == null || Math.abs(end[0] - ox) + Math.abs(end[1] - oz) < 10) continue;
            canals.add(new Canal(ox, oz, end[0], end[1], level, vx, vz));
            lo = Math.min(lo, o); hi = Math.max(hi, o);
        }
        if (hi > lo) canals.add(new Canal(fx + px * lo, fz + pz * lo, fx + px * hi, fz + pz * hi, level, vx, vz));   // the cross channel feeding them
    }

    /** Along a straight run, how far the ground stays fit for a canal (a little above the water, never far above). */
    private int[] walk(int x0, int z0, int x1, int z1, int level, boolean fromRiver) {
        int dx = Integer.signum(x1 - x0), dz = Integer.signum(z1 - z0), n = Math.abs(x1 - x0) + Math.abs(z1 - z0);
        int lastX = x0, lastZ = z0;
        for (int k = 0; k <= n; k += 2) {
            int x = x0 + dx * k, z = z0 + dz * k;
            double h = heightAt(x, z);
            if (Double.isNaN(h)) break;
            boolean byRiver = fromRiver && k < 12;
            if (!byRiver && (h < level + 0.5 || h > level + 9)) break;
            lastX = x; lastZ = z;
        }
        if (lastX == x0 && lastZ == z0) return null;
        return new int[]{lastX, lastZ};
    }

    public int[] baysAt(int x, int z) { int[] r = at(chunkBays, x, z); return r == null ? NONE : r; }

    /**
     * Bays: on low stretches of coast, here and there, the sea reaches inland in a broad oval cove with beaches round
     * it. Never near a delta, a lake, or another bay.
     */
    private void bays(int[] lakeOf) {
        int lo = MARGIN / CELL - BAND / CELL - 4, hi = (MARGIN + SIZE) / CELL + BAND / CELL + 4;
        for (int j = Math.max(2, lo); j < Math.min(N - 2, hi); j++) for (int i = Math.max(2, lo); i < Math.min(N - 2, hi); i++) {
            int k = i + j * N;
            if (d.sea(k) || lakeOf[k] >= 0 || d.h[k] > 74 || d.h[k] < Drainage.SEA + 1) continue;
            int gi = Math.floorDiv(d.x0, CELL) + i, gj = Math.floorDiv(d.z0, CELL) + j;
            if (jit(gi, gj, 61) < 0.55) continue;
            double sx = 0, sz = 0; int sea = 0;
            for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                if (d.sea(k + di + dj * N)) { sx += di; sz += dj; sea++; }
            }
            if (sea < 2 || sea > 5) continue;
            double sl = Math.hypot(sx, sz);
            if (sl < 0.5) continue;
            sx /= sl; sz /= sl;
            // the sea two cells out really is sea (open water, not a pocket)
            int fi = i + (int) Math.round(sx * 2), fj = j + (int) Math.round(sz * 2);
            if (fi < 0 || fj < 0 || fi >= N || fj >= N || !d.sea(fi + fj * N)) continue;
            double u = (jit(gi, gj, 62) + 1) / 2;
            float ra = (float) (18 + 14 * u), rb = (float) (ra * (1.2 + 0.4 * (jit(gi, gj, 63) + 1) / 2));
            double cx = px(k) - sx * ra * 0.3, cz = pz(k) - sz * ra * 0.3;
            boolean clash = false;
            for (Bay b : bays) if (Math.hypot(b.x - cx, b.z - cz) < 360) clash = true;
            for (Delta dl : deltas) if (Math.hypot(dl.x - cx, dl.z - cz) < dl.r + 160) clash = true;
            for (Lake lk : lakes) if (lk.sd(cx, cz) > -60) clash = true;
            if (clash) continue;
            bays.add(new Bay((float) cx, (float) cz, (float) sx, (float) sz, ra, rb, (float) (4 + 4 * (jit(gi, gj, 64) + 1) / 2)));
        }
    }

    public int[] deltasAt(int x, int z) { int[] r = at(chunkDeltas, x, z); return r == null ? NONE : r; }

    /**
     * A delta, where a big river reaches the sea across low ground: two or three distributaries branch off a little
     * inland and fan out to the coast, each its own channel at sea level, and the land between them is brought down to
     * low sandbars and islets (see Planner). Only on low coasts; cliffs keep a single mouth.
     */
    private void delta(int k, int dn, Segment main) {
        float hw = halfWidth(k);
        if (hw < 4 || narrow[k] || d.h[k] > 72) return;
        int up = -1;
        for (int dd = 0; dd < 8; dd++) {                                         // upstream cell, to start the fan further inland
            int i = k % N + Drainage.DX[dd], j = k / N + Drainage.DZ[dd];
            if (i < 0 || j < 0 || i >= N || j >= N) continue;
            int u = i + j * N;
            if (d.down(u) == k && fed[u] && (up < 0 || d.acc[u] > d.acc[up])) up = u;
        }
        if (up >= 0 && d.h[up] > 74) up = -1;
        double sx = up >= 0 ? (px(up) + px(k)) / 2 : px(k), sz = up >= 0 ? (pz(up) + pz(k)) / 2 : pz(k);
        double mx = px(dn), mz = pz(dn), dl = Math.hypot(mx - sx, mz - sz);
        if (dl < 1) return;
        double ux = (mx - sx) / dl, uz = (mz - sz) / dl;
        int gi = Math.floorDiv(d.x0, CELL) + k % N, gj = Math.floorDiv(d.z0, CELL) + k / N;
        int branches = hw >= 7 ? 3 : 2;
        double reach = 0;
        int made = 0;
        for (int b = 0; b < branches; b++) {
            double side = branches == 3 ? (b - 1) : (b == 0 ? -1 : 1);
            if (side == 0) side = 0.35 * jit(gi, gj, 41);                          // the middle one wanders a little
            double ang = side * Math.toRadians(30 + 10 * jit(gi, gj, 42 + b));
            double tx = ux * Math.cos(ang) - uz * Math.sin(ang), tz = ux * Math.sin(ang) + uz * Math.cos(ang);
            double len = -1;
            for (double l = dl + 16; l <= dl + 112; l += 8) {
                int c = cellAt((int) (sx + tx * l), (int) (sz + tz * l));
                if (c >= 0 && d.sea(c)) { len = l + 12; break; }
            }
            if (len < 0) continue;
            double ex = sx + tx * len, ez = sz + tz * len, cx = sx + ux * len * 0.45, cz = sz + uz * len * 0.45;
            int n = (int) Math.ceil(len / 2) + 1;
            float[] xs = new float[n], zs = new float[n], lv = new float[n], ws = new float[n];
            float bw = (float) Math.max(2, hw * (0.38 + 0.12 * (jit(gi, gj, 45 + b) + 1)));
            for (int i = 0; i < n; i++) {
                double t = i / (double) (n - 1), a = (1 - t) * (1 - t), bb = 2 * (1 - t) * t, cc = t * t;
                xs[i] = (float) (a * sx + bb * cx + cc * ex);
                zs[i] = (float) (a * sz + bb * cz + cc * ez);
                lv[i] = SEA_TOP;
                ws[i] = (float) (bw * (0.75 + 0.5 * t));                              // widening to the sea
            }
            Segment s = new Segment(xs, zs, lv, ws, (float) (bw * 1.25), (float) Math.min(5, 1 + 0.35 * bw), main.acc * 0.4f);
            int pad = (int) Math.ceil(bw * 1.25) + REACH + 2;
            float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]); }
            s.minX = (int) Math.floor(minX) - pad; s.minZ = (int) Math.floor(minZ) - pad; s.maxX = (int) Math.ceil(maxX) + pad; s.maxZ = (int) Math.ceil(maxZ) + pad;
            segments.add(s);
            reach = Math.max(reach, len);
            made++;
        }
        if (made > 0) deltas.add(new Delta((float) sx, (float) sz, (float) ux, (float) uz, (float) reach, (float) Math.toRadians(50)));
    }

    /**
     * Falls along a channel: wherever its water drops 5 or more within a few blocks (over a ledge or a cliff), a
     * bowl is set where it lands, a little past the foot, its surface at the water's lower level.
     */
    /** How wide a stream pours over a step: a narrow lip for a stream, wider for a river. */
    public static float lipHalf(float w) { return w < 2.6f ? 1.15f : Math.min(w, Math.max(1.2f, w * 0.45f)); }

    /**
     * Every step down a stream ends in a bowl: the water collects in it and pours on out of a narrow lip on the side
     * it runs on, down to the next. Steps one straight after another make a single fall into one bowl at its foot.
     */
    private void findFalls(Segment s) {
        int n = s.level.length;
        for (int i = 0; i + 1 < n; i++) {
            if (s.level[i] - s.level[i + 1] < 2) continue;
            int j = i + 1;
            while (j + 1 < n && s.level[j] - s.level[j + 1] >= 2) j++;
            float lvl = s.level[j], drop = s.level[i] - lvl;
            if (lvl <= SEA_TOP + 1) { i = j; continue; }
            // the bowl reaches back under the lip, where the falling water actually lands, and on past the foot
            // centred on the foot of the (last) drop, reaching back under its brink, where the water comes over
            int c = j;
            int a = Math.max(0, c - 1), b = Math.min(n - 1, c + 2);
            double ex = s.x[b] - s.x[a], ez = s.z[b] - s.z[a], el = Math.max(1e-6, Math.hypot(ex, ez));
            double bx = (s.x[j - 1] + s.x[j]) / 2, bz = (s.z[j - 1] + s.z[j]) / 2;
            double reachLip = Math.hypot(s.x[c] - bx, s.z[c] - bz) + 1.6, reachFoot = 0;
            float base = drop < 5 ? 2.3f : 2.8f;
            float r = (float) Math.max(Math.max(base, s.w[c] * 1.4 + 1.4 + Math.min(2, drop * 0.08)), Math.max(reachLip, reachFoot));
            float depth = (float) Math.min(4, 1.2 + drop * 0.15);
            Pool pl = new Pool(s.x[c], s.z[c], r, (int) lvl, depth, (float) (ex / el), (float) (ez / el), drop);
            pl.lip = lipHalf(s.w[c]);
            pools.add(pl);
            i = j;
        }
    }
    private int[] at(int[][] idx, int x, int z) {
        int cx = (x >> 4) - cx0, cz = (z >> 4) - cz0;
        if (cx < 0 || cz < 0 || cx >= cw || cz >= cw) return null;
        return idx[cx + cz * cw];
    }

    public boolean channel(int k) { return fed[k]; }

    private boolean[] fillCell;
    /** Every FG blocks over the region and its band: the level vanilla water below it is filled to, or NaN. */
    static final int FG = 8;
    private float[] fillTop;
    private int fgx0, fgz0, fgw;

    /**
     * Where vanilla's water is filled in: its rivers (found by their biome, every few blocks), and pools at sea level
     * inland that aren't ours, but never swamps (their water is theirs), and sloping down to the sea near the coast.
     */
    private void fillGrid(Biomes biomes, int[] lakeOf, ForkJoinPool pool) {
        fgx0 = bx0 - BAND; fgz0 = bz0 - BAND; fgw = (SIZE + 2 * BAND) / FG + 1;
        int W = fgw;
        byte[] fl = new byte[W * W];
        if (biomes != null)
            pool.submit(() -> IntStream.range(0, W).parallel().forEach(j -> {
                for (int i = 0; i < W; i++) fl[i + j * W] = (byte) biomes.at((fgx0 + i * FG) >> 2, (fgz0 + j * FG) >> 2);
            })).join();
        // distance to the sea (blocks), from ocean biome or planned sea
        boolean[] sea = new boolean[W * W];
        for (int j = 0; j < W; j++) for (int i = 0; i < W; i++) {
            int x = fgx0 + i * FG, z = fgz0 + j * FG, k = cellAt(x, z);
            sea[i + j * W] = (fl[i + j * W] & 2) != 0 || (k >= 0 && d.sea(k) && (fl[i + j * W] & 1) == 0);
        }
        float[] dist = dist(sea, W, W, false);
        // a vanilla river's water runs a little past its biome's edge: reach 3 points (24 blocks) beyond it
        boolean[] riverMask = new boolean[W * W];
        for (int c = 0; c < W * W; c++) riverMask[c] = (fl[c] & 1) != 0;
        float[] toRiver = dist(riverMask, W, W, false);
        fillTop = new float[W * W];
        for (int j = 0; j < W; j++) for (int i = 0; i < W; i++) {
            int c = i + j * W, x = fgx0 + i * FG, z = fgz0 + j * FG, k = cellAt(x, z);
            boolean river = toRiver[c] <= 3.5, swamp = (fl[c] & 4) != 0;
            if (sea[c] || !river || swamp || (k >= 0 && lakeOf[k] >= 0)) { fillTop[c] = Float.NaN; continue; }
            fillTop[c] = (float) Math.min(63, 55 + dist[c] * FG / 3.0);
        }
    }

    private int cellAt(int x, int z) {
        int i = Math.floorDiv(x - d.x0, CELL), j = Math.floorDiv(z - d.z0, CELL);
        return i < 0 || j < 0 || i >= N || j >= N ? -1 : i + j * N;
    }

    /** A spot in a vanilla river well inland (for testing), or null. */
    public int[] someFilledRiver(int nearX, int nearZ, int within) {
        for (int c = 0; c < fillTop.length; c++) {
            if (Float.isNaN(fillTop[c]) || fillTop[c] < 63) continue;
            int x = fgx0 + (c % fgw) * FG, z = fgz0 + (c / fgw) * FG;
            if (Math.abs(x - nearX) < within && Math.abs(z - nearZ) < within) return new int[]{x, z};
        }
        return null;
    }

    /** The level vanilla water is filled in to at this spot, or NaN (nearest grid point). */
    public double fillAt(double x, double z) {
        // blended between the grid points round it (no square shelves), and fading out where most of them aren't filled
        double gx = (x - fgx0) / FG, gz = (z - fgz0) / FG;
        int i = (int) Math.floor(gx), j = (int) Math.floor(gz);
        if (i < 0 || j < 0 || i + 1 >= fgw || j + 1 >= fgw) return Double.NaN;
        double fx = gx - i, fz = gz - j, sum = 0, w = 0;
        for (int b = 0; b < 2; b++) for (int a = 0; a < 2; a++) {
            float v = fillTop[(i + a) + (j + b) * fgw];
            double k = (a == 0 ? 1 - fx : fx) * (b == 0 ? 1 - fz : fz);
            if (Float.isNaN(v)) continue;
            sum += v * k; w += k;
        }
        if (w < 0.5) return Double.NaN;
        return sum / w - (1 - w) * 6;                               // sinking away toward the unfilled side
    }

    // ------------------------------------------------------------------ wetlands

    public int dbgNear, dbgLow, dbgFlat, dbgDesert, dbgDry, dbgHash, dbgHollow;
    /** Per cell: the water level of the wetland it's part of, or NaN. */
    private float[] wetLevel;

    /**
     * Wetlands: flat, low ground beside a sizeable river (two or more streams joined) or a lake, no more than a few
     * blocks above its water, gathered into patches; about half the patches that qualify become wetland (the others
     * stay meadow), so not every floodplain is a marsh.
     */
    private void wetlands(float[] L, int[] lakeOf) {
        int NN = N * N;
        wetLevel = new float[NN];
        Arrays.fill(wetLevel, Float.NaN);
        float[] cand = new float[NN];
        Arrays.fill(cand, Float.NaN);
        for (int k = 0; k < NN; k++) {
            if (d.sea(k) || lakeOf[k] >= 0 || fed[k]) continue;
            float hk = d.valley[k] ? 63 : d.h[k];                          // an old vanilla river valley, filled in to just above the sea
            int i = k % N, j = k / N;
            if (i < 2 || j < 2 || i >= N - 2 || j >= N - 2) continue;
            float w = Float.NaN;
            for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
                int q = (i + di) + (j + dj) * N;
                boolean water = (fed[q] && order[q] >= 2) || lakeOf[q] >= 0;
                if (water && !Float.isNaN(L[q]) && L[q] > SEA_TOP && (Float.isNaN(w) || L[q] < w)) w = L[q];
            }
            if (Float.isNaN(w)) continue;
            dbgNear++;
            if (hk > w + 6 || hk < w - 1) continue;
            dbgLow++;
            double slope = 0;
            for (int dd = 0; dd < 8; dd += 2) {
                int nb = (i + Drainage.DX[dd]) + (j + Drainage.DZ[dd]) * N;
                float hn = d.valley[nb] ? 63 : d.sea(nb) ? hk : d.h[nb];
                slope = Math.max(slope, Math.abs(hk - hn));
            }
            if (slope > 5) continue;
            dbgFlat++;
            cand[k] = w;
        }
        // patches, each kept or not as a whole
        boolean[] seen = new boolean[NN];
        int[] q = new int[NN];
        int gi0 = Math.floorDiv(d.x0, CELL), gj0 = Math.floorDiv(d.z0, CELL);
        for (int s0 = 0; s0 < NN; s0++) {
            if (seen[s0] || Float.isNaN(cand[s0])) continue;
            int qh = 0, qt = 0, minK = s0;
            q[qt++] = s0; seen[s0] = true;
            while (qh < qt) {
                int c = q[qh++], ci = c % N, cj = c / N;
                minK = Math.min(minK, c);
                for (int dd = 0; dd < 8; dd += 2) {
                    int i = ci + Drainage.DX[dd], j = cj + Drainage.DZ[dd];
                    if (i < 0 || j < 0 || i >= N || j >= N) continue;
                    int k = i + j * N;
                    if (!seen[k] && !Float.isNaN(cand[k])) { seen[k] = true; q[qt++] = k; }
                }
            }
            if (qt < 3) continue;
            // decided by the patch's lowest point on the world grid (sorting by water level would do too; this is stable)
            int lowest = q[0];
            for (int t = 0; t < qt; t++) if (d.h[q[t]] < d.h[lowest] || (d.h[q[t]] == d.h[lowest] && q[t] < lowest)) lowest = q[t];
            if ((jit(gi0 + lowest % N, gj0 + lowest / N, 61) + 1) / 2 > 0.55) continue;
            for (int t = 0; t < qt; t++) wetLevel[q[t]] = cand[q[t]];
        }
    }

    /** The land's height here, smoothed between cell centres (the plan's coarse view of it). */
    public double heightAt(double x, double z) {
        double u = (x - d.x0) / CELL - 0.5, v = (z - d.z0) / CELL - 0.5;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        if (i < 0 || j < 0 || i >= N - 1 || j >= N - 1) return Double.NaN;
        double fu = u - i, fv = v - j;
        float a = hv(i + j * N), b = hv(i + 1 + j * N), c = hv(i + (j + 1) * N), e = hv(i + 1 + (j + 1) * N);
        return (a * (1 - fu) + b * fu) * (1 - fv) + (c * (1 - fu) + e * fu) * fv;
    }

    private float hv(int k) { return d.valley[k] ? 63 : d.h[k]; }

    /** Centres of wetland cells (for testing). */
    public List<int[]> wetlandCells() {
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k < N * N; k++) if (!Float.isNaN(wetLevel[k])) out.add(new int[]{d.x0 + (k % N) * CELL + CELL / 2, d.z0 + (k / N) * CELL + CELL / 2});
        return out;
    }

    /** The wetland at this spot: its water level and how deep in it the spot lies (0..1), or null. */
    public double[] wetlandAt(double x, double z) {
        double u = (x - d.x0) / CELL - 0.5, v = (z - d.z0) / CELL - 0.5;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        if (i < 0 || j < 0 || i >= N - 1 || j >= N - 1) return null;
        double fu = u - i, fv = v - j, m = 0, lvl = Double.NaN, best = -1;
        int[] ks = {i + j * N, i + 1 + j * N, i + (j + 1) * N, i + 1 + (j + 1) * N};
        double[] wts = {(1 - fu) * (1 - fv), fu * (1 - fv), (1 - fu) * fv, fu * fv};
        for (int t = 0; t < 4; t++) {
            if (Float.isNaN(wetLevel[ks[t]])) continue;
            m += wts[t];
            if (wts[t] > best) { best = wts[t]; lvl = wetLevel[ks[t]]; }
        }
        return m <= 0 ? null : new double[]{lvl, m};
    }

    /** How much this spot lies in a vanilla river's valley (0..1, smoothed between cell centres). */
    public double valleyAt(double x, double z) {
        double u = (x - d.x0) / CELL - 0.5, v = (z - d.z0) / CELL - 0.5;
        int i = (int) Math.floor(u), j = (int) Math.floor(v);
        if (i < 0 || j < 0 || i >= N - 1 || j >= N - 1) return 0;
        double fu = u - i, fv = v - j;
        boolean[] f = fillCell;
        double a = f[i + j * N] ? 1 : 0, b = f[i + 1 + j * N] ? 1 : 0, c = f[i + (j + 1) * N] ? 1 : 0, e = f[i + 1 + (j + 1) * N] ? 1 : 0;
        return (a * (1 - fu) + b * fu) * (1 - fv) + (c * (1 - fu) + e * fu) * fv;
    }

    // ------------------------------------------------------------------ river systems

    /** A river system must run at least this far (blocks, from its furthest source to the sea) to have water at all. */
    public static final double MIN_LENGTH = 800;
    /** Share of river systems that stay narrow all the way down. */
    public static final double NARROW_SHARE = 0.05;
    /** Streams rise only this high or higher (the top of the ground, y). */
    public static final double HIGHLAND = 85;
    /** Cells that must drain through a highland cell for a spring to rise there. */
    public static final float SPRING_CELLS = 3;
    /** Per cell: its system is long enough; its system stays narrow; it carries water (fed from a highland spring or a lake); a spring rises here. */
    boolean[] longNet, narrow, fed, head;
    /** Per cell: cells from here to the sea; the stream's order (1 for a stream from a spring, one more where two of the same order join). */
    int[] toSea, order;

    /**
     * Each cell belongs to the river system of the cell where its water reaches the sea (or leaves the plan). Short
     * systems (little coastal runs) carry no water, so rivers rise well inland; a few systems stay narrow throughout.
     */
    private void networks() {
        int NN = N * N;
        Integer[] order = new Integer[NN];
        for (int k = 0; k < NN; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Float.compare(d.fill[b], d.fill[a]));
        float[] len = new float[NN];                       // longest run of water down to here
        for (int k : order) {
            int dn = d.down(k);
            if (dn >= 0) len[dn] = (float) Math.max(len[dn], len[k] + CELL * Drainage.DL[d.dir[k]]);
        }
        int[] outlet = new int[NN];
        boolean[] edge = new boolean[NN];
        toSea = new int[NN];
        for (int o = NN - 1; o >= 0; o--) {                // downstream first
            int k = order[o], dn = d.down(k);
            if (dn < 0) { outlet[k] = k; edge[k] = d.dir[k] == -2; toSea[k] = 0; }
            else if (d.sea(dn)) { outlet[k] = k; toSea[k] = 1; }
            else { outlet[k] = outlet[dn]; edge[k] = edge[dn]; toSea[k] = toSea[dn] + 1; }
        }
        longNet = new boolean[NN]; narrow = new boolean[NN];
        int gi0 = Math.floorDiv(d.x0, CELL), gj0 = Math.floorDiv(d.z0, CELL);
        for (int k = 0; k < NN; k++) {
            int o = outlet[k];
            longNet[k] = edge[k] || len[o] >= MIN_LENGTH;
            narrow[k] = (jit(gi0 + o % N, gj0 + o / N, 11) + 1) / 2 < NARROW_SHARE;
        }
        // which cells carry water: streams rise only in the highlands (or flow out of a lake), then run on down;
        // and how big each stream is: two streams of the same size joining make a bigger one
        fed = new boolean[NN]; head = new boolean[NN];
        this.order = new int[NN];
        boolean[] fromAbove = new boolean[NN];
        int[] ordMax = new int[NN], ordCnt = new int[NN];
        for (int k : order) {
            boolean lake = d.lake[k] >= 0;
            if (!d.sea(k) && longNet[k]) {
                if (fromAbove[k]) fed[k] = true;
                else if (!lake && d.h[k] >= HIGHLAND && d.acc[k] >= SPRING_CELLS) { fed[k] = true; head[k] = true; }
            }
            if (fed[k]) this.order[k] = ordCnt[k] >= 2 ? ordMax[k] + 1 : Math.max(1, ordMax[k]);
            int dn = d.down(k);
            if (dn < 0) continue;
            if (fed[k] || lake) fromAbove[dn] = true;
            int o = fed[k] ? this.order[k] : 0;
            if (o > ordMax[dn]) { ordMax[dn] = o; ordCnt[dn] = 1; }
            else if (o > 0 && o == ordMax[dn]) ordCnt[dn]++;
        }
    }

    /** How much a stream's width wanders at this cell (it narrows and widens a little along its course). */
    double wander(int k) {
        int gi = Math.floorDiv(d.x0, CELL) + k % N, gj = Math.floorDiv(d.z0, CELL) + k / N;
        return 1 + 0.3 * jit(gi, gj, 13);
    }

    /** Half the width of the water, in blocks: from the water gathered above (in cells), flaring toward the sea. */
    public float halfWidth(int k) {
        int o = Math.max(1, order[k]);
        double hw = 0.6 * Math.pow(1.7, o - 1) + 0.12 * Math.pow(d.acc[k], 0.6);
        if (narrow[k]) return (float) Math.min(hw * 0.45, 3);
        if (toSea[k] <= 6) hw *= 1 + 0.15 * (7 - toSea[k]);          // the last stretch opens out to the sea
        return (float) Math.min(22, hw);
    }

    // ------------------------------------------------------------------ geometry

    /** A cell's point: its centre, shuffled a little (the same wherever it's worked out, so neighbouring regions agree). */
    double px(int k) { int gi = Math.floorDiv(d.x0, CELL) + k % N, gj = Math.floorDiv(d.z0, CELL) + k / N; return gi * (double) CELL + CELL / 2.0 + jit(gi, gj, 1) * JITTER; }
    double pz(int k) { int gi = Math.floorDiv(d.x0, CELL) + k % N, gj = Math.floorDiv(d.z0, CELL) + k / N; return gj * (double) CELL + CELL / 2.0 + jit(gi, gj, 2) * JITTER; }

    public static double jit(long gi, long gj, int salt) {
        long h = gi * 0x9E3779B97F4A7C15L ^ gj * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    /** The channel from cell k to cell dn: a smooth curve, its level kept under the banks. */
    private Segment segment(int k, int dn, int upK, int dn2, float l0, float l1, Lake from, Drainage.Heights hs) {
        double x1 = px(k), z1 = pz(k), x2 = px(dn), z2 = pz(dn);
        double x0 = upK >= 0 ? px(upK) : 2 * x1 - x2, z0 = upK >= 0 ? pz(upK) : 2 * z1 - z2;
        double x3 = dn2 >= 0 ? px(dn2) : 2 * x2 - x1, z3 = dn2 >= 0 ? pz(dn2) : 2 * z2 - z1;
        double len = Math.hypot(x2 - x1, z2 - z1);
        int n = Math.max(2, (int) Math.ceil(len / 2)) + 1;
        float acc = d.acc[k];
        float hw = halfWidth(k), depth = (float) Math.min(8, 1 + 0.35 * hw);
        float[] xs = new float[n], zs = new float[n], lv = new float[n], ws = new float[n];
        double wa = wander(k), wb = d.sea(dn) || !channel(dn) ? wa : wander(dn);
        float maxW = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            xs[i] = (float) cr(x0, x1, x2, x3, t);
            zs[i] = (float) cr(z0, z1, z2, z3, t);
            double f = wa + (wb - wa) * (3 * t * t - 2 * t * t * t);
            if (head[k]) f *= 0.55 + 0.45 * Math.min(1, t * 2.5);            // just out of the spring: a trickle widening
            ws[i] = (float) Math.max(0.6, hw * f);
            maxW = Math.max(maxW, ws[i]);
        }
        // the level: from this cell's down toward the next's, never above the ground beside the water
        double cur = l0;
        for (int i = 0; i < n; i++) {
            // leaving a lake: the lake bed doesn't count, only the ground past its shore
            if (from == null || from.sd(xs[i], zs[i]) < -2) {
                int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
                double tx = xs[b] - xs[a], tz = zs[b] - zs[a], tl = Math.hypot(tx, tz);
                double nx = tl < 1e-6 ? 0 : -tz / tl, nz = tl < 1e-6 ? 0 : tx / tl, off = ws[i] + 1.5;
                // the ground under the water and both banks (and, every other step, a little further out): the lowest
                // of them, and how rough the land is here
                double g0 = hs.at((int) Math.floor(xs[i]), (int) Math.floor(zs[i]));
                double g1 = hs.at((int) Math.floor(xs[i] + nx * off), (int) Math.floor(zs[i] + nz * off));
                double g2 = hs.at((int) Math.floor(xs[i] - nx * off), (int) Math.floor(zs[i] - nz * off));
                double lo = Math.min(g0, Math.min(g1, g2)), hi = Math.max(g0, Math.max(g1, g2));
                if (i % 2 == 0) {
                    double o2 = off + 2.5;
                    double g3 = hs.at((int) Math.floor(xs[i] + nx * o2), (int) Math.floor(zs[i] + nz * o2));
                    double g4 = hs.at((int) Math.floor(xs[i] - nx * o2), (int) Math.floor(zs[i] - nz * o2));
                    lo = Math.min(lo, Math.min(g3, g4)); hi = Math.max(hi, Math.max(g3, g4));
                }
                // in broken, rocky ground (and high up, where it mostly is) the water sits deeper in its cut, so the
                // land's dips and steps never leave it standing above them
                double sink = Math.min(4, (hi - lo) * 0.35) + (lo > 110 ? 1 : 0);
                cur = Math.min(cur, Math.floor(lo - sink) - 1);
            }
            cur = Math.max(cur, l1);
            lv[i] = (float) cur;
        }
        Segment s = new Segment(xs, zs, lv, ws, maxW, depth, acc);
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]); }
        int pad = (int) Math.ceil(maxW) + REACH + 2;
        s.minX = (int) Math.floor(minX) - pad; s.minZ = (int) Math.floor(minZ) - pad;
        s.maxX = (int) Math.ceil(maxX) + pad; s.maxZ = (int) Math.ceil(maxZ) + pad;
        return s;
    }

    private static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    // ------------------------------------------------------------------ lakes

    /** Trace each lake near the region on a finer grid; returns, per cell, the kept lake it lies in (or -1). */
    private int[] refineLakes(ThreadLocal<Drainage.Heights> hs, ForkJoinPool pool) {
        int NN = N * N, count = d.lakeLevel.length;
        int[] out = new int[NN];
        Arrays.fill(out, -1);
        if (count == 0) return out;
        int[] mi = new int[count], mj = new int[count], xi = new int[count], xj = new int[count];
        Arrays.fill(mi, N); Arrays.fill(mj, N); Arrays.fill(xi, -1); Arrays.fill(xj, -1);
        for (int k = 0; k < NN; k++) {
            int l = d.lake[k];
            if (l < 0) continue;
            int i = k % N, j = k / N;
            mi[l] = Math.min(mi[l], i); mj[l] = Math.min(mj[l], j); xi[l] = Math.max(xi[l], i); xj[l] = Math.max(xj[l], j);
        }
        int lo = (MARGIN - BAND) / CELL, hi = (MARGIN + SIZE + BAND) / CELL;
        List<Integer> keep = new ArrayList<>();
        for (int l = 0; l < count; l++)
            if (xi[l] >= lo && mi[l] <= hi && xj[l] >= lo && mj[l] <= hi && mi[l] > 0 && mj[l] > 0 && xi[l] < N - 1 && xj[l] < N - 1) keep.add(l);
        Lake[] made = new Lake[count];
        pool.submit(() -> keep.parallelStream().forEach(l -> made[l] = traceLake(l, mi[l], mj[l], xi[l], xj[l], hs.get()))).join();
        int[] idx = new int[count];
        Arrays.fill(idx, -1);
        for (int l : keep) if (made[l] != null) { idx[l] = lakes.size(); lakes.add(made[l]); }
        for (int k = 0; k < NN; k++) if (d.lake[k] >= 0) out[k] = idx[d.lake[k]];
        return out;
    }

    /** Flood the hollow on a fine grid from its lowest point; if the water would escape the hollow's box, it's lowered. */
    /** Lakes with their water this high or more are mountain tarns: no more than MOUNTAIN_LAKE blocks across. */
    static final int MOUNTAIN_HEIGHT = 105, MOUNTAIN_LAKE = 80;

    private Lake traceLake(int l, int i0, int j0, int i1, int j1, Drainage.Heights hs) {
        int bx = d.x0 + (i0 - 1) * CELL, bz = d.z0 + (j0 - 1) * CELL;
        int sx = (i1 - i0 + 3) * CELL, sz = (j1 - j0 + 3) * CELL;
        int step = 8;
        while (Math.max(sx, sz) / step > 96) step += 8;
        int w = sx / step + 1, h = sz / step + 1;
        float[] g = new float[w * h];
        float min = Float.MAX_VALUE; int seed = -1;
        for (int b = 0; b < h; b++) for (int a = 0; a < w; a++) {
            int x = bx + a * step, z = bz + b * step;
            g[a + b * w] = (float) hs.at(x, z);
            int ci = (x - d.x0) / CELL, cj = (z - d.z0) / CELL;
            boolean inside = ci >= 0 && cj >= 0 && ci < N && cj < N && d.lake[ci + cj * N] == l;
            if (inside && g[a + b * w] < min) { min = g[a + b * w]; seed = a + b * w; }
        }
        if (seed < 0) return null;
        // the spill height: the lowest pass out of the hollow (lowest-first flood from the seed until it reaches the
        // box's edge). Water holds at any level up to it; the coarse grid can still miss a narrow low notch in the rim
        // and nothing is built to close one, so settle a block below.
        float spill = Float.MAX_VALUE;
        {
            boolean[] seen = new boolean[w * h];
            java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>((p1, p2) -> Long.compare(p1[0], p2[0]));
            pq.add(new long[]{Float.floatToIntBits(Math.max(0, g[seed])) & 0xffffffffL, seed}); seen[seed] = true;
            float run = -Float.MAX_VALUE;
            while (!pq.isEmpty()) {
                long[] e = pq.poll();
                int c = (int) e[1];
                run = Math.max(run, g[c]);
                int a = c % w, b = c / w;
                if (a == 0 || b == 0 || a == w - 1 || b == h - 1) { spill = run; break; }
                int[] nb = {c - 1, c + 1, c - w, c + w};
                for (int k : nb) if (!seen[k]) { seen[k] = true; pq.add(new long[]{Float.floatToIntBits(Math.max(0, g[k])) & 0xffffffffL, k}); }
            }
        }
        int level = (int) Math.min(Math.floor(d.lakeLevel[l]), Math.floor(spill)) - 1;
        if (level <= min + 1) return null;
        boolean[] in = new boolean[w * h];
        int[] q = new int[w * h];
        // the coarse flood first, for the lake's rough extent
        java.util.Arrays.fill(in, false);
        {
            int qh = 0, qt = 0;
            q[qt++] = seed; in[seed] = true;
            while (qh < qt) {
                int c = q[qh++];
                int a = c % w, b = c / w;
                if (a == 0 || b == 0 || a == w - 1 || b == h - 1) return null;
                int[] nb = {c - 1, c + 1, c - w, c + w};
                for (int k : nb) if (!in[k] && g[k] < level) { in[k] = true; q[qt++] = k; }
            }
        }
        int ca0 = w, ca1 = 0, cb0 = h, cb1 = 0;
        for (int c = 0; c < w * h; c++) if (in[c]) { ca0 = Math.min(ca0, c % w); ca1 = Math.max(ca1, c % w); cb0 = Math.min(cb0, c / w); cb1 = Math.max(cb1, c / w); }
        // then the real shape, from the land's heights a few blocks apart over the whole of it: a notch in the rim the
        // coarse grid stepped over, or low ground past its edge (common in rocky mountains), would otherwise leave the
        // water standing as a wall. The lake settles to its true spill height and takes the shape the land gives it.
        int fx0 = bx + (ca0 - 2) * step, fz0 = bz + (cb0 - 2) * step;
        int spanX = (ca1 - ca0 + 4) * step, spanZ = (cb1 - cb0 + 4) * step;
        int fs = 2;
        while (Math.max(spanX, spanZ) / fs > 160) fs += 2;
        int fw = spanX / fs + 1, fh = spanZ / fs + 1;
        float[] fg = new float[fw * fh];
        int fseed = -1; float fmin = Float.MAX_VALUE;
        for (int b = 0; b < fh; b++) for (int a = 0; a < fw; a++) {
            int x = fx0 + a * fs, z = fz0 + b * fs;
            float v = (float) hs.at(x, z);
            fg[a + b * fw] = v;
            int ga = Math.round((x - bx) / (float) step), gb = Math.round((z - bz) / (float) step);
            boolean inside = ga >= 0 && gb >= 0 && ga < w && gb < h && in[ga + gb * w];
            if (inside && v < fmin) { fmin = v; fseed = a + b * fw; }
        }
        if (fseed < 0) return null;
        // the true spill: the lowest way out to the edge of this patch
        {
            boolean[] seen = new boolean[fw * fh];
            java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>((p1, p2) -> Long.compare(p1[0], p2[0]));
            pq.add(new long[]{Float.floatToIntBits(Math.max(0, fg[fseed])) & 0xffffffffL, fseed}); seen[fseed] = true;
            float run = -Float.MAX_VALUE, fspill = Float.MAX_VALUE;
            while (!pq.isEmpty()) {
                long[] e = pq.poll();
                int c = (int) e[1];
                run = Math.max(run, fg[c]);
                if (run >= level) break;                                    // it already holds at this level
                int a = c % fw, b = c / fw;
                if (a == 0 || b == 0 || a == fw - 1 || b == fh - 1) { fspill = run; break; }
                int[] nb = {c - 1, c + 1, c - fw, c + fw};
                for (int k : nb) if (!seen[k]) { seen[k] = true; pq.add(new long[]{Float.floatToIntBits(Math.max(0, fg[k])) & 0xffffffffL, k}); }
            }
            if (fspill < Float.MAX_VALUE) level = Math.min(level, (int) Math.floor(fspill));
        }
        if (level <= fmin + 1) return null;
        boolean[] fin = new boolean[fw * fh];
        int[] fq = new int[fw * fh];
        int area = 0;
        // high up (mountain tarns) a lake stays small: at most MOUNTAIN_LAKE blocks across. If the hollow would hold a
        // bigger one, the water settles lower until it fits (or there's no lake there at all)
        for (;;) {
            java.util.Arrays.fill(fin, false);
            int qh = 0, qt = 0, a0 = fw, a1 = 0, b0 = fh, b1 = 0;
            area = 0;
            fq[qt++] = fseed; fin[fseed] = true;
            while (qh < qt) {
                int c = fq[qh++]; area++;
                int a = c % fw, b = c / fw;
                if (a == 0 || b == 0 || a == fw - 1 || b == fh - 1) return null;
                a0 = Math.min(a0, a); a1 = Math.max(a1, a); b0 = Math.min(b0, b); b1 = Math.max(b1, b);
                int[] nb = {c - 1, c + 1, c - fw, c + fw};
                for (int k : nb) if (!fin[k] && fg[k] < level) { fin[k] = true; fq[qt++] = k; }
            }
            int span = Math.max(a1 - a0 + 1, b1 - b0 + 1) * fs;
            if (level < MOUNTAIN_HEIGHT || span <= MOUNTAIN_LAKE) break;
            level--;
            if (level <= fmin + 1) return null;
        }
        if (area * fs * fs < 4 * CELL * CELL / 2) return null;     // too small once it holds
        return new Lake(level, fx0, fz0, fs, fw, fh, signedDistance(fin, fw, fh, fs), (float) Math.min(10, 2.5 + 0.035 * Math.sqrt(area) * fs));
    }

    /** Chamfer distance to the edge of the region, positive inside and negative outside (in blocks). */
    private static float[] signedDistance(boolean[] in, int w, int h, int step) {
        float[] di = dist(in, w, h, true), dout = dist(in, w, h, false);
        float[] sd = new float[w * h];
        for (int c = 0; c < w * h; c++) sd[c] = (in[c] ? di[c] - 0.5f : -(dout[c] - 0.5f)) * step;
        return sd;
    }

    private static float[] dist(boolean[] in, int w, int h, boolean inside) {
        float INF = 1e6f, D = 1.4142f;
        float[] t = new float[w * h];
        for (int c = 0; c < w * h; c++) t[c] = in[c] == inside ? INF : 0;
        for (int b = 0; b < h; b++) for (int a = 0; a < w; a++) {
            int c = a + b * w;
            if (t[c] == 0) continue;
            if (a > 0) t[c] = Math.min(t[c], t[c - 1] + 1);
            if (b > 0) t[c] = Math.min(t[c], t[c - w] + 1);
            if (a > 0 && b > 0) t[c] = Math.min(t[c], t[c - w - 1] + D);
            if (a < w - 1 && b > 0) t[c] = Math.min(t[c], t[c - w + 1] + D);
        }
        for (int b = h - 1; b >= 0; b--) for (int a = w - 1; a >= 0; a--) {
            int c = a + b * w;
            if (t[c] == 0) continue;
            if (a < w - 1) t[c] = Math.min(t[c], t[c + 1] + 1);
            if (b < h - 1) t[c] = Math.min(t[c], t[c + w] + 1);
            if (a < w - 1 && b < h - 1) t[c] = Math.min(t[c], t[c + w + 1] + D);
            if (a > 0 && b < h - 1) t[c] = Math.min(t[c], t[c + w - 1] + D);
        }
        for (int c = 0; c < w * h; c++) if (t[c] >= INF) t[c] = Math.max(w, h);
        return t;
    }
}
