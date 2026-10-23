package com.ronald.rivergence.ripple.sim;

/**
 * A square map of columns around the player that finds beaches: land that sits at about the same
 * height as nearby water. For each beach column it stores how far it is from the water.
 */
public final class ShoreMap {
    public static final byte OTHER = 0;
    public static final byte WATER = 1;
    public static final byte BEACH = 2;    // absorbs water: sand, gravel, dirt
    public static final byte GLOSSY = 3;   // does not absorb water: stone and the like

    /** Beyond this many blocks from the water, the sand is not wet any more. */
    public static final float MAX_REACH = 5.5F;
    public static final float FAR = 6.0F;

    public final int size;
    public final int originX;
    public final int originZ;
    public final byte[] kind;
    public final short[] top;      // y of the top block of the column
    private final float[] dist;    // chamfer distance to water, in blocks
    private final short[] level;   // water level the distance was measured from

    /** Columns that are beach and close enough to water to matter. */
    public int[] wetColumns = new int[0];

    public ShoreMap(int size, int originX, int originZ) {
        this.size = size;
        this.originX = originX;
        this.originZ = originZ;
        int n = size * size;
        kind = new byte[n];
        top = new short[n];
        dist = new float[n];
        level = new short[n];
    }

    /** Works out distances once the kind and top arrays have been filled in. */
    public void compute() {
        int n = size * size;
        for (int k = 0; k < n; k++) {
            if (kind[k] == WATER) {
                dist[k] = 0F;
                level[k] = top[k];
            } else {
                dist[k] = 99F;
            }
        }
        final float diag = 1F;   // square distance, so contours follow the block grid
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    int k = j * size + i;
                    relax(k, i - 1, j, 1F);
                    relax(k, i, j - 1, 1F);
                    relax(k, i - 1, j - 1, diag);
                    relax(k, i + 1, j - 1, diag);
                }
            }
            for (int j = size - 1; j >= 0; j--) {
                for (int i = size - 1; i >= 0; i--) {
                    int k = j * size + i;
                    relax(k, i + 1, j, 1F);
                    relax(k, i, j + 1, 1F);
                    relax(k, i + 1, j + 1, diag);
                    relax(k, i - 1, j + 1, diag);
                }
            }
        }

        int count = 0;
        for (int k = 0; k < n; k++) {
            if (isShoreLand(kind[k]) && dist[k] - 0.5F <= MAX_REACH) count++;
        }
        wetColumns = new int[count];
        int w = 0;
        for (int k = 0; k < n; k++) {
            if (isShoreLand(kind[k]) && dist[k] - 0.5F <= MAX_REACH) wetColumns[w++] = k;
        }
    }

    public static boolean isShoreLand(byte kind) {
        return kind == BEACH || kind == GLOSSY;
    }

    /** Spread distance from neighbour (ni, nj) into column k if that makes sense for a beach. */
    private void relax(int k, int ni, int nj, float step) {
        if (ni < 0 || nj < 0 || ni >= size || nj >= size) return;
        int nk = nj * size + ni;
        // Spread from the neighbour into this column, and the other way, so both passes work.
        tryInto(k, nk, step);
        tryInto(nk, k, step);
    }

    private void tryInto(int to, int from, float step) {
        if (!isShoreLand(kind[to]) || dist[from] >= 90F) return;
        float nd = dist[from] + step;
        if (nd >= dist[to] || nd > MAX_REACH + 1F) return;
        if (top[to] != level[from]) return;   // only land exactly at sea level counts
        dist[to] = nd;
        level[to] = level[from];
    }

    /** Distance inland of the water's edge at a column centre: negative in water, about 0.5 next to it. */
    private float columnValue(int i, int j) {
        if (i < 0 || j < 0 || i >= size || j >= size) return FAR;
        int k = j * size + i;
        if (kind[k] == WATER) return -0.5F;
        if (isShoreLand(kind[k]) && dist[k] < 90F) return Math.min(FAR, dist[k] - 0.5F);
        return FAR;
    }

    /** Whole blocks inland of the water for the column containing a position (1 = first block), or 99. */
    public int stepAt(double x, double z) {
        int i = (int) Math.floor(x) - originX;
        int j = (int) Math.floor(z) - originZ;
        if (i < 0 || j < 0 || i >= size || j >= size) return 99;
        int k = j * size + i;
        if (!isShoreLand(kind[k]) || dist[k] >= 90F) return 99;
        return Math.round(dist[k]);
    }

    /** Smoothly interpolated distance from the water's edge at a world position. */
    public float distanceAt(double x, double z) {
        double fx = x - originX - 0.5;
        double fz = z - originZ - 0.5;
        int i = (int) Math.floor(fx);
        int j = (int) Math.floor(fz);
        float u = (float) (fx - i);
        float v = (float) (fz - j);
        float a = columnValue(i, j);
        float b = columnValue(i + 1, j);
        float c = columnValue(i, j + 1);
        float d = columnValue(i + 1, j + 1);
        return (a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v;
    }
}
