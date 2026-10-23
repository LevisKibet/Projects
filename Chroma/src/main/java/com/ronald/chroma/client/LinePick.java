package com.ronald.chroma.client;

import com.ronald.chroma.content.Curve;
import com.ronald.chroma.content.LineEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Works out which part of which line the player is looking at: a point, the tip of a curve handle, or a stretch. */
public final class LinePick {
    private LinePick() {}

    public static final int POINT = 0, HANDLE = 1, BODY = 2;
    public record Hit(LineEntity line, int type, int index, double dist, Vec3 at) {}

    private record Cached(int revision, List<Curve.Sample> samples) {}
    private static final Map<LineEntity, Cached> CACHE = new WeakHashMap<>();

    /** The line's samples, rebuilt only when it changes. */
    public static List<Curve.Sample> samples(LineEntity line) {
        Cached c = CACHE.get(line);
        if (c == null || c.revision != line.revision) {
            c = new Cached(line.revision, Curve.sample(line.points(), 0.125));
            CACHE.put(line, c);
        }
        return c.samples;
    }

    public static Hit pick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Vec3 eye = mc.player.getEyePosition(1f), dir = mc.player.getViewVector(1f);
        double reach = 6;
        HitResult block = mc.hitResult;
        if (block != null && block.getType() == HitResult.Type.BLOCK) reach = Math.min(reach, block.getLocation().distanceTo(eye) + 0.2);
        Hit best = null;
        for (LineEntity line : LineEntity.CLIENT_LINES) {
            if (line.isRemoved() || line.getLevel() != mc.level) continue;
            List<Curve.Pt> pts = line.points();
            if (pts.isEmpty() || pts.get(0).pos().distanceToSqr(eye) > 40 * 40) continue;
            for (int i = 0; i < pts.size(); i++) {
                Curve.Pt p = pts.get(i);
                best = better(best, line, POINT, i, sphere(eye, dir, p.pos(), 0.2), eye, dir, reach);
                if (p.handle().lengthSqr() > 1e-4) {
                    best = better(best, line, HANDLE, i, sphere(eye, dir, p.pos().add(p.handle()), 0.16), eye, dir, reach);
                    best = better(best, line, HANDLE, i, sphere(eye, dir, p.pos().subtract(p.handle()), 0.16), eye, dir, reach);
                }
            }
            if (best != null && best.line == line && best.type != BODY) continue;   // points win over the stretch they sit on
            List<Curve.Sample> s = samples(line);
            for (int i = 0; i + 1 < s.size(); i += 1) {
                double t = segment(eye, dir, s.get(i).pos(), s.get(i + 1).pos(), 0.13);
                if (t >= 0 && (best == null || t < best.dist - 0.05)) best = within(line, BODY, i, t, eye, dir, reach, best);
            }
        }
        return best;
    }

    private static Hit better(Hit best, LineEntity line, int type, int index, double t, Vec3 eye, Vec3 dir, double reach) {
        if (t < 0) return best;
        if (best != null && t >= best.dist) return best;
        return within(line, type, index, t, eye, dir, reach, best);
    }

    private static Hit within(LineEntity line, int type, int index, double t, Vec3 eye, Vec3 dir, double reach, Hit old) {
        return t <= reach ? new Hit(line, type, index, t, eye.add(dir.scale(t))) : old;
    }

    /** Distance along the ray to a sphere, or -1. */
    private static double sphere(Vec3 o, Vec3 d, Vec3 c, double r) {
        Vec3 oc = c.subtract(o);
        double t = oc.dot(d);
        if (t < 0) return -1;
        double d2 = oc.lengthSqr() - t * t;
        return d2 <= r * r ? t - Math.sqrt(r * r - d2) : -1;
    }

    /** Distance along the ray to where it passes within r of segment ab, or -1. */
    private static double segment(Vec3 o, Vec3 d, Vec3 a, Vec3 b, double r) {
        Vec3 u = b.subtract(a), w = o.subtract(a);
        double aa = d.dot(d), bb = d.dot(u), cc = u.dot(u), dd = d.dot(w), ee = u.dot(w);
        double den = aa * cc - bb * bb;
        double sc, tc;
        if (den < 1e-9) { sc = 0; tc = cc > 0 ? ee / cc : 0; }
        else { sc = (bb * ee - cc * dd) / den; tc = (aa * ee - bb * dd) / den; }
        tc = Math.max(0, Math.min(1, tc));
        sc = Math.max(0, (u.scale(tc).subtract(w)).dot(d) / aa);
        Vec3 p = o.add(d.scale(sc)), q = a.add(u.scale(tc));
        return p.distanceTo(q) <= r ? sc : -1;
    }
}
