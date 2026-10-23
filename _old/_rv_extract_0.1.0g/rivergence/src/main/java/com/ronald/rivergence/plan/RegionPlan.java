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
    public final List<Segment> segments = new ArrayList<>();
    public final List<Lake> lakes = new ArrayList<>();
    public final List<Spring> springs = new ArrayList<>();
    public final List<Pool> pools = new ArrayList<>();
    /** Per chunk of the region and a little round it: the segments and lakes that may touch it. */
    private final int cx0, cz0, cw;
    private final int[][] chunkSegs, chunkLakes, chunkSprings, chunkPools;
    public long nanos, sampleNanos;

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
        Pool(float x, float z, float r, int level, float depth, float dx, float dz, float drop) { this.x = x; this.z = z; this.r = r; this.level = level; this.depth = depth; this.dx = dx; this.dz = dz; this.drop = drop; }
    }

    public static long key(int rx, int rz) { return ((long) rx << 32) ^ (rz & 0xffffffffL); }

    /**
     * @param heights gives each worker thread its own height sampler
     */
    /** Biome facts per 4x4 column (quart coordinates): 1 vanilla river, 2 ocean, 4 swamp. */
    public interface Biomes { int at(int qx, int qz); }

    public RegionPlan(int rx, int rz, Supplier<Drainage.Heights> heights, Biomes biomes, ForkJoinPool pool) {
        long t0 = System.nanoTime();
        this.rx = rx; this.rz = rz;
        bx0 = rx * SIZE; bz0 = rz * SIZE;
        int x0 = bx0 - MARGIN, z0 = bz0 - MARGIN;
        ThreadLocal<Drainage.Heights> hs = ThreadLocal.withInitial(heights);

        // 1. the land, one height per cell
        float[] h = new float[N * N];
        pool.submit(() -> IntStream.range(0, N).parallel().forEach(j -> {
            Drainage.Heights s = hs.get();
            for (int i = 0; i < N; i++) h[i + j * N] = (float) s.at(x0 + i * CELL + CELL / 2, z0 + j * CELL + CELL / 2);
        })).join();
        sampleNanos = System.nanoTime() - t0;
        boolean[] riv = null;
        if (biomes != null) {
            boolean[] r = new boolean[N * N];
            pool.submit(() -> IntStream.range(0, N).parallel().forEach(j -> {
                for (int i = 0; i < N; i++) r[i + j * N] = (biomes.at((x0 + i * CELL + CELL / 2) >> 2, (z0 + j * CELL + CELL / 2) >> 2) & 1) != 0;
            })).join();
            riv = r;
        }
        d = new Drainage(x0, z0, CELL, N, h, riv);
        networks();

        // 2. lakes kept near the region, each traced on a finer grid
        int[] lakeOf = refineLakes(hs, pool);
        // vanilla's water inland (river valleys, pools at sea level) that isn't one of our lakes: filled in
        fillCell = new boolean[N * N];
        for (int k = 0; k < N * N; k++) fillCell[k] = d.h[k] < Drainage.SEA && !d.sea(k) && lakeOf[k] < 0;
        fillGrid(biomes, lakeOf, pool);

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
        for (int t = 0; t < made.length; t++) {
            Segment s = made[t];
            if (s == null) continue;
            segments.add(s);
            findFalls(s);
            int k = todo.get(t)[0];
            if (!head[k]) continue;
            int gi = Math.floorDiv(d.x0, CELL) + k % N, gj = Math.floorDiv(d.z0, CELL) + k / N;
            double ex = s.x[Math.min(3, s.x.length - 1)] - s.x[0], ez = s.z[Math.min(3, s.z.length - 1)] - s.z[0], el = Math.max(1e-6, Math.hypot(ex, ez));
            float r = (float) (4.2 + 2.3 * (jit(gi, gj, 21) + 1) / 2);
            springs.add(new Spring(s.x[0], s.z[0], r, (int) s.level[0], (float) (ex / el), (float) (ez / el), (long) (jit(gi, gj, 22) * 1e12)));
        }

        // 5. index by chunk
        cx0 = (bx0 - BAND) >> 4; cz0 = (bz0 - BAND) >> 4;
        cw = (SIZE + 2 * BAND) >> 4;
        chunkSegs = new int[cw * cw][];
        chunkLakes = new int[cw * cw][];
        chunkSprings = new int[cw * cw][];
        chunkPools = new int[cw * cw][];
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
            int reach = (int) Math.ceil(g.r * 2) + 2;
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

    /**
     * Falls along a channel: wherever its water drops 5 or more within a few blocks (over a ledge or a cliff), a
     * bowl is set where it lands, a little past the foot, its surface at the water's lower level.
     */
    private void findFalls(Segment s) {
        int n = s.level.length;
        for (int i = 0; i + 3 < n; i++) {
            if (s.level[i] - s.level[i + 3] < 5) continue;
            int j = i + 3;
            while (j + 1 < n && s.level[j] - s.level[j + 1] >= 2) j++;
            int c = Math.min(n - 1, j + 1);
            float lvl = s.level[j];
            if (lvl <= SEA_TOP + 1) { i = j; continue; }
            int a = Math.max(0, c - 2), b = Math.min(n - 1, c + 2);
            double ex = s.x[b] - s.x[a], ez = s.z[b] - s.z[a], el = Math.max(1e-6, Math.hypot(ex, ez));
            float drop = s.level[i] - lvl;
            float r = (float) Math.max(2.8, s.w[c] * 1.5 + 1.6 + Math.min(2, drop * 0.08));
            float depth = (float) Math.min(4, 1.5 + drop * 0.12);
            pools.add(new Pool(s.x[c], s.z[c], r, (int) lvl, depth, (float) (ex / el), (float) (ez / el), drop));
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
        int i = (int) Math.round((x - fgx0) / FG), j = (int) Math.round((z - fgz0) / FG);
        if (i < 0 || j < 0 || i >= fgw || j >= fgw) return Double.NaN;
        return fillTop[i + j * fgw];
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
            if ((i % 2 == 0 || i == n - 1) && (from == null || from.sd(xs[i], zs[i]) < -2)) {
                int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
                double tx = xs[b] - xs[a], tz = zs[b] - zs[a], tl = Math.hypot(tx, tz);
                double nx = tl < 1e-6 ? 0 : -tz / tl, nz = tl < 1e-6 ? 0 : tx / tl, off = ws[i] + 1.5;
                double g = hs.at((int) Math.floor(xs[i]), (int) Math.floor(zs[i]));
                g = Math.min(g, hs.at((int) Math.floor(xs[i] + nx * off), (int) Math.floor(zs[i] + nz * off)));
                g = Math.min(g, hs.at((int) Math.floor(xs[i] - nx * off), (int) Math.floor(zs[i] - nz * off)));
                cur = Math.min(cur, Math.floor(g) - 1);
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
        int level = (int) Math.floor(d.lakeLevel[l]);
        boolean[] in = new boolean[w * h];
        int[] q = new int[w * h];
        for (; level > min + 1; level--) {
            Arrays.fill(in, false);
            int qh = 0, qt = 0, area = 0;
            boolean leak = false;
            q[qt++] = seed; in[seed] = true;
            while (qh < qt) {
                int c = q[qh++]; area++;
                int a = c % w, b = c / w;
                if (a == 0 || b == 0 || a == w - 1 || b == h - 1) { leak = true; break; }
                int[] nb = {c - 1, c + 1, c - w, c + w};
                for (int k : nb) if (!in[k] && g[k] < level) { in[k] = true; q[qt++] = k; }
            }
            if (leak) continue;
            if (area * step * step < 4 * CELL * CELL / 2) return null;     // too small once it holds
            return new Lake(level, bx, bz, step, w, h, signedDistance(in, w, h, step), (float) Math.min(10, 2.5 + 0.035 * Math.sqrt(area) * step));
        }
        return null;
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
