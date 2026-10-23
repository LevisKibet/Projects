package com.ronald.ancientpeaks.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One Ancient Highlands island (pure Java, previewable outside the game).
 *
 * Shape: a long, bending strip built around a curved spine (s = 0..1 along it, e = 0 on the spine, 1 at the coast).
 * The low end (s ~ 0) holds the megalake grove and landing beach; forested hills rise toward the main massif and a
 * secondary peak with glacier caps. A meadow shelf sits partway up the main massif's flank, a sea strait cuts across
 * the island, rivers start at mountain tarns, and a great arch springs from the hillside into the lake.
 */
public final class Island {
    public static final double SHELF = 170, SEA = 63, LEAN = 0.13;

    public final int cx, cz;
    final double cos, sin;
    final Perlin[] n = new Perlin[14];
    public final double boundR;

    // spine
    final double[] spU, spV, spL; final double spLen;
    final double w0, wa1, wp1, wa2, wp2;
    // elevation along the spine
    final double peakS, peakH, peak2S, peak2H, hillS, hillH, marshEnd;
    // meadow shelf
    final double shU, shV, shR;
    private boolean shelfReady = false;
    private double shSide;
    // sea strait
    Path strait;
    // rivers and tarns
    final List<Path> rivers = new ArrayList<>();
    final List<double[]> tarns = new ArrayList<>();           // u, v, radius, level, kind(0 fall, 1 neck), outU, outV
    // great arch: low end (in the lake), high end (on the hillside)
    double aLoU, aLoV, aLoH, aHiU, aHiV, aHiH, aRise;
    // sea stacks (u, v, radius, height)
    final List<double[]> stacks = new ArrayList<>();
    // landing beach
    double landU, landV;

    /** A river, stream or strait: dense polyline with a water level per point. */
    static final class Path {
        final double[] u, v, l, level; final double len;
        final double width0, width1, depth0, depth1, bankSlope; final boolean gorge;
        final double[] bridges;
        Path(double[][] uv, double[] level, double width0, double width1, double depth0, double depth1, double bankSlope, boolean gorge, double[] bridges) {
            this.u = uv[0]; this.v = uv[1]; this.level = level; this.l = new double[u.length];
            double acc = 0; l[0] = 0;
            for (int i = 1; i < u.length; i++) { acc += Math.hypot(u[i] - u[i - 1], v[i] - v[i - 1]); l[i] = acc; }
            this.len = Math.max(acc, 1);
            this.width0 = width0; this.width1 = width1; this.depth0 = depth0; this.depth1 = depth1;
            this.bankSlope = bankSlope; this.gorge = gorge; this.bridges = bridges;
            double a = 1e18, b = -1e18, c = 1e18, d = -1e18;
            for (int i = 0; i < u.length; i++) { a = Math.min(a, u[i]); b = Math.max(b, u[i]); c = Math.min(c, v[i]); d = Math.max(d, v[i]); }
            minU = a; maxU = b; minV = c; maxV = d;
        }
        final double minU, maxU, minV, maxV;
        /** Streams only: flow gathered at each point, and the half-width and depth it gives. */
        double[] flow, wid, dep;
        /** Streams only: the water level every block along (index = distance along the path). */
        double[] fine;
        /** Quick test: could (u, v) be within `reach` of this path? */
        boolean near(double u, double v, double reach) {
            return u > minU - reach && u < maxU + reach && v > minV - reach && v < maxV + reach;
        }
    }

    /** Cached per-column base terrain terms (everything except the per-block lean and carving). */
    static final class Base {
        double rough, warp, s, e, side, wBlocks, hs, coastH, rug, step, hw, marshW, marshH, shelfM, shelfH, shelfD, seabed, coastK, tarnLift;
    }

    public Island(int cx, int cz, long seed) {
        this.cx = cx; this.cz = cz;
        Random r = new Random(seed);
        double rot = r.nextDouble() * Math.PI * 2;
        cos = Math.cos(rot); sin = Math.sin(rot);
        for (int i = 0; i < n.length; i++) n[i] = new Perlin(r.nextLong());

        // --- spine: a bending strip 1000-1350 blocks long
        double length = 1000 + r.nextDouble() * 350;
        int k = 5;
        double[] cu = new double[k], cv = new double[k];
        double bend = r.nextGaussian() * 140;
        for (int i = 0; i < k; i++) {
            double t = i / (double) (k - 1);
            cu[i] = (t - 0.5) * length;
            cv[i] = bend * Math.sin(Math.PI * t) + r.nextGaussian() * 45;
        }
        double[][] sp = spline(cu, cv, 16);
        spU = sp[0]; spV = sp[1]; spL = new double[spU.length];
        double acc = 0;
        for (int i = 1; i < spU.length; i++) { acc += Math.hypot(spU[i] - spU[i - 1], spV[i] - spV[i - 1]); spL[i] = acc; }
        spLen = acc;
        w0 = 200 + r.nextDouble() * 50; wa1 = 0.22 + r.nextDouble() * 0.1; wp1 = r.nextDouble(); wa2 = 0.12; wp2 = r.nextDouble();

        marshEnd = 0.30 + r.nextDouble() * 0.04;
        hillS = 0.40 + r.nextDouble() * 0.04; hillH = 45 + r.nextDouble() * 30;
        peakS = 0.62 + r.nextDouble() * 0.06; peakH = 172 + r.nextDouble() * 28;
        peak2S = 0.86 + r.nextDouble() * 0.05; peak2H = 120 + r.nextDouble() * 40;

        // --- meadow shelf on the main massif's flank (not the summit)
        shSide = r.nextBoolean() ? 1 : -1;
        double[] shP = pointAt(peakS - 0.07 + r.nextDouble() * 0.04, shSide * (0.42 + r.nextDouble() * 0.08));
        shU = shP[0]; shV = shP[1]; shR = 85 + r.nextDouble() * 25;

        double maxW = 0;
        for (int i = 0; i < spU.length; i++) maxW = Math.max(maxW, Math.hypot(spU[i], spV[i]));
        boundR = maxW + w0 * 1.45 + SHELF;
        this.r = r;
    }

    /** Island streams of our own - off when Streams Reflowing is installed (it brings its own, and they win). */
    public static volatile boolean OWN_STREAMS = true;
    /**
     * Whether streams may run across the lowland plains. Chronicles of Logres builds its village and castle there, so
     * with it installed the streams go round; without it they flow straight across to the sea.
     */
    public static volatile boolean PLAINS_OPEN = true;
    private Random r;
    private volatile boolean complete;

    /**
     * The costly part (strait, shelf, tarns, streams, arch, stacks, landing), done once the island's spot is chosen:
     * the placement search tries many spots and only needs the outline (spine, width, bounds) to judge them.
     */
    public synchronized Island complete() {
        if (complete) return this;
        Random r = this.r;
        double shSide = this.shSide;
        // --- sea strait across the island between the forest hills and the massif
        double sS = marshEnd + 0.10 + r.nextDouble() * 0.06;
        int m = 6;
        double[] su = new double[m], sv = new double[m];
        for (int i = 0; i < m; i++) {
            double t = i / (double) (m - 1);
            double[] p = pointAt(sS + (r.nextDouble() - 0.5) * 0.025, (t * 2 - 1) * 1.35 * -shSide);
            su[i] = p[0]; sv[i] = p[1];
        }
        double[][] st = spline(su, sv, 10);
        double[] stLev = new double[st[0].length];
        java.util.Arrays.fill(stLev, 40);
        // the strait runs as a sea tunnel through the island; only its last quarter (the shelf side) is open to the sky,
        // where one remnant of the old roof still stands as an arch
        strait = new Path(st, stLev, 11, 11, 0, 0, 6, true, new double[]{0.73 + r.nextDouble() * 0.03});

        // the shelf level: a little below the local massif height
        Base b = new Base();
        baseAt(shU, shV, b, false);
        double hHere = heightAt(b, b.e);
        shLevelHolder = Math.max(120, Math.min(hHere - 25, 175));
        shelfReady = true;

        // --- tarns, and the streams that run out of them (buildStreams).
        // Two kinds: WATERFALL tarns sit on a cliff lip, in a bowl whose rim has a single one-block notch where the
        // water spills over; NECK tarns sit on gentler ground and drain through a narrow neck channel downhill.
        pickTarns(r, b);
        if (OWN_STREAMS) buildStreams(r, b);

        // --- great arch: springs from the hillside (high end) and lands in the megalake (low end)
        double aSide = r.nextBoolean() ? 1 : -1;
        archSide = aSide;
        double[] hi = pointAt(marshEnd + 0.035, aSide * 0.22);
        double[] lo = pointAt(marshEnd - 0.075, aSide * 0.45);
        aHiU = hi[0]; aHiV = hi[1]; aLoU = lo[0]; aLoV = lo[1];
        baseAt(aHiU, aHiV, b, false); aHiH = heightAt(b, b.e) - 6;
        aLoH = 63;
        aRise = 42 + r.nextDouble() * 16;

        // --- sea stacks off the high coasts
        int count = 5 + r.nextInt(5);
        for (int i = 0; i < count; i++) {
            double s = 0.45 + r.nextDouble() * 0.55;
            double[] p = pointAt(s, (r.nextBoolean() ? 1 : -1) * (1.08 + r.nextDouble() * 0.12));
            stacks.add(new double[]{p[0], p[1], 6 + r.nextDouble() * 8, 90 + r.nextDouble() * 70});
        }
        double[] land = pointAt(0.0, 0);
        double[] land2 = pointAt(0.03, 0);
        double dx = land[0] - land2[0], dy = land[1] - land2[1], dl = Math.hypot(dx, dy);
        landU = land[0] + dx / dl * w0 * 0.35; landV = land[1] + dy / dl * w0 * 0.35;
        pickCave(b);
        complete = true;
        return this;
    }

    // ------------------------------------------------------------------ Vortigern's mere
    // A cave in the wall of the strait's open gorge: its mouth sits at the waterline (you swim in under the rock), a
    // tunnel rises and bends back into the mountain and opens into a great chamber round a still black lake, hung
    // with dripstone. (Its own random, so nothing else on the island moves.)
    public boolean hasCave;
    Path caveTunnel;
    double caveCU, caveCV, caveNU, caveNV, caveTU, caveTV;
    double caveA = 26, caveB = 36;                         // the chamber's half-lengths: into the hill, and across
    // the lush way up: a passage from the lake to the foot of a spiral, the spiral, and a passage out onto the
    // mountainside facing the swords' meadow
    Path caveUp1, caveUp2;
    double helixU, helixV, helixR = 9, helixY0, helixRise = 13, helixTurns, helixA0;

    private void pickCave(Base b) {
        Random cr = new Random(cx * 341873128712L ^ cz * 132897987541L ^ 0x6d657265L);
        Path st = strait;
        int n = st.u.length;
        double[] fr = {0.86, 0.82, 0.90, 0.79, 0.93, 0.84, 0.88, 0.80, 0.95, 0.71};
        Object[] fallback = null;
        double[] o = new double[5];
        for (double scale : new double[]{1, 0.78, 0.6}) {
        if (hasCave) break;
        caveA = 26 * scale; caveB = 36 * scale;
        for (double depth : new double[]{46 + 26 * scale, 36 + 26 * scale, 60 + 26 * scale})
        for (double f : fr) {
            for (int sideI = 0; sideI < 4; sideI++) {
                int i = 1;
                while (i < n - 2 && st.l[i] / st.len < f) i++;
                double tu = st.u[i + 1] - st.u[i - 1], tv = st.v[i + 1] - st.v[i - 1], tl = Math.hypot(tu, tv);
                tu /= tl; tv /= tl;
                // first the side of the gorge the swords' meadow is on (the way up must not cross the strait)
                double toMeadow = -tv * (shU - st.u[i]) + tu * (shV - st.v[i]);
                double side = (sideI < 2 ? 1 : -1) * (toMeadow >= 0 ? 1 : -1);
                double nu = -tv * side, nv = tu * side;
                double pu = st.u[i], pv = st.v[i];
                double bend = sideI % 2 == 0 ? 1 : -1;
                double cu = pu + nu * depth + tu * 16 * bend, cv = pv + nv * depth + tv * 16 * bend;
                // the chamber must sit deep in the hill, clear of the gorge, the tarns and the streams
                boolean ok = true;
                for (int k = 0; k < 9 && ok; k++) {
                    double a = k * Math.PI / 4, rr = k == 8 ? 0 : 1.15;
                    double su = cu + (Math.cos(a) * nu * caveA + Math.sin(a) * tu * caveB) * rr;
                    double sv = cv + (Math.cos(a) * nv * caveA + Math.sin(a) * tv * caveB) * rr;
                    baseAt(su, sv, b, false);
                    if (b.e > 0.95 || heightAt(b, b.e) < 94) ok = false;
                    pathDist(st, su, sv, o);
                    if (o[0] < 34) ok = false;
                    for (double[] t : tarns) if (Math.hypot(t[0] - su, t[1] - sv) < t[14] + 18) ok = false;
                    for (Path p : rivers) { if (!p.near(su, sv, 20)) continue; pathDist(p, su, sv, o); if (o[0] < 14) ok = false; }
                }
                if (!ok) continue;
                // the tunnel: from the mouth at the gorge wall, curving round to the chamber's near side
                double mu = pu + nu * 14, mv = pv + nv * 14;
                double ku = pu + nu * depth * 0.58 - tu * 6 * bend, kv = pv + nv * depth * 0.58 - tv * 6 * bend;
                double eu = cu - nu * caveA * 0.75, ev = cv - nv * caveA * 0.75;
                int m = 40;
                double[] us = new double[m], vs = new double[m], fl = new double[m];
                for (int k = 0; k < m; k++) {
                    double s = k / (double) (m - 1), a1 = (1 - s) * (1 - s), b1 = 2 * (1 - s) * s, c1 = s * s;
                    us[k] = a1 * mu + b1 * ku + c1 * eu; vs[k] = a1 * mv + b1 * kv + c1 * ev;
                    fl[k] = s < 0.12 ? 57 : s < 0.38 ? 57 + (s - 0.12) / 0.26 * 6.5 : 63.5 - Math.max(0, s - 0.85) * 6;
                }
                caveTunnel = new Path(new double[][]{us, vs}, fl, 0, 0, 0, 0, 1, false, new double[0]);
                caveCU = cu; caveCV = cv; caveNU = nu; caveNV = nv; caveTU = tu; caveTV = tv;
                hasCave = true;
                if (pickWayUp(b, cr)) return;
                if (fallback == null) fallback = new Object[]{caveTunnel, new double[]{cu, cv, nu, nv, tu, tv, caveA, caveB}};
                caveUp1 = caveUp2 = null; hasCave = false;
            }
        }
        }
        if (fallback != null && !hasCave) {                                // a lake with no way up is still a lake
            caveTunnel = (Path) fallback[0];
            double[] f = (double[]) fallback[1];
            caveCU = f[0]; caveCV = f[1]; caveNU = f[2]; caveNV = f[3]; caveTU = f[4]; caveTV = f[5]; caveA = f[6]; caveB = f[7];
            hasCave = true;
        }
    }

    /** From the lake chamber up a spiral to a mouth on the slope facing the meadow (where the swords are). */
    public static final int[] WHY = new int[8];
    private boolean pickWayUp(Base b, Random cr) {
        double gu = caveCU - shU, gv = caveCV - shV, gl = Math.hypot(gu, gv);
        gu /= gl; gv /= gl;
        double[] o = new double[5];
        for (int ti = 0; ti < 24; ti++) {
            double turn = (ti % 2 == 0 ? 1 : -1) * Math.PI * ((ti + 1) / 2) / 12.0;
            double du = gu * Math.cos(turn) - gv * Math.sin(turn), dv = gu * Math.sin(turn) + gv * Math.cos(turn);
            for (double out : new double[]{10, 18, 28, 40}) {
                double xu = shU + du * (shR + out), xv = shV + dv * (shR + out);         // the mouth, on the slope
                baseAt(xu, xv, b, false);
                if (b.e > 0.92) { WHY[0]++; continue; }
                double hx = heightAt(b, b.e);
                double hu = xu + du * 22, hv = xv + dv * 22;                              // the spiral, back in the hill
                baseAt(hu, hv, b, false);
                double hh = heightAt(b, b.e);
                if (hh < hx + 3 || hx < 75) { WHY[1]++; continue; }
                pathDist(strait, hu, hv, o);
                if (o[0] < 34) { WHY[2]++; continue; }
                boolean wet = false;
                for (double[] t : tarns) if (Math.hypot(t[0] - hu, t[1] - hv) < t[14] + 18) wet = true;
                if (wet) { WHY[3]++; continue; }
                helixU = hu; helixV = hv;
                helixY0 = 65;
                double top = hx - 10;
                helixTurns = Math.max(1, (top - helixY0) / helixRise);
                helixA0 = Math.atan2(caveCV - hv, caveCU - hu);                           // the spiral's foot faces the lake
                // the passage from the lake to the spiral's foot, wandering a little
                double su = caveCU + Math.cos(helixA0 + Math.PI) * 0 , sv = caveCV;
                double eu = hu + Math.cos(helixA0) * helixR, ev = hv + Math.sin(helixA0) * helixR;
                double bu = caveCU + (eu - caveCU) * 0.15, bv = caveCV + (ev - caveCV) * 0.15;
                caveUp1 = wander(bu, bv, eu, ev, 64, helixY0, cr);
                // and from the spiral's top (half a turn on) out to the mouth
                double ta = helixA0 + 2 * Math.PI * helixTurns;
                double tu0 = hu + Math.cos(ta) * helixR, tv0 = hv + Math.sin(ta) * helixR;
                double topY = helixY0 + helixRise * helixTurns;
                caveUp2 = wander(tu0, tv0, xu - du * 4, xv - dv * 4, topY, hx - 0.5, cr);
                if (!buried(caveUp1, b, 1.0)) { WHY[4]++; continue; }
                if (!buried(caveUp2, b, 0.4)) { WHY[5]++; continue; }
                return true;
            }
        }
        return false;
    }

    /** Does the passage stay inside the hill (clear of the strait, rock over its roof) for the given share of its length? */
    private boolean buried(Path p, Base b, double share) {
        double[] o = new double[5];
        int n = p.u.length, lim = (int) Math.ceil(n * share);
        for (int k = 0; k < n; k++) {
            pathDist(strait, p.u[k], p.v[k], o);
            if (o[0] < 26) { WHY[6]++; return false; }
            if (k >= lim) continue;
            baseAt(p.u[k], p.v[k], b, false);
            if (b.e > 0.95 || heightAt(b, b.e) < p.level[k] + 9) { WHY[7]++; return false; }
        }
        return true;
    }

    /** A gently wandering passage from a to b, its floor rising evenly from y0 to y1. */
    private static Path wander(double au, double av, double bu, double bv, double y0, double y1, Random r) {
        double len = Math.hypot(bu - au, bv - av);
        int m = Math.max(8, (int) (len / 3));
        double nu = -(bv - av) / len, nv = (bu - au) / len, ph = r.nextDouble() * 6.28, amp = Math.min(14, len * 0.12);
        double[] us = new double[m], vs = new double[m], fl = new double[m];
        for (int k = 0; k < m; k++) {
            double s = k / (double) (m - 1), w = Math.sin(Math.PI * s) * amp * Math.sin(s * 9 + ph);
            us[k] = au + (bu - au) * s + nu * w; vs[k] = av + (bv - av) * s + nv * w;
            fl[k] = y0 + (y1 - y0) * s;
        }
        return new Path(new double[][]{us, vs}, fl, 0, 0, 0, 0, 1, false, new double[0]);
    }

    /** The cave's way out onto the mountainside by the meadow {x, z} (or null). */
    public int[] caveExit() { return caveUp2 == null ? null : toWorld(caveUp2.u[caveUp2.u.length - 1], caveUp2.v[caveUp2.v.length - 1]); }

    /** The cave's mouth in world space {x, z} (or null). */
    public int[] caveMouth() { return hasCave ? toWorld(caveTunnel.u[0], caveTunnel.v[0]) : null; }
    /** The middle of the cave's lake in world space {x, z} (or null). */
    public int[] caveLake() { return hasCave ? toWorld(caveCU, caveCV) : null; }

    private double shLevelHolder;
    private double archSide = 1;
    public double shelfLevel() { return shLevelHolder; }

    // ------------------------------------------------------------------ geometry helpers
    /** World-local point at spine position s with signed cross offset (in units of local half-width). */
    double[] pointAt(double s, double crossE) {
        s = Math.max(0, Math.min(1, s));
        double target = s * spLen;
        int i = 1;
        while (i < spL.length - 1 && spL[i] < target) i++;
        double t = (target - spL[i - 1]) / Math.max(spL[i] - spL[i - 1], 1e-6);
        double u = spU[i - 1] + (spU[i] - spU[i - 1]) * t, v = spV[i - 1] + (spV[i] - spV[i - 1]) * t;
        double tu = spU[i] - spU[i - 1], tv = spV[i] - spV[i - 1], tl = Math.hypot(tu, tv);
        double nu = -tv / tl, nv = tu / tl;
        double w = width(s);
        return new double[]{u + nu * crossE * w, v + nv * crossE * w};
    }

    double width(double s) {
        double taper = 0.35 + 0.65 * Math.sqrt(smooth(0, 0.10, s) * smooth(1, 0.90, s));
        return w0 * (1 + wa1 * Math.sin(2 * Math.PI * (s * 1.3 + wp1)) + wa2 * Math.sin(2 * Math.PI * (s * 3.1 + wp2))) * taper;
    }

    static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
    static double terrace(double h, double step, double sharp) {
        double t = h / step, fl = Math.floor(t), fr = t - fl;
        return (fl + smooth(sharp, 1.0, fr)) * step;
    }
    static double gauss(double s, double c, double w) { double d = (s - c) / w; return Math.exp(-d * d); }
    double fbm(Perlin p, double x, double z, int oct) {
        double s = 0, a = 1, f = 1, norm = 0;
        for (int i = 0; i < oct; i++) { s += a * p.get(x * f + i * 17.3, z * f - i * 9.1); norm += a; a *= 0.5; f *= 2; }
        return s / norm;
    }
    double ridged(Perlin p, double x, double z, int oct) {
        double s = 0, a = 1, f = 1, norm = 0;
        for (int i = 0; i < oct; i++) { double q = 1 - Math.abs(p.get(x * f + i * 31.7, z * f + i * 11.3)); s += a * q * q; norm += a; a *= 0.5; f *= 2.1; }
        return s / norm;
    }

    /** Closest spine point: fills s, signed side and distance. */
    void spineCoords(double u, double v, double[] out) {
        double best = Double.MAX_VALUE, bs = 0, side = 1;
        for (int i = 0; i < spU.length - 1; i++) {
            double ax = spU[i], ay = spV[i], dx = spU[i + 1] - ax, dy = spV[i + 1] - ay;
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((u - ax) * dx + (v - ay) * dy) / l2));
            double qx = u - (ax + t * dx), qy = v - (ay + t * dy);
            double d = qx * qx + qy * qy;
            if (d < best) { best = d; bs = spL[i] + t * Math.sqrt(l2); side = Math.signum(dx * qy - dy * qx); }
        }
        out[0] = bs / spLen; out[1] = Math.sqrt(best); out[2] = side == 0 ? 1 : side;
    }

    // ------------------------------------------------------------------ base terrain
    void baseAt(double u, double v, Base b, boolean withTarns) {
        double[] sc = new double[3];
        spineCoords(u, v, sc);
        double s = sc[0];
        b.s = s; b.side = sc[2];
        b.wBlocks = width(s);
        double e = sc[1] / b.wBlocks;
        e *= 1 + 0.09 * n[0].get(u / 170, v / 170) + 0.035 * n[1].get(u / 45, v / 45);
        b.e = e;
        b.hs = 72 + hillH * gauss(s, hillS, 0.12) + peakH * gauss(s, peakS, 0.19) + peak2H * gauss(s, peak2S, 0.12);
        b.coastH = Math.max(63, Math.min(175, 63 + (b.hs - 72) * 0.5));
        double hi = Math.max(0, Math.min(1, (b.hs - 100) / 160));
        b.hw = hi;
        b.rug = (b.hs - 72) / 230 * (26 * ridged(n[2], u / 120, v / 120, 4) - 9) + 5 * n[3].get(u / 55, v / 55);
        b.step = 22 * (1 + 0.3 * n[4].get(u / 170, v / 170));
        // break up smooth, uniform slopes: mid-scale lumps, small knolls and rocky crags
        double wild = (1 - smooth(0.85, 1.0, e));
        b.rough = wild * (5.5 * fbm(n[3], u / 38 + 11, v / 38, 3) + 2.2 * n[4].get(u / 11, v / 11)
                + 14 * Math.pow(Math.max(0, ridged(n[1], u / 70 + 5, v / 70, 2) - 0.72) / 0.28, 2));
        b.warp = 9 * n[8].get(u / 55 + 3, v / 55);
        double sw = s + 0.06 * n[10].get(u / 140 + 7, v / 140);
        b.marshW = 1 - smooth(marshEnd - 0.08, marshEnd + 0.04, sw);
        // lowland plains (for the village and castle): broad, gently rolling ground a little above the sea
        b.marshH = 67.5 + 3.2 * fbm(n[5], u / 110, v / 110, 3) + 1.4 * n[6].get(u / 34, v / 34);
        // meadow shelf (with noisy rim)
        double pd = Math.hypot(u - shU, v - shV), pa = Math.atan2(v - shV, u - shU);
        double rr = shR * (1 + 0.28 * n[7].get(Math.cos(pa) * 2.3, Math.sin(pa) * 2.3) + 0.08 * n[8].get(Math.cos(pa) * 7, Math.sin(pa) * 7));
        b.shelfD = pd / rr;
        b.shelfM = shelfReady ? 1 - smooth(0.93, 1.0, b.shelfD) : 0;
        b.shelfH = shLevelHolder + 3 * fbm(n[8], u / 60, v / 60, 2);
        b.seabed = 34 + 8 * n[9].get(u / 90, v / 90);
        b.coastK = 0.14 + (0.016 - 0.14) * smooth(68, 100, b.coastH);
        // the rim round each tarn: raises the ground just outside its shore where the land dips below the water
        b.tarnLift = -1e9;
        if (withTarns) for (int k = 0; k < tarns.size(); k++) {
            double sd = tarnSd(k, u, v);
            if (sd >= 0 && sd < 7) b.tarnLift = Math.max(b.tarnLift, tarnRim(tarns.get(k)[3], sd));
        }
    }

    /** Terrain height for a column at an (optionally leaned) coast-distance e. */
    double heightAt(Base b, double e) {
        double t = smooth(0, 1, e);
        double h = b.coastH + (b.hs - b.coastH) * (1 - Math.pow(t, 1.5)) + b.rug * (1 - t * 0.6) + b.rough;
        // cliff bands low down; the upper mountain rises in gentler, broken slopes instead of sheer walls
        double tw = 0.72 * b.hw * (1 - 0.8 * smooth(165, 225, h));
        h = h + (terrace(h + b.warp, b.step, 0.8) - b.warp - h) * tw;
        h = h + (b.marshH - h) * b.marshW;
        // the meadow shelf: flat within, the mountain keeps rising gently behind it on the uphill side
        double sm = h > b.shelfH ? b.shelfM * (1 - smooth(0.55, 1.0, b.shelfD)) : b.shelfM;
        h = h + (b.shelfH - h) * sm;
        h = Math.max(h, b.tarnLift);
        double coast = smooth(1, 1 + b.coastK, e);
        return h + (b.seabed - h) * coast;
    }

    /** Real terrain surface (with the cliff lean the density applies), ignoring small jitter. */
    double surfaceAt(double u, double v, Base b) {
        baseAt(u, v, b, false);
        for (int y = 318; y > 40; y--) {
            double lean = LEAN * Math.max(0, Math.min(y, 175) - 66) * (0.35 + 0.65 * b.hw) / b.wBlocks;
            if (heightAt(b, b.e - lean) >= y) return y;
        }
        return 40;
    }

    // ------------------------------------------------------------------ tarns
    //
    // A tarn is not drawn as a circle: it is the water that would lie on the hillside below its level. From a hollow
    // or bench in the slope it fills every bit of ground lower than its surface that it can reach, so its shore runs
    // along the hill's own contour, curving round spurs and into side hollows, with any knoll that stands above it
    // left as an island. Where the ground drops away on the downhill side a low natural rim holds it in, broken by one
    // notch where it spills out into its stream.

    /** A tarn's shape: signed distance to its shore on a one-block grid round it (negative under water). */
    static final class TarnGrid { double u0, v0; int n; float[] sd; }
    final List<TarnGrid> tarnGrids = new ArrayList<>();
    private static final int TG = 36, RMAX = 26, DMAX = 7;

    /** Signed distance from (u, v) to tarn k's shore (negative in the water, 99 far off). */
    double tarnSd(int k, double u, double v) {
        if (k >= tarnGrids.size()) return 99;
        TarnGrid g = tarnGrids.get(k);
        double gx = u - g.u0, gy = v - g.v0;
        if (gx < 0 || gy < 0 || gx >= g.n - 1 || gy >= g.n - 1) return 99;
        int ix = (int) gx, iy = (int) gy, n = g.n;
        double fx = gx - ix, fy = gy - iy;
        float[] q = g.sd;
        return (q[ix + iy * n] * (1 - fx) + q[ix + 1 + iy * n] * fx) * (1 - fy) + (q[ix + (iy + 1) * n] * (1 - fx) + q[ix + 1 + (iy + 1) * n] * fx) * fy;
    }

    /** Height of the rim round a tarn at signed shore distance sd (outside the water): the bank that holds it in. */
    static double tarnRim(double level, double sd) { return level + 1.5 - Math.max(0, sd - 1) * 0.8; }

    /** The land's real surface here, lean included (and tarn rims, once there are tarns). */
    double groundAt(double u, double v, Base b) {
        baseAt(u, v, b, true);
        return surfaceLean(b);
    }

    /** Surface of a column with the cliff lean the density applies (the solid top at the column's own height). */
    double surfaceLean(Base b) {
        double coast = smooth(1, 1 + b.coastK, b.e);
        double h = heightAt(b, b.e);
        for (int k = 0; k < 3; k++) {
            double lean = LEAN * Math.max(0, Math.min(h, 175) - 66) * (0.35 + 0.65 * Math.max(b.hw, coast)) / b.wBlocks;
            h = heightAt(b, b.e - lean);
        }
        return h;
    }

    /** What filling the hillside round (cu, cv) to a level gives: the water's cells and how the shore is held. */
    private static final class Lake {
        double level; int area; boolean[] in; int outCell = -1; double extent, damShare, outLow;
    }

    private Lake flood(double cu, double cv, double[] ground, Base b, double level) {
        int n = 2 * TG + 1;
        boolean[] in = new boolean[n * n], seen = new boolean[n * n];
        int[] queue = new int[n * n];
        int head = 0, tail = 0, c0 = TG + TG * n;
        if (groundCell(cu, cv, ground, b, c0) >= level - 0.3) return null;
        queue[tail++] = c0; seen[c0] = true;
        int bank = 0, dam = 0, out = -1;
        double outLow = 1e9, ext = 0;
        while (head < tail) {
            int k = queue[head++];
            in[k] = true;
            int i = k % n, j = k / n;
            ext = Math.max(ext, Math.hypot(i - TG, j - TG));
            if (tail > 1400) return null;                                    // spreading over a whole plateau
            for (int d = 0; d < 4; d++) {
                int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
                int nk = ni + nj * n;
                if (seen[nk]) continue;
                seen[nk] = true;
                double g = groundCell(cu, cv, ground, b, nk);
                boolean far = Math.hypot(ni - TG, nj - TG) > RMAX;
                if (g >= level - 0.3) { bank++; continue; }                   // the hill: a natural shore
                if (far || g <= level - DMAX) {                                // the ground falls away: a rim holds it
                    dam++;
                    if (g < outLow) { outLow = g; out = nk; }
                    continue;
                }
                queue[tail++] = nk;
            }
        }
        if (out < 0) {                                                       // a closed hollow: spill at the lowest bank
            for (int k = 0; k < n * n; k++) {
                if (in[k] || !seen[k]) continue;
                double g = ground[k];
                if (g < outLow) { outLow = g; out = k; }
            }
        }
        Lake l = new Lake();
        l.level = level; l.area = tail; l.in = in; l.outCell = out; l.extent = ext + 1;
        l.damShare = dam / (double) Math.max(1, dam + bank); l.outLow = outLow;
        return l;
    }

    private double groundCell(double cu, double cv, double[] ground, Base b, int k) {
        if (Double.isNaN(ground[k])) {
            int n = 2 * TG + 1;
            baseAt(cu + (k % n) - TG, cv + (k / n) - TG, b, false);
            ground[k] = surfaceLean(b);
        }
        return ground[k];
    }

    private void pickTarns(Random r, Base b) {
        List<double[]> cand = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            double s = 0.42 + r.nextDouble() * 0.54, side = (r.nextBoolean() ? 1 : -1) * (0.08 + r.nextDouble() * 0.6);
            double[] p = pointAt(s, side);
            baseAt(p[0], p[1], b, false);
            if (b.shelfM > 0.2 || b.marshW > 0.2 || b.e > 0.8) continue;
            double h = surfaceAt(p[0], p[1], b);
            if (h < 110 || h > 230) continue;
            // a bench or hollow in the slope: ground rising behind it, not a summit or a ridge
            int higher = 0;
            double drop = 0;
            for (int k = 0; k < 12; k++) {
                double a = k * Math.PI / 6;
                baseAt(p[0] + Math.cos(a) * 12, p[1] + Math.sin(a) * 12, b, false);
                double hh = surfaceLean(b);
                if (hh > h + 1) higher++;
                baseAt(p[0] + Math.cos(a) * 34, p[1] + Math.sin(a) * 34, b, false);
                drop = Math.max(drop, h - surfaceLean(b));
            }
            if (higher < 3) continue;
            cand.add(new double[]{p[0], p[1], h, higher + 0.1 * Math.min(drop, 60)});
        }
        cand.sort((x, y) -> Double.compare(y[3], x[3]));
        if (DEBUG_STREAMS) System.out.println("tarn candidates " + cand.size());
        // fill each of the best spots to a few levels and keep the most natural lakes
        List<Object[]> lakes = new ArrayList<>();
        int n = 2 * TG + 1;
        for (int ci = 0; ci < cand.size() && ci < 40; ci++) {
            double[] c = cand.get(ci);
            double[] ground = new double[n * n];
            java.util.Arrays.fill(ground, Double.NaN);
            Lake best = null; double bestScore = 1e9;
            double g0 = groundCell(c[0], c[1], ground, b, TG + TG * n);
            for (int k = 1; k <= 4; k++) {
                Lake l = flood(c[0], c[1], ground, b, Math.floor(g0) + k);

                if (l == null || l.area < 140 || l.area > 1100 || l.damShare > 0.62) continue;
                double score = Math.abs(l.area - 480) / 480.0 + 1.5 * l.damShare;
                if (score < bestScore) { bestScore = score; best = l; }
            }
            if (best != null) lakes.add(new Object[]{c, best, bestScore, ground});
        }
        lakes.sort((x, y) -> Double.compare((double) x[2], (double) y[2]));
        for (Object[] o : lakes) {
            if (tarns.size() >= 2) break;
            double[] c = (double[]) o[0];
            Lake l = (Lake) o[1];
            boolean close = false;
            for (double[] t : tarns) if (Math.hypot(t[0] - c[0], t[1] - c[1]) < 110 + t[14]) close = true;
            if (close) continue;
            addTarn(c[0], c[1], l, (double[]) o[3], b);
        }
    }

    /** Keep a lake: its signed shore distance on the grid, and where its outlet notch is. */
    private void addTarn(double cu, double cv, Lake l, double[] ground, Base b) {
        int n = 2 * TG + 1;
        java.util.List<int[]> inEdge = new ArrayList<>(), outEdge = new ArrayList<>();
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int k = i + j * n;
            boolean me = l.in[k];
            for (int d = 0; d < 4; d++) {
                int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (ni < 0 || nj < 0 || ni >= n || nj >= n) continue;
                if (l.in[ni + nj * n] != me) { (me ? inEdge : outEdge).add(new int[]{i, j}); break; }
            }
        }
        float[] sd = new float[n * n];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            boolean me = l.in[i + j * n];
            double best = 1e9;
            for (int[] e : me ? outEdge : inEdge) best = Math.min(best, (e[0] - i) * (e[0] - i) + (e[1] - j) * (e[1] - j));
            double d = Math.sqrt(best) - 0.5;
            sd[i + j * n] = (float) (me ? -d : d);
        }
        TarnGrid g = new TarnGrid();
        g.u0 = cu - TG; g.v0 = cv - TG; g.n = n; g.sd = sd;
        // the outlet: the lowest point round the shore, and the way on downhill from there
        int oi = l.outCell % n, oj = l.outCell / n;
        double ou = cu + oi - TG, ov = cv + oj - TG;
        double du = ou - cu, dv = ov - cv, dl = Math.max(1e-6, Math.hypot(du, dv));
        du /= dl; dv /= dl;
        // the shore point nearest the outlet cell
        double su = ou, sv = ov, sb = 1e9;
        for (int[] e : inEdge) {
            double d = Math.hypot(e[0] - oi, e[1] - oj);
            if (d < sb) { sb = d; su = cu + e[0] - TG; sv = cv + e[1] - TG; }
        }
        double rEq = Math.sqrt(l.area / Math.PI);
        double kind = l.outLow < l.level - 15 ? 0 : 1;
        double[] t = new double[18];
        t[0] = cu; t[1] = cv; t[2] = rEq; t[3] = l.level; t[4] = kind; t[5] = du; t[6] = dv;
        t[13] = Double.NaN; t[14] = l.extent; t[15] = su; t[16] = sv; t[17] = -1;
        tarns.add(t);
        tarnGrids.add(g);
    }

    private boolean tooClose(double[] c, double d) {
        for (double[] t : tarns) if (Math.hypot(t[0] - c[0], t[1] - c[1]) < d) return true;
        return false;
    }

    // ------------------------------------------------------------------ streams
    //
    // Spring-to-sea streams. Each rises at a spring (a shallow, irregular seep of dotted pools) or runs out of a tarn,
    // and finds its way downhill: the lowest way ahead, drawn into valleys, wandering gently from side to side. It
    // widens as it goes and as tributaries join it, stops to form small ponds where the ground levels out, drops over
    // falls (each with a plunge pool) where the ground drops away, and ends in the sea, the great lake, another
    // stream - or at the edge of the plains, where it goes underground. The shapes live here (pure geometry: the
    // terrain density carves them, IslandSampler.isWater fills them); StreamDecor dresses them block by block.

    /** Where a stream reaches the plains and drops underground (u, v, water level). */
    final List<double[]> sinks = new ArrayList<>();
    /** Springs: u, v, radius, water level, outlet direction (du, dv), noise seed. */
    final List<double[]> springs = new ArrayList<>();
    /** Ponds on the way: u, v, radius, water level, noise seed. */
    final List<double[]> ponds = new ArrayList<>();
    /** Falls: lip u, v, top level, bottom level, direction (du, dv), half-width; pool centre u, v, pool radius. */
    final List<double[]> falls = new ArrayList<>();
    /** How many streams the sampler keeps track of per column. */
    public static final int MAX_STREAMS = 12;
    static boolean DEBUG_STREAMS = Boolean.getBoolean("ancientpeaks.debugStreams");

    private void buildStreams(Random r, Base b) {
        List<double[]> joinsAt = new ArrayList<>();              // per traced stream: {target stream, target point} or null
        // tarn outflows: out through the notch in the rim, lake-fed, so they start a little fuller
        for (int i = 0; i < tarns.size() && rivers.size() < MAX_STREAMS; i++) {
            double[] t = tarns.get(i);
            int before = rivers.size();
            traceStream(t[15] + t[5] * 1.5, t[16] + t[6] * 1.5, t[3] - 1, r, 4, joinsAt, 6, t[5], t[6], 8);
            if (rivers.size() > before) t[17] = before;
        }
        // springs: on hillsides and in hollows, wet ground away from the tarns, the shelf and each other
        List<double[]> cands = new ArrayList<>();
        for (int k = 0; k < 400; k++) {
            double s = 0.40 + r.nextDouble() * 0.56, side = (r.nextBoolean() ? 1 : -1) * (0.08 + r.nextDouble() * 0.62);
            double[] p = pointAt(s, side);
            baseAt(p[0], p[1], b, false);
            if (b.shelfM > 0.05 || b.marshW > 0.05 || b.e > 0.85) continue;
            double h = heightAt(b, b.e);
            if (h < 110 || h > 225) continue;
            // a hollow: lower than the ground around it (water gathers and seeps out there)
            double ring = 0, lowest = 1e9;
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4;
                baseAt(p[0] + Math.cos(ang) * 14, p[1] + Math.sin(ang) * 14, b, false);
                double hh = heightAt(b, b.e);
                ring += hh / 8; lowest = Math.min(lowest, hh);
            }
            double hollow = ring - h, wet = n[9].get(p[0] / 70 + 3, p[1] / 70) + 0.35 * hollow / 6;
            if (lowest > h - 1.5) continue;                       // nowhere for it to run off
            cands.add(new double[]{p[0], p[1], h, wet});
        }
        cands.sort((x, y) -> Double.compare(y[3], x[3]));         // wettest first
        for (double[] c : cands) {
            if (rivers.size() >= MAX_STREAMS || springs.size() >= 7) break;
            boolean ok = true;
            for (double[] t : tarns) if (Math.hypot(t[0] - c[0], t[1] - c[1]) < t[14] + 45) { ok = false; break; }
            for (double[] q : springs) if (Math.hypot(q[0] - c[0], q[1] - c[1]) < 110) { ok = false; break; }
            double[] o = new double[5];
            for (Path p : rivers) { if (!p.near(c[0], c[1], 30)) continue; pathDist(p, c[0], c[1], o); if (o[0] < 30) { ok = false; break; } }
            if (!ok) continue;
            double rad = 2.5 + r.nextDouble() * 2.2;
            // outlet: the lowest way off the spring's rim
            double dir = 0, low = 1e9;
            for (int a = 0; a < 16; a++) {
                double ang = a * Math.PI / 8;
                baseAt(c[0] + Math.cos(ang) * (rad + 4), c[1] + Math.sin(ang) * (rad + 4), b, false);
                double hh = heightAt(b, b.e);
                if (hh < low) { low = hh; dir = ang; }
            }
            double level = Math.floor(Math.min(c[2], low + 2)) - 1;
            double ou = c[0] + Math.cos(dir) * (rad + 1), ov = c[1] + Math.sin(dir) * (rad + 1);
            int before = rivers.size();
            traceStream(ou, ov, level, r, 1, joinsAt, 16, Math.cos(dir), Math.sin(dir), 0);
            if (rivers.size() > before) springs.add(new double[]{c[0], c[1], rad, level, Math.cos(dir), Math.sin(dir), r.nextDouble() * 1000});
        }
        // flow: each stream gathers along its length and takes in its tributaries' flow below where they join
        for (int k = rivers.size() - 1; k >= 0; k--) {
            double[] j = joinsAt.get(k);
            if (j == null) continue;
            Path into = rivers.get((int) j[0]);
            double add = rivers.get(k).flow[rivers.get(k).flow.length - 1];
            for (int i = (int) j[1]; i < into.flow.length; i++) into.flow[i] += add * Math.min(1, (i - j[1] + 1) / 4.0);   // eases in
        }
        for (Path p : rivers) {
            if (p.flow == null) continue;
            p.wid = new double[p.flow.length]; p.dep = new double[p.flow.length];
            for (int i = 0; i < p.flow.length; i++) {
                double f = Math.sqrt(p.flow[i]);
                // where it runs steeply downhill it narrows to a rushing cascade; on the level it spreads out
                double grade = (fineAt(p, p.l[i] - 4) - fineAt(p, p.l[i] + 4)) / 8;
                p.wid[i] = Math.max(0.45, Math.min(4.6, 0.2 + 0.55 * f) / (1 + 1.1 * Math.max(0, grade)));
                p.dep[i] = Math.min(3.0, 0.7 + 0.32 * f) / (1 + 0.5 * Math.max(0, grade));
            }
            findPonds(p);
            findFalls(p);
        }
    }

    /** A stream's water level a given distance along it. */
    static double fineAt(Path p, double along) {
        int k = (int) Math.round(along);
        return p.fine[Math.max(0, Math.min(p.fine.length - 1, k))];
    }

    /**
     * Follows the land downhill from (u0, v0): the lowest way ahead, drawn into valleys, wandering gently, until it
     * meets the sea or a lake, joins a stream already traced, or goes over the island's edge.
     *
     * Its water level is then worked out a block at a time all the way down: always two blocks under the lowest
     * ground either side of it, never rising. So on the level it lies in a shallow bed between its banks; where the
     * land steepens it steps down with it a block at a time (a rushing cascade, each little pool spilling into the
     * next); where the land drops off a ledge it pours over as a fall; and past the island's edge it keeps its level
     * and runs out over the cliff.
     */
    private void traceStream(double u0, double v0, double level0, Random r, double flow0, List<double[]> joinsAt, int minPts,
                             double du0, double dv0, double holdFor) {
        List<double[]> pts = new ArrayList<>();
        Base b = new Base();
        double u = u0, v = v0;
        double dir = Math.atan2(dv0, du0);
        double wander = r.nextDouble() * 100;
        String why = "long";
        double[] sd = new double[5];
        double[] join = null;
        for (int step = 0; step < 700; step++) {
            pts.add(new double[]{u, v});
            baseAt(u, v, b, false);
            double h = heightAt(b, b.e);
            if (h < 63.5) { why = "sea"; break; }
            if (b.e > 0.97) {
                // the coast, high up: straight on to the real edge of the cliff (lean and all), then out over it
                why = "cliff";
                double last = surfaceLean(b);
                boolean over = false;
                for (int k = 0; k < 30 && !over; k++) {
                    u += Math.cos(dir) * 2; v += Math.sin(dir) * 2;
                    baseAt(u, v, b, false);
                    double g = surfaceLean(b);
                    if (g < last - 5 || g < 64) over = true;
                    else { pts.add(new double[]{u, v}); last = Math.min(last, g); }
                }
                for (int k = 0; k < 6; k++) {
                    pts.add(new double[]{u, v, 1});
                    u += Math.cos(dir) * 4; v += Math.sin(dir) * 4;
                }
                break;
            }
            if (step > 2) { join = joins(u, v); if (join != null) { why = "join"; break; } }
            double best = Double.MAX_VALUE, bestDir = Double.NaN;
            double[] here = new double[5];
            pathDist(strait, u, v, here);
            for (int pass = 0; pass < 2 && Double.isNaN(bestDir); pass++)        // the wide turns only when boxed in
            for (int j = -(pass == 0 ? 6 : 11); j <= (pass == 0 ? 6 : 11); j++) {
                double a = dir + j * 0.3;
                double pu = u + Math.cos(a) * 6, pv = v + Math.sin(a) * 6;
                baseAt(pu, pv, b, false);
                if (b.marshW > 0.2 && !PLAINS_OPEN) continue;                   // the town's plains: round them, to the sea
                double hc = heightAt(b, b.e);
                if (b.shelfM > 0.02 || (b.marshW > 0.05 && !PLAINS_OPEN)) hc += 250 * b.shelfM + (PLAINS_OPEN ? 0 : 40 * b.marshW);
                for (int k = 0; k < tarns.size(); k++) if (tarnSd(k, pu, pv) < 9) hc += 250;   // round the tarns, not through them
                // valleys draw it in: ground rising on both sides of the way ahead
                double nu = -Math.sin(a), nv = Math.cos(a);
                baseAt(pu + nu * 10, pv + nv * 10, b, false); double hl = heightAt(b, b.e);
                baseAt(pu - nu * 10, pv - nv * 10, b, false); double hr = heightAt(b, b.e);
                double valley = Math.max(0, Math.min(12, (hl + hr) / 2 - hc));
                double score = hc - 0.3 * valley + 1.5 * Math.abs(j);
                pathDist(strait, pu, pv, sd);
                if (sd[0] < 45 && sd[1] > IslandSampler.TUNNEL_END - 0.08 && sd[0] <= here[0]) continue;
                if (score < best) { best = score; bestDir = a; }
            }
            if (Double.isNaN(bestDir)) { why = "strait"; break; }
            double swing = 0.35 * Math.sin(step * 0.21 + wander) + 0.22 * n[10].get(u / 45 + wander, v / 45);
            // the first few steps keep to the way it set out (out of a tarn's notch, off a spring's lip)
            double pull = step < 3 ? 0.15 : 0.55;
            dir = dir + (bestDir - dir) * pull + swing * (step < 3 ? 0 : 0.5);
            u += Math.cos(dir) * 5; v += Math.sin(dir) * 5;
        }
        int nPts = pts.size();
        if (DEBUG_STREAMS) System.out.println("[ancientpeaks] stream " + rivers.size() + " from " + (int) u0 + "," + (int) v0 + " len " + nPts * 5 + " ends: " + why);
        if (nPts < minPts) return;
        double[] pu = new double[nPts], pv = new double[nPts], flow = new double[nPts];
        boolean[] tail = new boolean[nPts];
        double len = 0;
        double[] along = new double[nPts];
        for (int i = 0; i < nPts; i++) {
            int a = Math.max(0, i - 2), c = Math.min(nPts - 1, i + 2);
            double su = 0, sv = 0;
            for (int j = a; j <= c; j++) { su += pts.get(j)[0]; sv += pts.get(j)[1]; }
            pu[i] = su / (c - a + 1); pv[i] = sv / (c - a + 1);
            if (i == 0) { pu[0] = pts.get(0)[0]; pv[0] = pts.get(0)[1]; }      // it starts exactly where it rises
            tail[i] = pts.get(i).length >= 3;
            if (i > 0) len += Math.hypot(pu[i] - pu[i - 1], pv[i] - pv[i - 1]);
            along[i] = len;
            flow[i] = flow0 + len / 45;
        }
        // the water level, a block at a time
        int nf = (int) Math.ceil(len) + 1;
        double[] fine = new double[nf];
        double cur = level0;
        for (int k = 0, i = 0; k < nf; k++) {
            while (i < nPts - 2 && along[i + 1] < k) i++;
            double segL = Math.max(1e-6, along[i + 1] - along[i]);
            double t = Math.max(0, Math.min(1, (k - along[i]) / segL));
            double qu = pu[i] + (pu[i + 1] - pu[i]) * t, qv = pv[i] + (pv[i + 1] - pv[i]) * t;
            double tu = (pu[i + 1] - pu[i]) / segL, tv = (pv[i + 1] - pv[i]) / segL;
            double w = Math.min(4.6, 0.2 + 0.55 * Math.sqrt(flow0 + k / 45.0)) + 1.5;
            double g0 = groundAt(qu, qv, b), g = g0;
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                double gs = groundAt(qu - tv * w * sgn, qv + tu * w * sgn, b);
                if (gs > g0 - 8 || b.e < 0.97) g = Math.min(g, gs);          // the island's edge beside it is a cliff, not its bank
            }
            if (!tail[i + 1] && k >= holdFor) cur = Math.min(cur, Math.floor(g - 2));
            fine[k] = Math.max(cur, 62.5);
        }
        double[] lev = new double[nPts];
        for (int i = 0; i < nPts; i++) lev[i] = fine[Math.min(nf - 1, (int) Math.round(along[i]))];
        if (join != null) {
            // it meets the other stream at or above that stream's water: where it would arrive lower, the other
            // stream's water is let down to it from there on (a deeper bed), never this one raised over its banks
            Path into = rivers.get((int) join[0]);
            double end = fine[nf - 1];
            int from = (int) Math.round(into.l[(int) join[1]]);
            for (int k = Math.max(0, from - 3); k < into.fine.length; k++) into.fine[k] = Math.min(into.fine[k], end);
            for (int i = 0; i < into.level.length; i++) into.level[i] = fineAt(into, into.l[i]);
        }
        Path p = new Path(new double[][]{pu, pv}, lev, 1, 1, 1, 1, 0.9, false, new double[0]);
        p.fine = fine;
        p.flow = flow;
        joinsAt.add(join);
        rivers.add(p);
    }

    /** {stream index, point index} of a stream already traced within a few blocks, or null. */
    private double[] joins(double u, double v) {
        for (int k = 0; k < rivers.size(); k++) {
            Path p = rivers.get(k);
            if (!p.near(u, v, 6)) continue;
            int bi = -1; double bd = 36;
            for (int i = 0; i < p.u.length; i++) {
                double d = (p.u[i] - u) * (p.u[i] - u) + (p.v[i] - v) * (p.v[i] - v);
                if (d < bd) { bd = d; bi = i; }
            }
            if (bi >= 0) return new double[]{k, bi};
        }
        return null;
    }

    /** Where the stream crosses a level stretch of ground some way from its source, it spreads into a small pond. */
    private void findPonds(Path p) {
        Base b = new Base();
        int n = p.u.length;
        double lastAt = -1e9;
        int made = 0;
        for (int i = 6; i < n - 4 && made < 2; i++) {
            if (p.l[i] < 55 || p.l[i] - lastAt < 70) continue;
            baseAt(p.u[i - 4], p.v[i - 4], b, false); double h0 = heightAt(b, b.e);
            baseAt(p.u[i + 4], p.v[i + 4], b, false); double h1 = heightAt(b, b.e);
            baseAt(p.u[i], p.v[i], b, false); double hc = heightAt(b, b.e);
            if (b.shelfM > 0.05 || b.marshW > 0.05 || b.e > 0.9) continue;
            if (Math.abs(h0 - h1) > 2.0 || hc - p.level[i] > 3) continue;             // not level, or deep in a cut
            if (fineAt(p, p.l[i] - 12) != p.level[i] || fineAt(p, p.l[i] + 12) < p.level[i] - 1) continue;
            boolean clear = true;
            for (double[] t : tarns) if (Math.hypot(t[0] - p.u[i], t[1] - p.v[i]) < t[14] + 14) clear = false;
            if (!clear) continue;
            double rad = 3.5 + p.wid[i] * 1.6 + (p.l[i] % 3);
            // the water downstream of the pond must not stand higher than it
            for (int k = (int) p.l[i]; k < p.fine.length; k++) p.fine[k] = Math.min(p.fine[k], p.level[i]);
            for (int j = i; j < n; j++) p.level[j] = Math.min(p.level[j], p.level[i]);
            ponds.add(new double[]{p.u[i], p.v[i], rad, p.level[i], p.l[i] * 7.31});
            lastAt = p.l[i];
            made++;
        }
    }

    /**
     * Wherever the water drops four blocks or more over a short way (a ledge, not a long steep run, which is a
     * cascade), a fall over the lip into a plunge pool.
     */
    private void findFalls(Path p) {
        double[] f = p.fine;
        int nf = f.length;
        for (int k = 1; k + 3 < nf; k++) {
            if (f[k - 1] - f[k + 2] < 5) continue;
            int a = k - 1, e = k + 2;
            while (e + 1 < nf && e < k + 6 && f[e] - f[e + 1] >= 2) e++;       // on down the face
            double top = f[a], bottom = f[e];
            double[] at = pointAlong(p, a + 0.5), at2 = pointAlong(p, a + 2.5);
            double du = at2[0] - at[0], dv = at2[1] - at[1], dl = Math.max(1e-6, Math.hypot(du, dv));
            du /= dl; dv /= dl;
            int ci = 0;
            while (ci < p.l.length - 2 && p.l[ci + 1] < a) ci++;
            double w = p.wid[ci];
            double pr = w + 1.6 + Math.min(2.5, (top - bottom) * 0.15);
            // the water drops straight down off the lip, so the pool lies at the foot of the fall, its near edge
            // tucked in under the lip (worn back into the rock), and it stands no higher than the stream just past
            // it, so it is held in all round
            double[] pool = pointAlong(p, a + 0.5 + pr * 0.7);
            for (int q = a + 1; q <= Math.min(nf - 1, (int) Math.ceil(a + 0.5 + pr * 1.8)); q++) bottom = Math.min(bottom, f[q]);
            bottom = Math.max(bottom, f[k + 2] - 3);                            // a pool, not a well, where the slope runs on down
            // the water in the pool lies level right across it; past the far lip the stream runs on lower
            int poolEnd = Math.min(nf - 1, (int) Math.ceil(a + 0.5 + pr * 1.7));
            for (int q = a + 1; q < nf; q++) f[q] = q <= poolEnd ? bottom : Math.min(f[q], bottom);
            falls.add(new double[]{at[0], at[1], top, bottom, du, dv, w, pool[0], pool[1], pr});
            k = Math.max(e, poolEnd) + 2;
        }
    }

    /** The point a given distance along a path. */
    static double[] pointAlong(Path p, double s) {
        int i = 0;
        while (i < p.u.length - 2 && p.l[i + 1] < s) i++;
        double segL = Math.max(1e-6, p.l[i + 1] - p.l[i]);
        double t = Math.max(0, Math.min(1, (s - p.l[i]) / segL));
        return new double[]{p.u[i] + (p.u[i + 1] - p.u[i]) * t, p.v[i] + (p.v[i + 1] - p.v[i]) * t};
    }

    // world-space views for the decoration pass
    public List<double[]> sinksWorld() { return world(sinks, 0); }
    /** {x, z, radius, level, outlet dx, outlet dz, seed} */
    public List<double[]> springsWorld() { return world(springs, 4); }
    /** {x, z, radius, level, seed} */
    public List<double[]> pondsWorld() { return world(ponds, -1); }
    /** {lip x, lip z, top, bottom, dx, dz, half-width, pool x, pool z, pool radius} */
    public List<double[]> fallsWorld() {
        List<double[]> out = new ArrayList<>();
        for (double[] f : falls) {
            int[] a = toWorld(f[0], f[1]), c = toWorld(f[7], f[8]);
            out.add(new double[]{a[0], a[1], f[2], f[3], f[4] * cos - f[5] * sin, f[4] * sin + f[5] * cos, f[6], c[0], c[1], f[9]});
        }
        return out;
    }
    private List<double[]> world(List<double[]> in, int dirAt) {
        List<double[]> out = new ArrayList<>();
        for (double[] s : in) {
            double[] w = s.clone();
            w[0] = cx + s[0] * cos - s[1] * sin; w[1] = cz + s[0] * sin + s[1] * cos;
            if (dirAt > 0) { w[dirAt] = s[dirAt] * cos - s[dirAt + 1] * sin; w[dirAt + 1] = s[dirAt] * sin + s[dirAt + 1] * cos; }
            out.add(w);
        }
        return out;
    }

    /** Irregular outline factor for a spring or pond (seeded), at angle a. */
    static double blobShape(double seed, double a) {
        return 1 + 0.22 * Math.sin(2 * a + seed) + 0.14 * Math.sin(3 * a + seed * 1.7) + 0.08 * Math.sin(5 * a + seed * 2.3);
    }

    /** Catmull-Rom densification so paths meander smoothly. */
    static double[][] spline(double[] u, double[] v, int sub) {
        int n = u.length, total = (n - 1) * sub + 1;
        double[] ou = new double[total], ov = new double[total];
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(i - 1, 0), i2 = i + 1, i3 = Math.min(i + 2, n - 1);
            for (int s = 0; s < sub; s++) {
                double t = s / (double) sub, t2 = t * t, t3 = t2 * t;
                ou[i * sub + s] = 0.5 * (2 * u[i] + (-u[i0] + u[i2]) * t + (2 * u[i0] - 5 * u[i] + 4 * u[i2] - u[i3]) * t2 + (-u[i0] + 3 * u[i] - 3 * u[i2] + u[i3]) * t3);
                ov[i * sub + s] = 0.5 * (2 * v[i] + (-v[i0] + v[i2]) * t + (2 * v[i0] - 5 * v[i] + 4 * v[i2] - v[i3]) * t2 + (-v[i0] + 3 * v[i] - 3 * v[i2] + v[i3]) * t3);
            }
        }
        ou[total - 1] = u[n - 1]; ov[total - 1] = v[n - 1];
        return new double[][]{ou, ov};
    }

    /** Distance to a path: fills distance, along-path fraction and interpolated water level. */
    static void pathDist(Path p, double u, double v, double[] out) {
        if (p.u.length < 2) {
            out[0] = Math.hypot(u - p.u[0], v - p.v[0]); out[1] = 0; out[2] = p.level[0];
            if (out.length >= 5) { out[3] = p.wid != null ? p.wid[0] : p.width0; out[4] = p.dep != null ? p.dep[0] : p.depth0; }
            return;
        }
        double best = Double.MAX_VALUE; int bi = 0; double bt = 0;
        for (int i = 0; i < p.u.length - 1; i++) {
            double ax = p.u[i], ay = p.v[i], dx = p.u[i + 1] - ax, dy = p.v[i + 1] - ay;
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((u - ax) * dx + (v - ay) * dy) / l2));
            double qx = ax + t * dx - u, qy = ay + t * dy - v;
            double d = qx * qx + qy * qy;
            if (d < best) { best = d; bi = i; bt = t; }
        }
        out[0] = Math.sqrt(best);
        out[1] = (p.l[bi] + bt * (p.l[bi + 1] - p.l[bi])) / p.len;
        if (p.fine != null) {
            // a stream's water level, block by block along it
            out[2] = fineAt(p, p.l[bi] + bt * (p.l[bi + 1] - p.l[bi]));
            if (out.length >= 5 && p.wid == null) { out[3] = p.width0; out[4] = p.depth0; }
            else if (out.length >= 5) { out[3] = p.wid[bi] + bt * (p.wid[bi + 1] - p.wid[bi]); out[4] = p.dep[bi] + bt * (p.dep[bi + 1] - p.dep[bi]); }
        } else if (p.wid != null) {
            out[2] = bt < 0.5 ? p.level[bi] : p.level[bi + 1];
            if (out.length >= 5) { out[3] = p.wid[bi] + bt * (p.wid[bi + 1] - p.wid[bi]); out[4] = p.dep[bi] + bt * (p.dep[bi + 1] - p.dep[bi]); }
        } else {
            out[2] = p.level[bi] + bt * (p.level[bi + 1] - p.level[bi]);
            if (out.length >= 5) { out[3] = p.width0 + (p.width1 - p.width0) * out[1]; out[4] = p.depth0 + (p.depth1 - p.depth0) * out[1]; }
        }
    }

    /** Tarn in world space: centre x, z and its outflow direction snapped to a block axis (dx, dz). */
    private double[][] tarnW;
    double[] tarnWorld(int i) {
        if (tarnW == null) {
            double[][] a = new double[tarns.size()][];
            for (int k = 0; k < a.length; k++) {
                double[] t = tarns.get(k);
                double wx = cx + t[0] * cos - t[1] * sin, wz = cz + t[0] * sin + t[1] * cos;
                double dx = t[5] * cos - t[6] * sin, dz = t[5] * sin + t[6] * cos;
                double sx = 0, sz = 0;
                if (Math.abs(dx) > Math.abs(dz)) sx = Math.signum(dx); else sz = Math.signum(dz);
                a[k] = new double[]{wx, wz, sx, sz};
            }
            tarnW = a;
        }
        return tarnW[i];
    }

    /** Each tarn in world space: {x, z, water level, radius}. */
    public java.util.List<double[]> tarnsWorld() {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (int k = 0; k < tarns.size(); k++) { double[] w = tarnWorld(k), t = tarns.get(k); out.add(new double[]{w[0], w[1], t[3], t[2]}); }
        return out;
    }

    public String describeTarns() {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < tarns.size(); k++) {
            double[] w = tarnWorld(k), t = tarns.get(k);
            sb.append(String.format("%d,%d,%d r%.0f%s; ", (int) w[0], (int) t[3], (int) w[1], t[2], Double.isNaN(t[13]) ? "" : " islet"));
        }
        return sb.toString();
    }

    public int[] toWorld(double u, double v) { return new int[]{(int) Math.round(cx + u * cos - v * sin), (int) Math.round(cz + u * sin + v * cos)}; }
    /** The great arch's two feet (world x, z): the low end in the lake, the high end on the hillside. */
    public int[] archLow() { return toWorld(aLoU, aLoV); }
    public int[] archHigh() { return toWorld(aHiU, aHiV); }
    public int[] greatArch() { return toWorld((aLoU + aHiU) / 2, (aLoV + aHiV) / 2); }
    public int[] landingCove() { return toWorld(landU, landV); }
    public int[] meadow() { return toWorld(shU, shV); }

    /** Where island structures stand (world x, z), by anchor name; null for an unknown anchor. */
    public int[] anchor(String name) {
        double[] p;
        switch (name) {
            case "plains_village": p = pointAt(marshEnd * 0.40, -archSide * 0.12); break;
            case "plains_castle": p = pointAt(marshEnd * 0.72, -archSide * 0.50); break;
            case "plains_center": p = pointAt(marshEnd * 0.5, 0); break;
            case "meadow_plateau": p = new double[]{shU, shV}; break;
            case "great_arch": p = new double[]{(aLoU + aHiU) / 2, (aLoV + aHiV) / 2}; break;
            case "main_peak": p = pointAt(peakS, 0); break;
            default: return null;
        }
        return toWorld(p[0], p[1]);
    }
    public double localU(int x, int z) { double dx = x - cx, dz = z - cz; return dx * cos + dz * sin; }
    public double localV(int x, int z) { double dx = x - cx, dz = z - cz; return -dx * sin + dz * cos; }
}
