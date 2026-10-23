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
    /** Per chunk of the region and a little round it: the segments and lakes that may touch it. */
    private final int cx0, cz0, cw;
    private final int[][] chunkSegs, chunkLakes;
    public long nanos, sampleNanos;

    /** A stretch of channel from one cell's centre to the next, as points two blocks apart with their water levels. */
    public static final class Segment {
        public final float[] x, z, level;
        public final float hw, depth, acc;
        public int minX, minZ, maxX, maxZ;
        Segment(float[] x, float[] z, float[] level, float hw, float depth, float acc) { this.x = x; this.z = z; this.level = level; this.hw = hw; this.depth = depth; this.acc = acc; }
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

    public static long key(int rx, int rz) { return ((long) rx << 32) ^ (rz & 0xffffffffL); }

    /**
     * @param heights gives each worker thread its own height sampler
     */
    public RegionPlan(int rx, int rz, Supplier<Drainage.Heights> heights, ForkJoinPool pool) {
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
        d = new Drainage(x0, z0, CELL, N, h, 0);

        // 2. lakes kept near the region, each traced on a finer grid
        int[] lakeOf = refineLakes(hs, pool);

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
            if (inLake && lakeOf[dn] == lakeOf[k]) continue;               // inside one lake: the lake covers it
            if (!inLake && !channel(k)) continue;
            if (Float.isNaN(L[dn])) L[dn] = (float) Math.max(SEA_TOP, Math.min(L[k], Math.floor(d.h[dn]) - 1));
            todo.add(new int[]{k, dn});
        }
        Segment[] made = new Segment[todo.size()];
        pool.submit(() -> IntStream.range(0, todo.size()).parallel().forEach(t -> {
            int k = todo.get(t)[0], dn = todo.get(t)[1];
            made[t] = segment(k, dn, main[k], d.down(dn), L[k], L[dn], lakeOf[k] >= 0 ? lakes.get(lakeOf[k]) : null, hs.get());
        })).join();
        for (Segment s : made) if (s != null) segments.add(s);

        // 5. index by chunk
        cx0 = (bx0 - BAND) >> 4; cz0 = (bz0 - BAND) >> 4;
        cw = (SIZE + 2 * BAND) >> 4;
        chunkSegs = new int[cw * cw][];
        chunkLakes = new int[cw * cw][];
        List<List<Integer>> cs = new ArrayList<>(), cl = new ArrayList<>();
        for (int c = 0; c < cw * cw; c++) { cs.add(null); cl.add(null); }
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
    private int[] at(int[][] idx, int x, int z) {
        int cx = (x >> 4) - cx0, cz = (z >> 4) - cz0;
        if (cx < 0 || cz < 0 || cx >= cw || cz >= cw) return null;
        return idx[cx + cz * cw];
    }

    public boolean channel(int k) { return !d.sea(k) && d.acc[k] >= STREAM_CELLS; }

    /** Half the width of the water, in blocks, for the water gathered (in cells). */
    public static float halfWidth(float acc) { return (float) Math.min(12, 0.7 + 0.5 * Math.sqrt(acc / STREAM_CELLS)); }

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
        float hw = halfWidth(acc), depth = (float) Math.min(6, 1 + 0.4 * hw);
        float[] xs = new float[n], zs = new float[n], lv = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            xs[i] = (float) cr(x0, x1, x2, x3, t);
            zs[i] = (float) cr(z0, z1, z2, z3, t);
        }
        // the level: from this cell's down toward the next's, never above the ground beside the water
        double cur = l0;
        for (int i = 0; i < n; i++) {
            // leaving a lake: the lake bed doesn't count, only the ground past its shore
            if ((i % 2 == 0 || i == n - 1) && (from == null || from.sd(xs[i], zs[i]) < -2)) {
                int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
                double tx = xs[b] - xs[a], tz = zs[b] - zs[a], tl = Math.hypot(tx, tz);
                double nx = tl < 1e-6 ? 0 : -tz / tl, nz = tl < 1e-6 ? 0 : tx / tl, off = hw + 1.5;
                double g = hs.at((int) Math.floor(xs[i]), (int) Math.floor(zs[i]));
                g = Math.min(g, hs.at((int) Math.floor(xs[i] + nx * off), (int) Math.floor(zs[i] + nz * off)));
                g = Math.min(g, hs.at((int) Math.floor(xs[i] - nx * off), (int) Math.floor(zs[i] - nz * off)));
                cur = Math.min(cur, Math.floor(g) - 1);
            }
            cur = Math.max(cur, l1);
            lv[i] = (float) cur;
        }
        Segment s = new Segment(xs, zs, lv, hw, depth, acc);
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]); }
        int pad = (int) Math.ceil(hw) + REACH + 2;
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
