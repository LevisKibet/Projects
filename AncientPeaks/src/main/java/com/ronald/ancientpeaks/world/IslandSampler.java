package com.ronald.ancientpeaks.world;

import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places the world's one Ancient Highlands island 9,000-10,000 blocks from spawn (deep ocean where it can)
 * and evaluates its terrain, water and zones. Pure Java so the offline preview tool can run it too.
 */
public final class IslandSampler {
    public interface Continents { double at(int x, int z); }

    public static final int SECTOR = 20000, MARGIN = 2000, SPAWN_EXCLUSION = 5000;
    public static final double DEEP_OCEAN = -0.36;

    public static final double TUNNEL_END = 0.68;

    // one sampler per world seed, shared by every density function and hook of that world
    private static final ConcurrentHashMap<Long, IslandSampler> BY_SEED = new ConcurrentHashMap<>();
    public static IslandSampler forSeed(long seed) { return ACTIVE = BY_SEED.computeIfAbsent(seed, k -> new IslandSampler(k, false)); }
    /** The sampler of the world being generated (for hooks that are not handed a level, like Streams Reflowing's). */
    public static volatile IslandSampler ACTIVE;

    private final long[] calmCache = new long[8192];
    /**
     * True where Streams Reflowing must not veto vegetation: the plains (no streams start there) and the tarns.
     * Cached per 4x4 column.
     */
    public boolean calmGround(int x, int z) {
        if (islandAt(x, z) == null) return false;
        int qx = x >> 2, qz = z >> 2;
        long key = (1L << 61) | ((long) (qx & 0xFFFFFF) << 26) | ((long) (qz & 0xFFFFFF) << 2);
        int slot = (int) ((qx * 73856093L ^ qz * 19349663L) & 8191);
        long e = calmCache[slot];
        if ((e & ~3L) == key) return (e & 3) == 1;
        Column c = new Column();
        fill(c, (qx << 2) + 2, (qz << 2) + 2);
        Zone zn = zone(c, 64);
        boolean calm = zn == Zone.PLAINS || zn == Zone.TARN || (c.tarn >= 0 && c.td < c.is.tarns.get(c.tarn)[2] + 10);
        calmCache[slot] = key | (calm ? 1 : 2);
        return calm;
    }

    public enum Zone { NONE, CLIFFS, MEADOW, FOREST, PLAINS, GORGE, SHORE, PEAKS, TARN }

    private final long seed;
    private volatile Continents continents;
    private final boolean preview;

    public IslandSampler(long seed, boolean preview) { this.seed = seed; this.preview = preview; }
    public void setContinents(Continents c) { this.continents = c; }
    public boolean ready() { return preview || continents != null; }

    // ------------------------------------------------------------------ placement
    /** One island per world: its centre lies this far from spawn (0, 0). */
    public static final int RING_MIN = 9000, RING_MAX = 10000;
    /** The ring the island is placed on: from the config in the game, the defaults in the preview tools. */
    private int[] ring() {
        if (preview) return new int[]{RING_MIN, RING_MAX};
        try { return com.ronald.ancientpeaks.RuinsConfig.islandRing(); } catch (Throwable t) { return new int[]{RING_MIN, RING_MAX}; }
    }
    private volatile Optional<Island> theIsland;

    /** The world's island, if this column lies on it. */
    public Island islandAt(int x, int z) {
        Island is = island();
        if (is == null) return null;
        double dx = x - is.cx, dz = z - is.cz;
        return dx * dx + dz * dz < is.boundR * is.boundR ? is : null;
    }

    /**
     * The world's island. There is only one now, so this ignores the sector (kept for callers that still search
     * sector by sector - they all find the same island).
     */
    public Island islandInSector(int sx, int sz) { return island(); }

    /** The world's single island (null until the world's noise is ready, or if nowhere on the ring would do). */
    public Island island() {
        if (!ready()) return null;
        Optional<Island> o = theIsland;
        if (o == null) {
            // no lock: worker threads that ask at the same moment each work it out (it comes out the same) and the
            // first answer is kept - a lock here could wait on the noise the search itself reads, and hang world creation
            Optional<Island> mine = Optional.ofNullable(find());
            synchronized (this) { if (theIsland == null) theIsland = mine; o = theIsland; }
        }
        return o.orElse(null);
    }

    /**
     * Walks the 9,000-10,000 block ring around spawn from a seeded angle and takes the first spot that is deep ocean
     * under the whole island. If the ring never finds one (a seed full of land), the most oceanic spot on it is used.
     */
    private Island find() {
        Random r = new Random(seed ^ 0x6C6F67726573L);
        double a0 = r.nextDouble() * Math.PI * 2;
        Island fallback = null; double fallbackC = Double.MAX_VALUE;
        for (int ri = 0; ri < 5; ri++) {
            int[] ring = ring();
            double rad = ring[0] + 300 + r.nextInt(ring[1] - ring[0] - 600);
            for (int i = 0; i < 96; i++) {
                double a = a0 + i * 0.618034 * Math.PI * 2 + ri * 0.37;   // golden-angle walk spreads the tries around the ring
                int x = (int) Math.round(Math.cos(a) * rad), z = (int) Math.round(Math.sin(a) * rad);
                long islandSeed = seed * 31 + ri * 1009L + i;
                Island is = new Island(x, z, islandSeed);
                if (preview) return is.complete();
                double c = continents.at(x, z);
                if (c < fallbackC) { fallbackC = c; fallback = is; }
                if (!ocean(x, z, 0.04)) continue;
                if (footprintIsOcean(is)) {
                    if (DEBUG) System.out.println("[ancientpeaks] island at " + x + "," + z + " (" + (int) rad + " from spawn)");
                    return is.complete();
                }
                if (DEBUG) System.out.println("[ancientpeaks]   reject footprint at " + x + "," + z + " why=" + lastWhy);
            }
        }
        if (DEBUG && fallback != null) System.out.println("[ancientpeaks] no clean ocean on the ring; island at " + fallback.cx + "," + fallback.cz);
        return fallback == null ? null : fallback.complete();
    }

    /** Deep ocean only - and never next to mushroom fields (they sit at even lower continentalness). */
    public static boolean DEBUG = Boolean.getBoolean("ancientpeaks.debug");
    private String lastWhy = "";
    private boolean ocean(int x, int z, double slack) {
        double c = continents.at(x, z);
        boolean ok = c < DEEP_OCEAN + slack && c > -1.04;
        if (!ok) lastWhy = String.format("%.2f", c);
        return ok;
    }

    private boolean footprintIsOcean(Island is) {
        for (int i = 0; i <= 20; i++) {
            double s = i / 20.0;
            for (double side : new double[]{-1.9, -1.2, 0, 1.2, 1.9}) {
                double[] p = is.pointAt(s, side);
                int[] w = is.toWorld(p[0], p[1]);
                if (!ocean(w[0], w[1], 0.15)) return false;
            }
        }
        double ring = is.boundR - Island.SHELF * 0.2;
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            if (!ocean((int) (is.cx + Math.cos(a) * ring), (int) (is.cz + Math.sin(a) * ring), 0.23)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers
    static double smooth(double a, double b, double x) { return Island.smooth(a, b, x); }
    private static double clamp1(double v) { return v < -1 ? -1 : (v > 1 ? 1 : v); }

    // ------------------------------------------------------------------ per-column fields
    public static final class Column {
        public int x = Integer.MIN_VALUE, z = Integer.MIN_VALUE;
        public Island is;
        public double m, u, v, h, hHi, coast, stackH, jitterAmp, widN;
        final Island.Base b = new Island.Base();
        final double[] rd = new double[Island.MAX_STREAMS], rs = new double[Island.MAX_STREAMS], rl = new double[Island.MAX_STREAMS];
        final double[] rw = new double[Island.MAX_STREAMS], rdp = new double[Island.MAX_STREAMS];
        /** nearest spring, pond and plunge pool: index (or -1) and outline-relative distance (1 = the edge) */
        int spring = -1, pond = -1, pool = -1; double sq = 9, pq = 9, plq = 9, sAng;
        double sd, ss;
        int tarn = -1; double td, islet;
        /** distance from the tarn's outlet notch line; the land's surface with its cliff lean */
        double tnd = 1e9, hL;
        /** the mere cave: distance to the tunnel's line, how far along it, its floor there; the chamber's radial distance */
        double cvd = 1e9, cvs, cvf, chq = 9;
        /** the lush way up: distance and floor of each passage, and how far off the spiral's ring (with its angle) */
        double p1d = 1e9, p1f, p2d = 1e9, p2f, hxd = 1e9, hxa;
    }

    public void fill(Column c, int x, int z) {
        fillIsland(c, islandAt(x, z), x, z);
    }

    void fillIsland(Column c, Island is, int x, int z) {
        c.x = x; c.z = z;
        c.is = is;
        if (is == null) { c.m = 0; return; }
        double u = is.localU(x, z), v = is.localV(x, z);
        c.u = u; c.v = v;
        Island.Base b = c.b;
        is.baseAt(u, v, b, true);
        c.m = 1 - smooth(1.06, 1.0 + (Island.SHELF + 30) / b.wBlocks, b.e);
        if (c.m <= 0) return;
        c.coast = smooth(1, 1 + b.coastK, b.e);
        c.stackH = -1e9;
        for (double[] s : is.stacks) {
            double d = Math.hypot(u - s[0], v - s[1]);
            double rad = s[2] * (1 + 0.25 * is.n[11].get(u / 9, v / 9));
            if (d < rad) c.stackH = Math.max(c.stackH, s[3] - Math.pow(d / rad, 6) * 20);
        }
        c.h = Math.max(is.heightAt(b, b.e), c.stackH);
        c.hHi = Math.max(is.heightAt(b, b.e - Island.LEAN * 254 / b.wBlocks), c.h);
        c.hL = Math.max(is.surfaceLean(b), c.stackH);
        c.jitterAmp = 2.6 * b.hw * (1 - b.shelfM) + 1.8 * c.coast;
        c.widN = 2.0 * is.n[12].get(u / 80, v / 80);
        double[] o = new double[5];
        for (int i = 0; i < is.rivers.size() && i < Island.MAX_STREAMS; i++) {
            Island.Path p = is.rivers.get(i);
            if (!p.near(u, v, 100)) { c.rd[i] = 1e9; c.rs[i] = 0; c.rl[i] = 0; c.rw[i] = 1; c.rdp[i] = 1; continue; }
            Island.pathDist(p, u, v, o);
            c.rd[i] = o[0]; c.rs[i] = o[1]; c.rl[i] = o[2];
            c.rw[i] = o[3] + (p.wid != null ? c.widN * Math.min(1, o[3] / 3) * 0.5 : c.widN);
            c.rdp[i] = o[4];
        }
        // no rocky jitter right by a stream: its banks stand where its level was worked out for
        double edge = 1e9;
        for (int i = 0; i < is.rivers.size() && i < Island.MAX_STREAMS; i++)
            if (is.rivers.get(i).fine != null) edge = Math.min(edge, c.rd[i] - c.rw[i]);
        c.jitterAmp *= smooth(1.5, 9, edge);
        c.spring = -1; c.sq = 9;
        for (int i = 0; i < is.springs.size(); i++) {
            double[] sp = is.springs.get(i);
            double du = u - sp[0], dv = v - sp[1];
            if (Math.abs(du) > 14 || Math.abs(dv) > 14) continue;
            double a = Math.atan2(dv, du), q = Math.hypot(du, dv) / (sp[2] * Island.blobShape(sp[6], a));
            if (q < c.sq) { c.sq = q; c.spring = i; c.sAng = a; }
        }
        c.pond = -1; c.pq = 9;
        for (int i = 0; i < is.ponds.size(); i++) {
            double[] pd = is.ponds.get(i);
            double du = u - pd[0], dv = v - pd[1];
            if (Math.abs(du) > 30 || Math.abs(dv) > 30) continue;
            double q = Math.hypot(du, dv) / (pd[2] * Island.blobShape(pd[4], Math.atan2(dv, du)));
            if (q < c.pq) { c.pq = q; c.pond = i; }
        }
        c.pool = -1; c.plq = 9;
        for (int i = 0; i < is.falls.size(); i++) {
            double[] f = is.falls.get(i);
            double du = u - f[7], dv = v - f[8];
            if (Math.abs(du) > 12 || Math.abs(dv) > 12) continue;
            double q = Math.hypot(du, dv) / (f[9] * (1 + 0.15 * Math.sin(3 * Math.atan2(dv, du) + f[0])));
            if (q < c.plq) { c.plq = q; c.pool = i; }
        }
        Island.pathDist(is.strait, u, v, o);
        c.sd = o[0]; c.ss = o[1];
        c.cvd = 1e9; c.chq = 9;
        if (is.hasCave) {
            if (is.caveTunnel.near(u, v, 8)) { Island.pathDist(is.caveTunnel, u, v, o); c.cvd = o[0]; c.cvs = o[1]; c.cvf = o[2]; }
            double du = u - is.caveCU, dv = v - is.caveCV;
            double a1 = (du * is.caveNU + dv * is.caveNV) / is.caveA, b1 = (du * is.caveTU + dv * is.caveTV) / is.caveB;
            if (Math.abs(a1) < 1.6 && Math.abs(b1) < 1.6) c.chq = Math.hypot(a1, b1) * (1 + 0.13 * is.n[10].get(u / 7.0, v / 7.0));
            c.p1d = 1e9; c.p2d = 1e9; c.hxd = 1e9;
            if (is.caveUp1 != null) {
                if (is.caveUp1.near(u, v, 8)) { Island.pathDist(is.caveUp1, u, v, o); c.p1d = o[0]; c.p1f = o[2]; }
                if (is.caveUp2.near(u, v, 8)) { Island.pathDist(is.caveUp2, u, v, o); c.p2d = o[0]; c.p2f = o[2]; }
                double hu = u - is.helixU, hv = v - is.helixV;
                if (Math.abs(hu) < is.helixR + 6 && Math.abs(hv) < is.helixR + 6) { c.hxd = Math.abs(Math.hypot(hu, hv) - is.helixR); c.hxa = Math.atan2(hv, hu); }
            }
        }
        // tarns: the shore follows the hillside's contours (see Island.pickTarns); td < radius means in the water
        c.tarn = -1; c.td = 1e9; c.islet = 1e9; c.tnd = 1e9;
        for (int i = 0; i < is.tarns.size(); i++) {
            double sd = is.tarnSd(i, u, v);
            if (sd > 40) continue;
            double[] t = is.tarns.get(i);
            if (t[2] + sd < c.td) {
                c.td = t[2] + sd; c.tarn = i;
                // the outlet notch: a short line out from the shore at the outlet
                double du = u - t[15], dv = v - t[16];
                double along = du * t[5] + dv * t[6];
                c.tnd = along < -2 || along > 9 ? 1e9 : Math.abs(-du * t[6] + dv * t[5]);
            }
        }
    }

    /** Inside a passage of the lush cave: about 5 wide and 5 high, a rounded roof. */
    private static boolean passage(double d, double fl, int x, int y, int z, Island is) {
        double ceil = fl + 4.8 + 1.2 * is.n[11].get(x / 6.0 + y / 5.0, z / 6.0);
        if (y <= fl || y >= ceil) return false;
        double vt = (y - fl) / (ceil - fl) * 2 - 1;
        double wr = (2.6 + 0.9 * is.n[12].get(x / 5.0, z / 5.0 + y / 7.0)) * Math.sqrt(Math.max(0, 1 - vt * vt * vt * vt));
        return d < wr;
    }

    // ------------------------------------------------------------------ density
    /** Carve amount for a river/strait channel: > 0 means air. */
    private double channelCarve(Island.Path p, double d, double frac, double level, double y, double terrain, double widN) {
        return channelCarveW(p, d, level, y, terrain, p.width0 + (p.width1 - p.width0) * frac + widN, p.depth0 + (p.depth1 - p.depth0) * frac);
    }
    private double channelCarveW(Island.Path p, double d, double level, double y, double terrain, double wc, double depth) {
        wc = Math.max(wc, 0.45);
        double bed = d < wc ? level - depth * (1 - (d / wc) * (d / wc)) : level + (d - wc) * p.bankSlope;
        double carve = y - bed;
        if (p.gorge) {
            // walls lean inward above the lower gorge: overhanging rock over the river
            double hh = y - level;
            double allowed = wc + Math.min(hh, 26) / p.bankSlope * 1.0 + 3 - 0.07 * Math.max(0, hh - 26)
                    + 0.7 * Math.max(0, y - (terrain - 9));
            allowed = Math.max(allowed, wc * 0.7);
            carve = Math.min(carve, allowed - d);
        }
        return carve;
    }

    /** Island terrain density at a block: > 0 solid. Only meaningful where c.m > 0. */
    public double density(Column c, int x, int y, int z) {
        Island is = c.is;
        Island.Base b = c.b;
        double j = 0;
        if (c.jitterAmp > 0 && y > c.h - 50) j = is.n[13].get(x / 14.0 + y / 10.0, z / 14.0) + is.n[12].get(x / 14.0, z / 14.0 - y / 10.0);
        double dens;
        if (y < c.h - 6) dens = 1;
        else if (y > c.hHi + 6) dens = -1;
        else {
            double lean = Island.LEAN * Math.max(0, Math.min(y, 175) - 66) * (0.35 + 0.65 * Math.max(b.hw, c.coast)) / b.wBlocks;
            double h = Math.max(is.heightAt(b, b.e - lean), c.stackH);
            dens = clamp1((h + j * c.jitterAmp - y) / 3.0);
        }
        boolean anyGround = dens > -1;

        // springs: a shallow, irregular seep - dotted pools between hummocks of mud and moss, in a little hollow
        if (c.spring >= 0 && c.sq < 1.9 && y < is.springs.get(c.spring)[3] + 12) {
            double[] sp = is.springs.get(c.spring);
            double L = sp[3];
            double out = Math.cos(c.sAng) * sp[4] + Math.sin(c.sAng) * sp[5];          // 1 = toward the outlet
            if (c.sq < 1) {
                double top = springBed(c, x, z, sp);
                dens = clamp1(top + 0.5 - y);
            } else {
                dens = Math.min(dens, clamp1((L + 1.5 + (c.sq - 1) * 5 - y) / 2.0));   // the hollow it sits in
                if (out < 0.85 && c.sq < 1.45) dens = Math.max(dens, clamp1(L + 1 - y)); // a low rim, open at the outlet
                else if (out >= 0.85) dens = Math.min(dens, clamp1(L + 0.5 - y));
            }
        }
        // ponds where a stream crosses level ground: a bowl with shelving edges, a low bank holding it in
        if (c.pond >= 0 && c.pq < 2.3 && y < is.ponds.get(c.pond)[3] + 12) {
            double[] pd = is.ponds.get(c.pond);
            double L = pd[3];
            if (c.pq < 1) {
                double bottom = L - 0.6 - Math.min(3.2, 0.9 + pd[2] * 0.35) * (1 - c.pq * c.pq) + 0.6 * is.n[9].get(x / 5.0, z / 5.0);
                dens = clamp1(bottom + 0.5 - y);
            } else {
                dens = Math.min(dens, clamp1((L + 1.5 + (c.pq - 1) * 4 - y) / 2.0));
                if (c.pq < 1.35) dens = Math.max(dens, clamp1(L + 1 - y));
            }
        }
        // plunge pools under the falls: an open bowl where the fall lands, worn back under the lip
        if (anyGround && c.pool >= 0 && c.plq < 1) {
            double[] f = is.falls.get(c.pool);
            double bottom = f[3] - 1 - Math.min(2.5, 1 + (f[2] - f[3]) * 0.15) * (1 - c.plq * c.plq);
            double past = (c.u - f[0]) * f[4] + (c.v - f[1]) * f[5];
            if (y > bottom && (past > 0.6 || y < f[3] + 0.5)) dens = Math.min(dens, clamp1((bottom - y) / 1.5));
            else if (y <= bottom && y > bottom - 4) dens = Math.max(dens, 0.6);
        }

        // rivers
        double tarnSd = c.tarn >= 0 ? c.td - is.tarns.get(c.tarn)[2] : 99;
        int outflow = c.tarn >= 0 ? (int) is.tarns.get(c.tarn)[17] : -1;
        for (int i = 0; anyGround && i < is.rivers.size() && i < Island.MAX_STREAMS; i++) {
            if (c.rd[i] > 90) continue;
            if (tarnSd < 7 && (i != outflow || tarnSd < 0)) continue;     // a tarn is never undercut by a stream
            Island.Path p = is.rivers.get(i);
            double carve = channelCarveW(p, c.rd[i], c.rl[i], y, c.h, c.rw[i], c.rdp[i]);
            if (carve > 0 && p.gorge) {
                for (double bf : p.bridges) {
                    if (Math.abs(c.rs[i] - bf) * p.len < 7 + 3 * is.n[11].get(x / 20.0, z / 20.0)) {
                        double top = c.h - 22, thick = 5 + 12 * Math.min(1, c.rd[i] / 16);
                        double arch = Math.min(top - y, y - (top - thick));
                        if (arch > 0 && top - c.rl[i] > 20) carve = Math.min(carve, -arch);
                    }
                }
            }
            if (carve > 0) dens = Math.min(dens, clamp1(-carve / 2.0));
        }
        // and round each pool a rocky rim a block above its water, solid all the way down, with one low lip where it
        // spills on into the stream below (after the stream's own carving, so the stream never cuts it away)
        if (c.pool >= 0 && c.plq >= 1 && c.plq < 1.45) {
            double[] f = is.falls.get(c.pool);
            double past = (c.u - f[0]) * f[4] + (c.v - f[1]) * f[5];
            boolean exit = false;
            for (int i = 0; i < is.rivers.size() && i < Island.MAX_STREAMS; i++)
                if (is.rivers.get(i).fine != null && c.rd[i] < c.rw[i] + 0.8 && c.rl[i] <= f[3] && past > 0.6) exit = true;
            double rim = exit ? f[3] - 0.5 : f[3] + 1.5 - (c.plq - 1) * 3;
            dens = Math.max(dens, clamp1((rim - y) / 1.5));
            if (exit && y >= f[3]) dens = Math.min(dens, -0.8);
        }
        // sea strait: a great sea tunnel through the island, open to the sky only for its last quarter
        if (anyGround && c.sd < 70) {
            Island.Path p = is.strait;
            double carve;
            if (c.ss < TUNNEL_END) {
                double wt = 13 + c.widN + 2.5 * is.n[11].get(x / 18.0 + y / 14.0, z / 18.0);
                double q = c.sd / wt;
                double ceiling = Math.min(62 + 24 * Math.sqrt(Math.max(0, 1 - q * q)), Math.max(c.h - 10, 64));
                double bed = 38 + 12 * q * q;
                carve = Math.min(Math.min(y - bed, ceiling - y), wt - c.sd);
                // the tunnel mouth at the closed end opens as a sea cave in the cliff
            } else {
                carve = channelCarve(p, c.sd, c.ss, 40, y, c.h, c.widN);
                for (double bf : p.bridges) {
                    if (Math.abs(c.ss - bf) * p.len < 8 + 3 * is.n[11].get(x / 20.0, z / 20.0)) {
                        double top = c.h - 14, thick = 7 + 10 * Math.min(1, c.sd / 14);
                        double arch = Math.min(top - y, y - (top - thick));
                        if (arch > 0) carve = Math.min(carve, -arch);
                    }
                }
            }
            if (carve > 0) dens = Math.min(dens, clamp1(-carve / 2.0));
        }
        // the mere cave: a tunnel from the gorge's waterline into the hill, and the chamber round its lake
        if (c.cvd < 6 && y > 50 && y < 72) {
            double fl = c.cvf, ceil = fl + 4.6 + 1.3 * is.n[11].get(x / 6.0 + y / 5.0, z / 6.0);
            if (c.cvs < 0.07) ceil = Math.min(ceil, 63.6);                  // the mouth: one block of air over the water
            if (y > fl && y < ceil) {
                double vt = (y - fl) / (ceil - fl) * 2 - 1;
                double wr = (2.4 + 0.9 * is.n[12].get(x / 5.0, z / 5.0 + y / 7.0)) * Math.sqrt(Math.max(0, 1 - vt * vt * vt * vt));
                if (c.cvd < wr) dens = Math.min(dens, -0.8);
            }
        }
        if (c.chq < 1.05 && y > 44 && y < 92) {
            double q = Math.min(1, c.chq);
            double floor = 50 + 16 * q * q + 1.4 * is.n[9].get(x / 6.0, z / 6.0);
            double ceil = 64 + 22 * Math.sqrt(Math.max(0, 1 - q * q)) + 2.2 * is.n[13].get(x / 8.0 + y / 9.0, z / 8.0);
            if (c.chq < 1 && y > floor && y < ceil) dens = Math.min(dens, -0.8);
        }
        // the lush passages and the spiral up to the meadow slope
        if (c.p1d < 6 && passage(c.p1d, c.p1f, x, y, z, is)) dens = Math.min(dens, -0.8);
        if (c.p2d < 6 && passage(c.p2d, c.p2f, x, y, z, is)) dens = Math.min(dens, -0.8);
        if (c.hxd < 4.5) {
            double phase = ((c.hxa - is.helixA0) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) / (2 * Math.PI);
            double k = Math.floor((y - is.helixY0) / is.helixRise - phase);
            for (double kk = k - 1; kk <= k + 1; kk++) {
                double t = kk + phase;
                if (t < -0.05 || t > is.helixTurns + 0.05) continue;
                double fl = is.helixY0 + is.helixRise * t;
                if (y > fl && y < fl + 5 && c.hxd < 3.1 + 0.6 * is.n[12].get(x / 5.0, z / 5.0)) dens = Math.min(dens, -0.8);
            }
        }
        // tarns: a bowl under the water, the ground under it solid; round the shore a rim wherever the land dips below
        // the water (filled solid all the way down, so it never hangs over anything); one notch where it spills out
        if (c.tarn >= 0) {
            double[] t = is.tarns.get(c.tarn);
            double L = t[3], sd = c.td - t[2];
            boolean notch = c.tnd < 1.3 && sd > -1.5 && sd < 8;
            if (sd < 0) {
                double in = -sd;
                double bottom = Math.min(L - 1 - Math.min(6, 0.6 + in * 0.8) + 0.7 * is.n[9].get(x / 9.0, z / 9.0), c.h - 1);
                if (y > bottom) dens = Math.min(dens, clamp1(-(y - bottom) / 2.0));
                else if (y > bottom - 6) dens = Math.max(dens, 0.6);
            } else if (sd < 7) {
                boolean outCut = outflow >= 0 && c.rd[outflow] < c.rw[outflow] + 1.5 && y >= L - 1;
                if (notch) {
                    if (y < L - 1) dens = Math.max(dens, 0.6);
                    else dens = Math.min(dens, -0.8);
                } else if (!outCut) dens = Math.max(dens, clamp1((Island.tarnRim(L, sd) - y) / 1.5));
            }
        }
        // great arch: springs from the hillside and lands in the lake
        double au = is.aHiU - is.aLoU, av = is.aHiV - is.aLoV, al2 = au * au + av * av, al = Math.sqrt(al2);
        double pu = c.u - is.aLoU, pv = c.v - is.aLoV;
        double t = (pu * au + pv * av) / al2;
        if (t > -0.12 && t < 1.12) {
            double w = Math.abs(pu * av - pv * au) / al;
            if (w < 26) {
                double tc = Math.max(0, Math.min(1, t)), sn = Math.sin(Math.PI * tc);
                double yc = is.aLoH + (is.aHiH - is.aLoH) * tc + is.aRise * sn;
                double wob = 2.5 * is.n[11].get(x / 12.0 + y / 10.0, z / 12.0);
                double thV = 6 + 8 * (1 - sn) + wob, thW = 8 + 6 * (1 - sn) + wob;
                boolean solid = Math.abs(y - yc) < thV && w < thW;
                if (tc < 0.12 && t > -0.12 && y < yc && y > 20 && w < thW * 1.25) solid = true;   // lake-side leg
                if (solid) dens = Math.max(dens, 0.8);
            }
        }
        return dens;
    }

    /**
     * Top solid block of a spring's floor at this column: wet hollows (water stands on them) and dry hummocks of mud
     * and moss flush with the water, so the seep shows as dotted pools rather than one round pond.
     */
    double springBed(Column c, int x, int z, double[] sp) {
        double L = sp[3];
        double blot = c.is.n[11].get(x / 2.2 + sp[6], z / 2.2) + 0.5 * c.is.n[13].get(x / 1.3, z / 1.3 - sp[6]);
        boolean wet = c.sq < 0.35 || blot > -0.1 - 0.35 * (1 - c.sq);
        if (!wet) return L;
        return c.sq < 0.55 ? L - 2 : L - 1;
    }

    /** Stream-side facts for the decoration pass (StreamDecor). */
    public double streamDist(Column c, int i) { return c.rd[i]; }
    public double streamWidth(Column c, int i) { return Math.max(c.rw[i], 0.45); }
    public double streamLevel(Column c, int i) { return c.rl[i]; }
    public double streamFlow(Column c, int i) {
        Island.Path p = c.is.rivers.get(i);
        if (p.flow == null) return 0;
        int k = (int) Math.round(c.rs[i] * (p.flow.length - 1));
        return p.flow[Math.max(0, Math.min(p.flow.length - 1, k))];
    }
    public boolean isStream(Column c, int i) { return !c.is.rivers.get(i).gorge && c.is.rivers.get(i).wid != null; }
    public int streamCount(Column c) { return c.is == null ? 0 : Math.min(c.is.rivers.size(), Island.MAX_STREAMS); }
    public int springOf(Column c) { return c.spring; }
    public double springQ(Column c) { return c.sq; }
    public int pondOf(Column c) { return c.pond; }
    public double pondQ(Column c) { return c.pq; }
    public int poolOf(Column c) { return c.pool; }
    public double poolQ(Column c) { return c.plq; }

    /** Water that the island itself holds: its lakes (tarns, the great lake) and anything below sea level. */
    public boolean isWater(Column c, int x, int y, int z) {
        if (c.is == null || c.m <= 0.5) return false;
        Island is = c.is;
        if (y <= 62) return density(c, x, y, z) < 0;
        // the island's own streams: water up to each stretch's level, inside its channel
        for (int i = 0; i < is.rivers.size() && i < Island.MAX_STREAMS; i++) {
            Island.Path p = is.rivers.get(i);
            if (p.gorge || c.rd[i] > c.rw[i] + 1) continue;
            // only where the land holds it: past a lip where the ground falls away the water pours on down by itself
            if (c.hL < c.rl[i] - 2) continue;
            // and never above the ground beside it: no water heaped on the surface where a stream sinks or spills
            // and only as deep as its bed: down a drop it pours rather than standing in a tall column
            if (c.rd[i] < Math.max(c.rw[i], 0.45) + 0.5 && y <= Math.min(Math.floor(c.rl[i]), Math.floor(c.hL))
                    && y >= Math.floor(c.rl[i]) - Math.ceil(c.rdp[i]) - 3 && density(c, x, y, z) < 0) return true;
        }
        if (c.spring >= 0 && c.sq < 1) {
            double[] sp = is.springs.get(c.spring);
            if (y <= sp[3] && y > springBed(c, x, z, sp) && density(c, x, y, z) < 0) return true;
        }
        if (c.pond >= 0 && c.pq < 1 && y <= is.ponds.get(c.pond)[3] && density(c, x, y, z) < 0) return true;
        if (c.pool >= 0 && c.plq < 1 && y <= is.falls.get(c.pool)[3] && density(c, x, y, z) < 0) return true;
        if (c.tarn >= 0) {
            double[] t = is.tarns.get(c.tarn);
            if (c.td < t[2] - 0.3 && y <= Math.floor(t[3]) && y >= t[3] - 9 && density(c, x, y, z) < 0) return true;
        }
        return false;
    }

    /** Tarn the column belongs to (or -1), its outline-relative distance from the centre, and the tarn's radius. */
    public int tarnOf(Column c) { return c.is == null ? -1 : c.tarn; }
    public double tarnDist(Column c) { return c.td; }
    public double tarnRadius(Column c) { return c.tarn < 0 ? 0 : c.is.tarns.get(c.tarn)[2]; }
    public int tarnLevel(Column c) { return c.tarn < 0 ? 0 : (int) Math.floor(c.is.tarns.get(c.tarn)[3]); }

    /** Inside the inner part of a tarn (where springs bubble up from the floor)? */
    public boolean inTarnCore(Column c) {
        return c.is != null && c.tarn >= 0 && c.td < c.is.tarns.get(c.tarn)[2] * 0.75;
    }

    public int waterTop(Column c) {
        double top = 62;
        if (c.is == null) return (int) top;
        if (c.tarn >= 0 && c.td < c.is.tarns.get(c.tarn)[2]) top = Math.max(top, c.is.tarns.get(c.tarn)[3]);
        for (int i = 0; i < c.is.rivers.size() && i < Island.MAX_STREAMS; i++)
            if (!c.is.rivers.get(i).gorge && c.rd[i] < c.rw[i] + 1.5) top = Math.max(top, c.rl[i]);
        if (c.spring >= 0 && c.sq < 1) top = Math.max(top, c.is.springs.get(c.spring)[3]);
        if (c.pond >= 0 && c.pq < 1) top = Math.max(top, c.is.ponds.get(c.pond)[3]);
        if (c.pool >= 0 && c.plq < 1) top = Math.max(top, c.is.falls.get(c.pool)[3]);
        return (int) Math.floor(top);
    }

    public boolean nearRiver(Column c, double reach) {
        if (c.is == null) return false;
        for (int i = 0; i < c.is.rivers.size() && i < Island.MAX_STREAMS; i++) if (c.rd[i] < reach) return true;
        return c.sd < reach;
    }

    /**
     * A dry, fairly flat spot on the island's lowland coast where arrivals land: searched along the island's
     * shore away from rivers, the strait and the megalake.
     */
    public int[] findLanding(Island is) { return findLandings(is, 1).get(0); }

    /** Up to n dry, fairly flat coastal spots, best first, at least 70 blocks apart. */
    public java.util.List<int[]> findLandings(Island is, int n) {
        Column c = new Column(), d = new Column();
        java.util.List<double[]> cands = new java.util.ArrayList<>();
        for (int i = 0; i <= 90; i++) {
            double s = 0.05 + 0.9 * i / 90.0;
            for (double side : new double[]{-0.9, -0.82, -0.74, 0.74, 0.82, 0.9}) {
                double[] p = is.pointAt(s, side);
                int[] w = is.toWorld(p[0], p[1]);
                fill(c, w[0], w[1]);
                if (c.m < 1 || c.h < 65.5 || c.h > 96 || nearRiver(c, 22)) continue;
                if (c.tarn >= 0 && c.td < 40) continue;
                double slope = 0;
                for (int[] o : new int[][]{{4, 0}, {-4, 0}, {0, 4}, {0, -4}}) { fill(d, w[0] + o[0], w[1] + o[1]); slope += Math.abs(d.h - c.h); }
                cands.add(new double[]{slope + Math.abs(c.h - 70) * 0.3, w[0], Math.ceil(c.h), w[1]});
            }
        }
        cands.sort((x, y) -> Double.compare(x[0], y[0]));
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (double[] k : cands) {
            boolean far = true;
            for (int[] o : out) if (Math.hypot(o[0] - k[1], o[2] - k[3]) < 70) { far = false; break; }
            if (far) out.add(new int[]{(int) k[1], (int) k[2], (int) k[3]});
            if (out.size() >= n) break;
        }
        if (out.isEmpty()) { int[] w = is.landingCove(); out.add(new int[]{w[0], 80, w[1]}); }
        return out;
    }

    /** Which island biome a position belongs to. */
    public Zone zone(Column c, int y) {
        if (c.is == null || c.m <= 0.5 || c.coast > 0.5) return Zone.NONE;
        Island.Base b = c.b;
        if (c.tarn >= 0) {
            double[] t = c.is.tarns.get(c.tarn);
            // the whole tarn surroundings, at every height, so Streams Reflowing (which reads biomes at sea level) leaves it alone
            if (c.td < t[2] + 18 && y < t[3] + 16) return Zone.TARN;
        }
        if (c.h > 232 && y > 226) return Zone.PEAKS;
        for (int i = 0; i < c.is.rivers.size() && i < Island.MAX_STREAMS; i++) {
            Island.Path p = c.is.rivers.get(i);
            if (!p.gorge) continue;                     // the small streams keep the land's own biome
            double reach = 26 + p.width1;
            if (c.rd[i] < reach && y > c.rl[i] - 8 && y < c.rl[i] + 200) return Zone.GORGE;
        }
        if (c.sd < 34 && y > 30 && (c.ss >= TUNNEL_END || y < 90)) return Zone.GORGE;
        if (b.shelfM > 0.5 && y > c.h - 14) return Zone.MEADOW;
        if (b.marshW > 0.5 && c.h < 90) return Zone.PLAINS;
        if (b.e > 0.8 && c.h < 70) return Zone.SHORE;
        if (c.h < 150 && b.hw < 0.75) return Zone.FOREST;
        return Zone.CLIFFS;
    }
}
