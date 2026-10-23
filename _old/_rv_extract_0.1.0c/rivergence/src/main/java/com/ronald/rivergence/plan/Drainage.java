package com.ronald.rivergence.plan;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Where the water goes across one square of land.
 *
 * The land is sampled on a coarse grid (one height per cell). Every hollow is filled up to the level where it would
 * spill (a priority flood from the sea and the square's edges inward), so each cell then has a way downhill: the
 * filled hollows are where lakes lie, and everything else drains toward the sea. Each cell sends its water on to its
 * steepest lower neighbour, and the water gathered from everything upstream ("accumulation", in cells) says how big
 * the stream there is: a trickle where a few cells drain, a river where thousands do.
 *
 * Pure Java on purpose: the heights come in through {@link Heights}, so it runs the same in the game and in tools.
 */
public final class Drainage {
    /** Terrain surface height at a block column (for columns under the sea, the sea floor). */
    public interface Heights { double at(int x, int z); }

    public static final double SEA = 62.5;
    /** Smallest body of water below sea level counted as the sea (blocks²); smaller ones inland are hollows. */
    static final double SEA_AREA = 96 * 1024;
    /** Smallest rise given to the water surface across a flat, so it always has a way to flow. */
    private static final double EPS = 0.001;
    /** Lakes: hollows at least this deep (blocks) and this big (cells). */
    private static final double LAKE_DEPTH = 1.5;
    private static final int LAKE_CELLS = 4;

    static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1}, DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    static final double[] DL = {1, Math.sqrt(2), 1, Math.sqrt(2), 1, Math.sqrt(2), 1, Math.sqrt(2)};

    /** The square: its north-west corner (blocks), cell size (blocks) and cells per side. */
    public final int x0, z0, cell, n;
    /** Per cell: land height, the filled water surface, the way water leaves (0-7, -1 into the sea, -2 off the edge). */
    public final float[] h, fill;
    public final byte[] dir;
    /** Per cell: low ground below sea level that isn't the sea (a vanilla river's valley, mostly). */
    public final boolean[] valley;
    /** Per cell: cells draining through it (itself included). */
    public final float[] acc;
    /** Per cell: the lake it lies in (index into {@link #lakeLevel}) or -1. */
    public final int[] lake;
    public float[] lakeLevel = new float[0];
    public int[] lakeArea = new int[0];
    public long sampleNanos;

    public Drainage(int x0, int z0, int cell, int n, Heights hs) {
        this(x0, z0, cell, n, sample(x0, z0, cell, n, hs), 0);
        sampleNanos = lastSample;
    }

    private static long lastSample;
    private static float[] sample(int x0, int z0, int cell, int n, Heights hs) {
        long t0 = System.nanoTime();
        float[] h = new float[n * n];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) h[i + j * n] = (float) hs.at(x0 + i * cell + cell / 2, z0 + j * cell + cell / 2);
        lastSample = System.nanoTime() - t0;
        return h;
    }

    /** From heights already sampled at the cell centres (row by row, n per row). */
    public Drainage(int x0, int z0, int cell, int n, float[] heights, int unused) {
        this.x0 = x0; this.z0 = z0; this.cell = cell; this.n = n;
        int N = n * n;
        h = heights; fill = new float[N]; dir = new byte[N]; acc = new float[N]; lake = new int[N]; valley = new boolean[N];
        flood();
        directions();
        accumulate();
        findLakes();
    }

    public boolean sea(int k) { return dir[k] == -1; }

    /** Fill every hollow to its spill level, working inward from the sea and the square's edges. */
    private void flood() {
        int N = n * n;
        boolean[] done = new boolean[N];
        // the sea: wide low ground joined to deep water. Narrow low ground (a vanilla river valley, a dry hollow below sea
        // level inland) is land here: the water planned round it runs on along it to the sea, so the catchments stay whole.
        boolean[] low = new boolean[N], wide = new boolean[N], ocean = new boolean[N];
        for (int k = 0; k < N; k++) low[k] = h[k] < SEA;
        boolean[] core = new boolean[N];
        for (int k = 0; k < N; k++) core[k] = low[k] && allAround(low, k, true);
        for (int k = 0; k < N; k++) wide[k] = low[k] && (core[k] || !allAround(core, k, false));
        ArrayDeque<Integer> oq = new ArrayDeque<>();
        for (int k = 0; k < N; k++) if (wide[k] && h[k] < SEA - 12) { ocean[k] = true; oq.add(k); }
        while (!oq.isEmpty()) {
            int c = oq.poll(), ci = c % n, cj = c / n;
            for (int d = 0; d < 8; d += 2) {
                int i = ci + DX[d], j = cj + DZ[d];
                if (i < 0 || j < 0 || i >= n || j >= n) continue;
                int k = i + j * n;
                if (!ocean[k] && wide[k]) { ocean[k] = true; oq.add(k); }
            }
        }
        // little pockets of "sea" inland (a deep dip, a flooded hollow) aren't the sea: only big bodies, or ones running off the plan
        int[] comp = new int[N];
        java.util.Arrays.fill(comp, -1);
        for (int s0 = 0; s0 < N; s0++) {
            if (!ocean[s0] || comp[s0] >= 0) continue;
            java.util.List<Integer> cells = new java.util.ArrayList<>();
            boolean edge = false;
            oq.add(s0); comp[s0] = s0;
            while (!oq.isEmpty()) {
                int c = oq.poll(), ci = c % n, cj = c / n;
                cells.add(c);
                if (ci == 0 || cj == 0 || ci == n - 1 || cj == n - 1) edge = true;
                for (int d = 0; d < 8; d += 2) {
                    int i = ci + DX[d], j = cj + DZ[d];
                    if (i < 0 || j < 0 || i >= n || j >= n) continue;
                    int k = i + j * n;
                    if (ocean[k] && comp[k] < 0) { comp[k] = s0; oq.add(k); }
                }
            }
            if (!edge && cells.size() * (double) cell * cell < SEA_AREA) for (int c : cells) ocean[c] = false;
        }
        for (int k = 0; k < N; k++) valley[k] = low[k] && !ocean[k] && !wide[k];
        PriorityQueue<long[]> q = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        for (int k = 0; k < N; k++) {
            int i = k % n, j = k / n;
            boolean edge = i == 0 || j == 0 || i == n - 1 || j == n - 1;
            if (ocean[k]) { fill[k] = (float) SEA; dir[k] = -1; }
            else if (edge) { fill[k] = h[k]; dir[k] = -2; }
            else continue;
            done[k] = true;
            q.add(new long[]{key(fill[k]), k});
        }
        // Across a flat (a filled hollow, a valley floor brought up to sea level) the flood runs along the deepest
        // ground first, and each flat cell drains back the way the flood reached it: so the water crossing a flat
        // follows its lowest line instead of fanning out in parallel runs.
        par = new byte[N];
        java.util.Arrays.fill(par, (byte) -1);
        while (!q.isEmpty()) {
            long[] top = q.poll();
            int c = (int) top[1];
            int ci = c % n, cj = c / n;
            for (int d = 0; d < 8; d++) {
                int i = ci + DX[d], j = cj + DZ[d];
                if (i < 0 || j < 0 || i >= n || j >= n) continue;
                int k = i + j * n;
                if (done[k]) continue;
                done[k] = true;
                fill[k] = (float) Math.max(h[k], fill[c] + EPS * DL[d]);
                par[k] = (byte) ((d + 4) & 7);
                long kk = fill[k] > h[k] ? Math.max(top[0], key(fill[k] - 0.01 * (fill[k] - h[k]))) : key(fill[k]);
                q.add(new long[]{kk, k});
            }
        }
    }

    /** all: every neighbour (8) is set; otherwise: none of them is (edges count as set for all, unset for none). */
    private boolean allAround(boolean[] m, int k, boolean all) {
        int ci = k % n, cj = k / n;
        for (int d = 0; d < 8; d++) {
            int i = ci + DX[d], j = cj + DZ[d];
            if (i < 0 || j < 0 || i >= n || j >= n) continue;
            if (all ? !m[i + j * n] : m[i + j * n]) return false;
        }
        return true;
    }

    private static long key(double f) { return (long) ((f + 1000) * 100000); }

    /** Each cell drains to its steepest lower neighbour on the filled surface. */
    private byte[] par;

    private void directions() {
        for (int k = 0; k < n * n; k++) {
            if (dir[k] < 0) continue;
            if (fill[k] > h[k] && par[k] >= 0) { dir[k] = par[k]; continue; }     // on a flat: back along the flood
            int ci = k % n, cj = k / n;
            double best = 0; int bd = -2;
            for (int d = 0; d < 8; d++) {
                int i = ci + DX[d], j = cj + DZ[d];
                if (i < 0 || j < 0 || i >= n || j >= n) continue;
                double s = (fill[k] - fill[i + j * n]) / DL[d];
                if (s > best) { best = s; bd = d; }
            }
            dir[k] = (byte) bd;
        }
    }

    /** Downstream cell of k, or -1. */
    public int down(int k) {
        if (dir[k] < 0) return -1;
        int i = k % n + DX[dir[k]], j = k / n + DZ[dir[k]];
        return i + j * n;
    }

    /** Gather the water downhill, highest cells first. */
    private void accumulate() {
        int N = n * n;
        Integer[] order = new Integer[N];
        for (int k = 0; k < N; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> Float.compare(fill[b], fill[a]));
        Arrays.fill(acc, 1);
        for (int k : order) {
            int d = down(k);
            if (d >= 0) acc[d] += acc[k];
        }
    }

    /** Filled hollows that are deep and big enough become lakes, at the level they fill to. */
    private void findLakes() {
        int N = n * n;
        Arrays.fill(lake, -1);
        float[] lv = new float[64]; int[] ar = new int[64]; int count = 0;
        boolean[] seen = new boolean[N];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        java.util.List<Integer> cells = new java.util.ArrayList<>();
        for (int s = 0; s < N; s++) {
            if (seen[s] || dir[s] == -1 || valley[s] || fill[s] - h[s] < 0.8) continue;
            cells.clear(); q.add(s); seen[s] = true;
            float level = 0, depth = 0;
            while (!q.isEmpty()) {
                int c = q.poll(); cells.add(c);
                level = Math.max(level, fill[c]); depth = Math.max(depth, fill[c] - h[c]);
                int ci = c % n, cj = c / n;
                for (int d = 0; d < 8; d += 2) {
                    int i = ci + DX[d], j = cj + DZ[d];
                    if (i < 0 || j < 0 || i >= n || j >= n) continue;
                    int k = i + j * n;
                    if (seen[k] || dir[k] == -1 || valley[k] || fill[k] - h[k] < 0.8) continue;
                    seen[k] = true; q.add(k);
                }
            }
            if (cells.size() < LAKE_CELLS || depth < LAKE_DEPTH) continue;
            if (count == lv.length) { lv = Arrays.copyOf(lv, count * 2); ar = Arrays.copyOf(ar, count * 2); }
            lv[count] = (float) Math.floor(level); ar[count] = cells.size();
            for (int c : cells) lake[c] = count;
            count++;
        }
        lakeLevel = Arrays.copyOf(lv, count); lakeArea = Arrays.copyOf(ar, count);
    }
}
