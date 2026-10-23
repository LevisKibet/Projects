package com.ronald.chroma.content;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a line and the colours along it, shared by the server (where the light goes) and the client (what gets
 * drawn). A line is a run of points stuck to block faces; each point has a handle, and every stretch between two points
 * is a cubic Bezier curve pulled by the handles at either end (no handle: a straight run, a sharp corner).
 */
public final class Curve {
    private Curve() {}

    /** One point of a line: where it is, the face it's stuck to, and its curve handle. */
    public record Pt(Vec3 pos, Direction face, Vec3 handle) {
        public Pt withHandle(Vec3 h) { return new Pt(pos, face, h); }
        public Vec3 normal() { return Vec3.atLowerCornerOf(face.getNormal()); }
    }

    /** A spot along the line: position, direction of travel, the way out from the surface, distance along the line. */
    public record Sample(Vec3 pos, Vec3 tangent, Vec3 normal, double s) {}

    /** The whole line as closely spaced samples (about `step` blocks apart). */
    public static List<Sample> sample(List<Pt> pts, double step) {
        List<Sample> out = new ArrayList<>();
        if (pts.size() < 2) return out;
        double s = 0;
        Vec3 last = null;
        for (int i = 0; i + 1 < pts.size(); i++) {
            Pt a = pts.get(i), b = pts.get(i + 1);
            Vec3 p0 = a.pos(), p1 = a.pos().add(a.handle()), p2 = b.pos().subtract(b.handle()), p3 = b.pos();
            double approx = p0.distanceTo(p1) + p1.distanceTo(p2) + p2.distanceTo(p3);
            int n = Math.max(2, (int) Math.ceil(approx / step));
            for (int k = (i == 0 ? 0 : 1); k <= n; k++) {
                double t = k / (double) n;
                Vec3 p = bezier(p0, p1, p2, p3, t);
                Vec3 d = derivative(p0, p1, p2, p3, t);
                if (d.lengthSqr() < 1e-8) d = p3.subtract(p0);
                Vec3 nrm = a.normal().scale(1 - t).add(b.normal().scale(t));
                if (nrm.lengthSqr() < 1e-6) nrm = b.normal();
                if (last != null) s += p.distanceTo(last);
                out.add(new Sample(p, d.normalize(), nrm.normalize(), s));
                last = p;
            }
        }
        return out;
    }

    static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double t) {
        double u = 1 - t;
        return a.scale(u * u * u).add(b.scale(3 * u * u * t)).add(c.scale(3 * u * t * t)).add(d.scale(t * t * t));
    }

    static Vec3 derivative(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double t) {
        double u = 1 - t;
        return b.subtract(a).scale(3 * u * u).add(c.subtract(b).scale(6 * u * t)).add(d.subtract(c).scale(3 * t * t));
    }

    /** Spin a vector round an axis (radians). */
    public static Vec3 rotate(Vec3 v, Vec3 axis, double ang) {
        Vec3 k = axis.normalize();
        double c = Math.cos(ang), sn = Math.sin(ang);
        return v.scale(c).add(k.cross(v).scale(sn)).add(k.scale(k.dot(v) * (1 - c)));
    }

    // ------------------------------------------------------------------ colours

    public static final int STRIP = 0, BULBS = 1;
    /** Strip patterns. */
    public static final int SOLID = 0, FLOW = 1, BREATHE = 2, CHASE = 3;
    /** String light patterns. */
    public static final int STEADY = 0, TWINKLE = 1, ALTERNATE = 2, BULB_CHASE = 3;
    public static final String[] STRIP_MODES = {"solid", "flowing", "breathing", "chasing"};
    public static final String[] BULB_MODES = {"steady", "twinkling", "alternating", "chasing"};
    public static final float[] SPEEDS = {0.12f, 0.35f, 0.9f};
    public static final String[] SPEED_NAMES = {"slow", "medium", "fast"};

    /** The classic string light colours: red, green, blue, gold, orange. */
    public static final int[] CLASSIC = {0xFF2A1E, 0x2EE04A, 0x2F6BFF, 0xFFC93A, 0xFF7A1C};

    /** Bright versions of the dye colours, for light (the dye's own colours are too dull to glow). */
    public static final int[] VIVID = {
            0xFFF4E6, 0xFF8A20, 0xFF40E0, 0x60C8FF, 0xFFE040, 0x80FF40, 0xFF90C0, 0xA0A0A8,
            0xD0D0D8, 0x20E8E0, 0xA040FF, 0x3060FF, 0xC88040, 0x30D050, 0xFF2020, 0x30204A};

    /** Colour (0xRRGGBB) at distance s along a strip, at time t seconds. */
    public static int stripColour(int[] cols, int mode, float speed, double s, double t) {
        if (cols.length == 1) return cols[0];
        double u = s / 1.5;                                   // a new colour every block and a half
        if (mode == FLOW) u -= t * speed * 4;
        return gradient(cols, u);
    }

    /** How bright a strip is at s (0..1), for breathing and chasing. */
    public static float stripBrightness(int mode, float speed, double s, double t) {
        if (mode == BREATHE) return 0.55f + 0.45f * (float) Math.sin(t * speed * Math.PI * 2 * 0.8);
        if (mode == CHASE) {
            double f = (s / 6.0 - t * speed * 2.5) % 1.0;
            if (f < 0) f += 1;
            double pulse = Math.max(0, 1 - Math.abs(f - 0.5) * 5);
            return (float) (0.3 + 0.7 * pulse);
        }
        return 1f;
    }

    /** Colour of bulb number i. */
    public static int bulbColour(int[] cols, int i) { return cols[Math.floorMod(i, cols.length)]; }

    /** How bright bulb number i is (0..1). */
    public static float bulbBrightness(int mode, float speed, int i, double t) {
        switch (mode) {
            case TWINKLE: {
                double phase = hash(i) * 10 + t * speed * 3;
                double v = 0.5 + 0.5 * Math.sin(phase) * Math.sin(phase * 1.7 + hash(i + 7) * 3);
                return (float) (0.25 + 0.75 * v);
            }
            case ALTERNATE: {
                boolean even = ((int) Math.floor(t * speed * 2.2)) % 2 == 0;
                return (i % 2 == 0) == even ? 1f : 0.18f;
            }
            case BULB_CHASE: {
                int lit = Math.floorMod((int) Math.floor(t * speed * 8), 3);
                return Math.floorMod(i, 3) == lit ? 1f : 0.25f;
            }
            default: return 1f;
        }
    }

    static double hash(int i) {
        long x = i * 0x9E3779B97F4A7C15L;
        x ^= x >>> 31; x *= 0xBF58476D1CE4E5B9L; x ^= x >>> 29;
        return (x & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /** Colours spread evenly along u (u = 1 per colour), blended, wrapping round. */
    static int gradient(int[] cols, double u) {
        int n = cols.length;
        double f = u - Math.floor(u / n) * n;
        int a = (int) Math.floor(f) % n, b = (a + 1) % n;
        double k = f - Math.floor(f);
        k = k * k * (3 - 2 * k);
        return mix(cols[a], cols[b], k);
    }

    public static int mix(int a, int b, double k) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = (int) Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return r << 16 | g << 8 | bl;
    }

    /** How far each glow shade is washed toward white. */
    public static final double[] SHADES = {0, 0.35, 0.65};
    public static final String[] SHADE_SUFFIX = {"", "_soft", "_pale"};

    /** The colour of glow block (dye, shade). */
    public static int glowColour(int dye, int shade) { return mix(VIVID[dye], 0xFFFFFF, SHADES[shade]); }

    /** The glow block (dye * SHADES + shade) closest to this colour. */
    public static int nearestGlow(int rgb) {
        int best = 0; double bd = Double.MAX_VALUE;
        for (int d = 0; d < VIVID.length; d++) for (int sh = 0; sh < SHADES.length; sh++) {
            int c = glowColour(d, sh);
            double dr = ((rgb >> 16) & 255) - ((c >> 16) & 255), dg = ((rgb >> 8) & 255) - ((c >> 8) & 255), db = (rgb & 255) - (c & 255);
            double dist = dr * dr * 0.9 + dg * dg * 1.2 + db * db * 0.8;
            if (dist < bd) { bd = dist; best = d * SHADES.length + sh; }
        }
        return best;
    }

    /**
     * The light a line of several colours casts: its colours blended (two give a soft mix, like red and blue making a
     * faint purple) and washed toward white the more colours there are, as mixed coloured light does.
     */
    public static int castColour(int[] cols) {
        if (cols.length == 1) return cols[0];
        double r = 0, g = 0, b = 0;
        for (int c : cols) {                                       // blend as light: average the squares
            double cr = ((c >> 16) & 255) / 255.0, cg = ((c >> 8) & 255) / 255.0, cb = (c & 255) / 255.0;
            r += cr * cr; g += cg * cg; b += cb * cb;
        }
        r = Math.sqrt(r / cols.length); g = Math.sqrt(g / cols.length); b = Math.sqrt(b / cols.length);
        double mx = Math.max(1e-3, Math.max(r, Math.max(g, b)));
        int blend = (int) Math.round(r / mx * 255) << 16 | (int) Math.round(g / mx * 255) << 8 | (int) Math.round(b / mx * 255);
        double white = cols.length == 2 ? 0.3 : Math.min(0.85, 0.3 + 0.18 * (cols.length - 2));
        return mix(blend, 0xFFFFFF, white);
    }

    /** The dye whose bright colour is closest to this one (for the light it gives off). */
    public static int nearestDye(int rgb) {
        int best = 0; double bd = Double.MAX_VALUE;
        for (int i = 0; i < VIVID.length; i++) {
            double dr = ((rgb >> 16) & 255) - ((VIVID[i] >> 16) & 255), dg = ((rgb >> 8) & 255) - ((VIVID[i] >> 8) & 255), db = (rgb & 255) - (VIVID[i] & 255);
            double d = dr * dr * 0.9 + dg * dg * 1.2 + db * db * 0.8;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }
}
