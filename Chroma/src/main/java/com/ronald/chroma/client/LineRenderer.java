package com.ronald.chroma.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ronald.chroma.Chroma;
import com.ronald.chroma.content.Curve;
import com.ronald.chroma.content.LineEntity;
import com.ronald.chroma.content.ShaperItem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws a line along its curve every frame: an LED strip is a glowing band with a soft halo, string lights are a dark
 * wire with little glass bulbs and a glow round each. Colours and patterns move with the game clock. While the Light
 * Shaper is held, the points and curve handles are drawn too.
 */
public class LineRenderer implements BlockEntityRenderer<LineEntity> {
    private static final ResourceLocation STRIP = tex("strip"), HALO_LINE = tex("halo_line"), GLOW = tex("glow"), WIRE = tex("wire"), BULB = tex("bulb");
    private static ResourceLocation tex(String n) { return new ResourceLocation(Chroma.MODID, "textures/misc/" + n + ".png"); }

    public LineRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override public boolean shouldRenderOffScreen(LineEntity be) { return true; }
    @Override public int getViewDistance() { return 96; }
    /** Past this, lines are drawn plainly: no light thrown round them, no halos or bulb glows. */
    private static final double DETAIL = 48;
    @Override public boolean shouldRender(LineEntity be, Vec3 cam) { return be.getRenderBoundingBox().inflate(getViewDistance()).contains(cam); }

    @Override
    public void render(LineEntity line, float partial, PoseStack ps, MultiBufferSource buf, int light, int overlay) {
        List<Curve.Sample> samples = LinePick.samples(line);
        BlockPos o = line.getBlockPos();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        double t = (mc.level.getGameTime() + partial) / 20.0;
        PoseStack.Pose pose = ps.last();
        if (samples.size() >= 2) {
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            near = distanceTo(line, cam) < DETAIL;
            if (near) LightSplash.render(line, samples, o, pose, buf, t);
            if (line.kind() == Curve.BULBS) bulbs(line, samples, o, t, pose, buf);
            else strip(line, samples, o, t, pose, buf);
        }
        if (mc.player != null && mc.player.getMainHandItem().getItem() instanceof ShaperItem) handles(line, o, pose, buf);
    }

    /** Whether the line being drawn is close enough for the finer touches. */
    private boolean near = true;

    private static double distanceTo(LineEntity line, Vec3 cam) {
        net.minecraft.world.phys.AABB b = line.getRenderBoundingBox().deflate(5);
        double dx = Math.max(0, Math.max(b.minX - cam.x, cam.x - b.maxX));
        double dy = Math.max(0, Math.max(b.minY - cam.y, cam.y - b.maxY));
        double dz = Math.max(0, Math.max(b.minZ - cam.z, cam.z - b.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ------------------------------------------------------------------ LED strip

    private void strip(LineEntity line, List<Curve.Sample> s, BlockPos o, double t, PoseStack.Pose pose, MultiBufferSource buf) {
        int[] cols = line.colours();
        int mode = line.mode();
        float speed = Curve.SPEEDS[line.speed()];
        int n = s.size();
        int[] c = new int[n];
        float[] b = new float[n];
        for (int i = 0; i < n; i++) {
            c[i] = Curve.stripColour(cols, mode, speed, s.get(i).s(), t);
            b[i] = Curve.stripBrightness(mode, speed, s.get(i).s(), t);
        }
        // a raised bar: glowing face on top, slightly dimmer sides, standing off the wall
        VertexConsumer core = buf.getBuffer(RenderType.entityCutoutNoCull(STRIP));
        tube(core, pose, s, o, 0.075, 0.0, 0.07, c, b, 0.78f, LightTexture.FULL_BRIGHT);
        // a soft glow spilling onto the surface round it
        if (!near) return;
        VertexConsumer halo = buf.getBuffer(RenderType.entityTranslucentEmissive(HALO_LINE));
        ribbon(halo, pose, s, o, 0.7, 0.004, c, b, 0.3f, LightTexture.FULL_BRIGHT);
    }

    /** A flat band along the samples, lying on the surface (lift moves it off or into the surface). */
    private void ribbon(VertexConsumer vc, PoseStack.Pose pose, List<Curve.Sample> s, BlockPos o, double width, double lift,
                        int[] c, float[] b, float alpha, int light) {
        Matrix4f m = pose.pose();
        Matrix3f nm = pose.normal();
        for (int i = 0; i + 1 < s.size(); i++) {
            Curve.Sample a = s.get(i), z = s.get(i + 1);
            Vec3 sa = side(a).scale(width / 2), sz = side(z).scale(width / 2);
            Vec3 pa = a.pos().add(a.normal().scale(lift)).subtract(o.getX(), o.getY(), o.getZ());
            Vec3 pz = z.pos().add(z.normal().scale(lift)).subtract(o.getX(), o.getY(), o.getZ());
            float va = (float) (a.s() * 2), vz = (float) (z.s() * 2);
            int lightA = light, lightZ = light;
            vert(vc, m, nm, pa.subtract(sa), c[i], b[i], alpha, 0, va, lightA, a.normal());
            vert(vc, m, nm, pa.add(sa), c[i], b[i], alpha, 1, va, lightA, a.normal());
            vert(vc, m, nm, pz.add(sz), c[i + 1], b[i + 1], alpha, 1, vz, lightZ, z.normal());
            vert(vc, m, nm, pz.subtract(sz), c[i + 1], b[i + 1], alpha, 0, vz, lightZ, z.normal());
        }
    }

    /**
     * A bar of rectangular section along the samples: half-width hw, from `low` to `high` off the surface. The top is
     * drawn at full colour, the sides at `sideShade`, and both ends are capped.
     */
    private void tube(VertexConsumer vc, PoseStack.Pose pose, List<Curve.Sample> s, BlockPos o, double hw, double low, double high,
                      int[] c, float[] b, float sideShade, int light) {
        Matrix4f m = pose.pose();
        Matrix3f nm = pose.normal();
        Vec3 off = new Vec3(o.getX(), o.getY(), o.getZ());
        int n = s.size();
        Vec3[][] ring = new Vec3[n][];
        for (int i = 0; i < n; i++) {
            Curve.Sample a = s.get(i);
            Vec3 sd = side(a).scale(hw), p = a.pos().subtract(off);
            Vec3 lo = a.normal().scale(low), hi = a.normal().scale(high);
            ring[i] = new Vec3[]{p.subtract(sd).add(lo), p.subtract(sd).add(hi), p.add(sd).add(hi), p.add(sd).add(lo)};
        }
        for (int i = 0; i + 1 < n; i++) {
            Curve.Sample a = s.get(i), z = s.get(i + 1);
            float va = (float) (a.s() * 2), vz = (float) (z.s() * 2);
            Vec3 na = a.normal(), sa = side(a);
            // top
            vert(vc, m, nm, ring[i][1], c[i], b[i], 1f, 0, va, light, na);
            vert(vc, m, nm, ring[i][2], c[i], b[i], 1f, 1, va, light, na);
            vert(vc, m, nm, ring[i + 1][2], c[i + 1], b[i + 1], 1f, 1, vz, light, na);
            vert(vc, m, nm, ring[i + 1][1], c[i + 1], b[i + 1], 1f, 0, vz, light, na);
            // the two sides
            vert(vc, m, nm, ring[i][0], c[i], b[i] * sideShade, 1f, 0.1f, va, light, sa.scale(-1));
            vert(vc, m, nm, ring[i][1], c[i], b[i] * sideShade, 1f, 0.3f, va, light, sa.scale(-1));
            vert(vc, m, nm, ring[i + 1][1], c[i + 1], b[i + 1] * sideShade, 1f, 0.3f, vz, light, sa.scale(-1));
            vert(vc, m, nm, ring[i + 1][0], c[i + 1], b[i + 1] * sideShade, 1f, 0.1f, vz, light, sa.scale(-1));
            vert(vc, m, nm, ring[i][2], c[i], b[i] * sideShade, 1f, 0.7f, va, light, sa);
            vert(vc, m, nm, ring[i][3], c[i], b[i] * sideShade, 1f, 0.9f, va, light, sa);
            vert(vc, m, nm, ring[i + 1][3], c[i + 1], b[i + 1] * sideShade, 1f, 0.9f, vz, light, sa);
            vert(vc, m, nm, ring[i + 1][2], c[i + 1], b[i + 1] * sideShade, 1f, 0.7f, vz, light, sa);
        }
        for (int e = 0; e < 2; e++) {                       // end caps
            int i = e == 0 ? 0 : n - 1;
            Vec3 t = s.get(i).tangent().scale(e == 0 ? -1 : 1);
            for (int k = 0; k < 4; k++) {
                int q = e == 0 ? k : 3 - k;
                vert(vc, m, nm, ring[i][q], c[i], b[i] * sideShade, 1f, k == 1 || k == 2 ? 1 : 0, k >= 2 ? 1 : 0, light, t);
            }
        }
    }

    private static Vec3 side(Curve.Sample s) {
        Vec3 v = s.tangent().cross(s.normal());
        return v.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : v.normalize();
    }

    // ------------------------------------------------------------------ string lights

    private void bulbs(LineEntity line, List<Curve.Sample> s, BlockPos o, double t, PoseStack.Pose pose, MultiBufferSource buf) {
        Minecraft mc = Minecraft.getInstance();
        int n = s.size();
        int[] wireC = new int[n];
        float[] ones = new float[n];
        java.util.Arrays.fill(wireC, 0x1E3A22);
        java.util.Arrays.fill(ones, 1f);
        VertexConsumer wire = buf.getBuffer(RenderType.entityCutoutNoCull(WIRE));
        int wireLight = LevelRenderer.getLightColor(mc.level, BlockPos.containing(s.get(n / 2).pos()));
        tube(wire, pose, s, o, 0.022, 0.0, 0.04, wireC, ones, 0.75f, wireLight);

        VertexConsumer glass = buf.getBuffer(RenderType.entityCutoutNoCull(BULB));
        VertexConsumer glow = buf.getBuffer(RenderType.entityTranslucentEmissive(GLOW));
        Camera cam = mc.gameRenderer.getMainCamera();
        Vector3f left = cam.getLeftVector(), up = cam.getUpVector();
        int[] cols = line.colours();
        float speed = Curve.SPEEDS[line.speed()];
        double total = s.get(n - 1).s();
        int k = 0, j = 0;
        for (double at = LineEntity.BULB_START; at <= total; at += LineEntity.BULB_GAP, k++) {
            while (j + 1 < n && s.get(j + 1).s() < at) j++;
            Curve.Sample a = s.get(j), z = s.get(Math.min(n - 1, j + 1));
            double span = z.s() - a.s();
            double f = span > 1e-6 ? (at - a.s()) / span : 0;
            Vec3 p = a.pos().lerp(z.pos(), f).subtract(o.getX(), o.getY(), o.getZ());
            Vec3 nrm = a.normal();
            Vec3 axis = Math.abs(nrm.y) > 0.7 ? nrm : nrm.scale(0.55).add(0, -0.85, 0).normalize();
            Vec3 u = a.tangent().subtract(axis.scale(a.tangent().dot(axis)));
            u = u.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : u.normalize();
            int col = Curve.bulbColour(cols, k);
            float br = Curve.bulbBrightness(line.mode(), speed, k, t);
            int lit = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p.add(o.getX(), o.getY(), o.getZ())));
            box(glass, pose, p, axis, u, 0.0, 0.08, 0.045, 0x25472A, 1f, lit);                    // the socket
            box(glass, pose, p, axis, u, 0.08, 0.3, 0.07, col, 0.35f + 0.65f * br, LightTexture.FULL_BRIGHT);   // the glass
            Vec3 g = p.add(axis.scale(0.2));
            float size = 0.5f;
            if (near) billboard(glow, pose, g, left, up, size, col, 0.55f * br);
        }
    }

    /** A small square-section box running from `from` to `to` along axis. */
    private void box(VertexConsumer vc, PoseStack.Pose pose, Vec3 base, Vec3 axis, Vec3 u, double from, double to, double half, int col, float bright, int light) {
        Matrix4f m = pose.pose();
        Matrix3f nm = pose.normal();
        Vec3 v = axis.cross(u).normalize();
        Vec3 a0 = base.add(axis.scale(from)), a1 = base.add(axis.scale(to));
        Vec3 uu = u.scale(half), vv = v.scale(half);
        Vec3[] ring = {uu.add(vv), uu.subtract(vv), uu.scale(-1).subtract(vv), uu.scale(-1).add(vv)};
        for (int i = 0; i < 4; i++) {
            Vec3 r0 = ring[i], r1 = ring[(i + 1) % 4];
            Vec3 nrm = r0.add(r1).normalize();
            vert(vc, m, nm, a0.add(r0), col, bright, 1f, 0, 0, light, nrm);
            vert(vc, m, nm, a1.add(r0), col, bright, 1f, 0, 1, light, nrm);
            vert(vc, m, nm, a1.add(r1), col, bright, 1f, 1, 1, light, nrm);
            vert(vc, m, nm, a0.add(r1), col, bright, 1f, 1, 0, light, nrm);
        }
        for (int e = 0; e < 2; e++) {
            Vec3 c = e == 0 ? a0 : a1;
            Vec3 nrm = e == 0 ? axis.scale(-1) : axis;
            for (int i = 0; i < 4; i++) vert(vc, m, nm, c.add(ring[e == 0 ? 3 - i : i]), col, bright, 1f, i == 1 || i == 2 ? 1 : 0, i >= 2 ? 1 : 0, light, nrm);
        }
    }

    private void billboard(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vector3f left, Vector3f up, float size, int col, float alpha) {
        Matrix4f m = pose.pose();
        Matrix3f nm = pose.normal();
        Vec3 l = new Vec3(left.x(), left.y(), left.z()).scale(size / 2), u = new Vec3(up.x(), up.y(), up.z()).scale(size / 2);
        Vec3 n = l.cross(u).normalize();
        vert(vc, m, nm, c.add(l).subtract(u), col, 1f, alpha, 0, 1, LightTexture.FULL_BRIGHT, n);
        vert(vc, m, nm, c.subtract(l).subtract(u), col, 1f, alpha, 1, 1, LightTexture.FULL_BRIGHT, n);
        vert(vc, m, nm, c.subtract(l).add(u), col, 1f, alpha, 1, 0, LightTexture.FULL_BRIGHT, n);
        vert(vc, m, nm, c.add(l).add(u), col, 1f, alpha, 0, 0, LightTexture.FULL_BRIGHT, n);
    }

    private static void vert(VertexConsumer vc, Matrix4f m, Matrix3f nm, Vec3 p, int col, float bright, float alpha, float u, float v, int light, Vec3 n) {
        float r = ((col >> 16) & 255) / 255f * bright, g = ((col >> 8) & 255) / 255f * bright, b = (col & 255) / 255f * bright;
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, alpha).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light).normal(nm, (float) n.x, (float) n.y, (float) n.z).endVertex();
    }

    // ------------------------------------------------------------------ the Light Shaper's view

    private void handles(LineEntity line, BlockPos o, PoseStack.Pose pose, MultiBufferSource buf) {
        VertexConsumer lines = buf.getBuffer(RenderType.lines());
        LinePick.Hit hit = ChromaClient.lastHit;
        List<Curve.Pt> pts = line.points();
        Vec3 off = new Vec3(o.getX(), o.getY(), o.getZ());
        for (int i = 0; i < pts.size(); i++) {
            Curve.Pt p = pts.get(i);
            Vec3 c = p.pos().subtract(off);
            boolean hot = hit != null && hit.line() == line && hit.index() == i && hit.type() != LinePick.BODY;
            float r = hot ? 1f : 0.9f, g = hot ? 0.85f : 0.9f, b = hot ? 0.2f : 0.95f;
            double sz = i == 0 || i == pts.size() - 1 ? 0.09 : 0.07;
            lineBox(lines, pose, c, sz, r, g, b);
            Vec3 h = p.handle();
            if (h.lengthSqr() > 1e-4) {
                Vec3 a = c.subtract(h), z = c.add(h);
                segment(lines, pose, a, z, 0.4f, 0.85f, 1f);
                for (Vec3 tip : new Vec3[]{a, z}) {
                    double ts = 0.05;
                    lineBox(lines, pose, tip, ts, 0.4f, 0.85f, 1f);
                }
            }
        }
    }

    /** A small wire cube round c. */
    private void lineBox(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, double h, float r, float g, float b) {
        for (int i = 0; i < 12; i++) {
            int axis = i / 4, k = i % 4;
            double s1 = (k & 1) == 0 ? -h : h, s2 = (k & 2) == 0 ? -h : h;
            Vec3 a, z;
            if (axis == 0) { a = c.add(-h, s1, s2); z = c.add(h, s1, s2); }
            else if (axis == 1) { a = c.add(s1, -h, s2); z = c.add(s1, h, s2); }
            else { a = c.add(s1, s2, -h); z = c.add(s1, s2, h); }
            segment(vc, pose, a, z, r, g, b);
        }
    }

    private void segment(VertexConsumer vc, PoseStack.Pose pose, Vec3 a, Vec3 z, float r, float g, float b) {
        Vec3 d = z.subtract(a).normalize();
        vc.vertex(pose.pose(), (float) a.x, (float) a.y, (float) a.z).color(r, g, b, 1f).normal(pose.normal(), (float) d.x, (float) d.y, (float) d.z).endVertex();
        vc.vertex(pose.pose(), (float) z.x, (float) z.y, (float) z.z).color(r, g, b, 1f).normal(pose.normal(), (float) d.x, (float) d.y, (float) d.z).endVertex();
    }
}
