package com.ronald.chroma.light;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Lights the world's blocks in colour. As the chunk builder puts each face of each block together, every corner of
 * the face looks up the coloured light in the air just in front of it (blending the blocks round it, so light fades
 * smoothly across a wall) and its vertex colour is multiplied by that light - the same place vanilla puts its
 * shading. A face lit by a green light is drawn green-lit: green things stay bright, red things go dark.
 *
 * Daylight wins: where the sky light reaching a spot (at this time of day) is stronger than the block light, the
 * colour fades out, so torches don't tint a sunny field but light a cave or a night-time street in their own colour.
 */
public final class ColorLight {
    private ColorLight() {}

    private static final ThreadLocal<Tinter> TINTER = ThreadLocal.withInitial(Tinter::new);
    private static final ThreadLocal<BlockPos> POS = new ThreadLocal<>();

    public static void at(BlockPos pos) { POS.set(pos); }

    /** Called instead of VertexConsumer.putBulkData while a block's faces are built. */
    public static void put(VertexConsumer out, PoseStack.Pose pose, BakedQuad quad, float[] brightness, float r, float g, float b,
                           int[] lights, int overlay, boolean readExisting) {
        BlockPos pos = POS.get();
        VoxelLight.Field f = VoxelLight.current;
        float[] moving = DynamicLights.sources();
        if (pos == null || !ChromaLight.ACTIVE || (f == null && moving.length == 0) || !Settings.enabled()) {
            out.putBulkData(pose, quad, brightness, r, g, b, lights, overlay, readExisting);
            return;
        }
        Tinter t = TINTER.get();
        if (!t.prepare(f, moving, pos, quad)) {
            out.putBulkData(pose, quad, brightness, r, g, b, lights, overlay, readExisting);
            return;
        }
        t.out = out;
        t.k = 0;
        try { t.putBulkData(pose, quad, brightness, r, g, b, lights, overlay, readExisting); }
        finally { t.out = null; }
    }

    /**
     * For renderers that build faces their own way (Embeddium/Rubidium): corners in world space in, the colour each
     * corner should be multiplied by out. Returns null when nothing here is coloured.
     */
    public static float[] tints(float[] corners, Direction face) {
        VoxelLight.Field f = VoxelLight.current;
        float[] moving = DynamicLights.sources();
        if (!ChromaLight.ACTIVE || (f == null && moving.length == 0) || !Settings.enabled()) return null;
        Tinter t = TINTER.get();
        System.arraycopy(corners, 0, t.pts, 0, 12);
        return t.compute(f, moving, face) ? t.tint : null;
    }

    /** Works out the light at each corner of a face, then passes the vertices on with their colour multiplied. */
    private static final class Tinter implements VertexConsumer {
        VertexConsumer out;
        int k;
        final float[] tint = new float[12];
        private final float[] c = new float[4];
        private final float[] s3 = new float[3];

        final float[] pts = new float[12];

        boolean prepare(VoxelLight.Field f, float[] moving, BlockPos pos, BakedQuad quad) {
            int[] v = quad.getVertices();
            int n = Math.min(4, v.length / 8);
            for (int i = 0; i < 4; i++) {
                int vi = Math.min(i, n - 1) * 8;
                pts[i * 3] = pos.getX() + Float.intBitsToFloat(v[vi]);
                pts[i * 3 + 1] = pos.getY() + Float.intBitsToFloat(v[vi + 1]);
                pts[i * 3 + 2] = pos.getZ() + Float.intBitsToFloat(v[vi + 2]);
            }
            return compute(f, moving, quad.getDirection());
        }

        /** pts holds the 4 corners in world space; fills tint. */
        boolean compute(VoxelLight.Field f, float[] moving, Direction d) {
            float nx = d.getStepX() * 0.5f, ny = d.getStepY() * 0.5f, nz = d.getStepZ() * 0.5f;
            float strength = Settings.strength();
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                sample(f, moving, pts[i * 3] + nx, pts[i * 3 + 1] + ny, pts[i * 3 + 2] + nz);
                // what lights this spot, per colour: the block light, or the sky light (as bright as the time of
                // day makes it, and coloured if it came through glass), whichever is stronger - as vanilla does
                int darken = f != null ? f.skyDarken() : 0;
                float tr = Math.max(c[0], s3[0] - darken), tg = Math.max(c[1], s3[1] - darken), tb = Math.max(c[2], s3[2] - darken);
                float L = Math.max(tr, Math.max(tg, tb));
                float w = 0;
                if (L > 0.3f) {
                    w = smooth(0.3f, 2.5f, L) * strength;
                    c[0] = tr; c[1] = tg; c[2] = tb;
                }
                if (w > 0.004f) {
                    any = true;
                    tint[i * 3] = 1 - w * (1 - c[0] / L);
                    tint[i * 3 + 1] = 1 - w * (1 - c[1] / L);
                    tint[i * 3 + 2] = 1 - w * (1 - c[2] / L);
                } else { tint[i * 3] = 1; tint[i * 3 + 1] = 1; tint[i * 3 + 2] = 1; }
            }
            return any;
        }

        /** Coloured light (levels 0..15 per channel) and sky light at a point, blended from the 8 blocks round it. */
        private void sample(VoxelLight.Field f, float[] moving, float x, float y, float z) {
            float r = 0, g = 0, b = 0, sr = 15, sg = 15, sb = 15;
            if (f != null) {
                float fx = x - 0.5f, fy = y - 0.5f, fz = z - 0.5f;
                int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy), z0 = (int) Math.floor(fz);
                float tx = fx - x0, ty = fy - y0, tz = fz - z0;
                sr = 0; sg = 0; sb = 0;
                for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                    float w = (dx == 0 ? 1 - tx : tx) * (dy == 0 ? 1 - ty : ty) * (dz == 0 ? 1 - tz : tz);
                    int i = f.index(x0 + dx, y0 + dy, z0 + dz);
                    if (i < 0) { sr += 15 * VoxelLight.UNIT * w; sg += 15 * VoxelLight.UNIT * w; sb += 15 * VoxelLight.UNIT * w; continue; }
                    int lv = f.light()[i];
                    r += (lv & 255) * w; g += ((lv >> 8) & 255) * w; b += ((lv >> 16) & 255) * w;
                    int sv = f.skyRgb(i);
                    sr += (sv & 255) * w; sg += ((sv >> 8) & 255) * w; sb += ((sv >> 16) & 255) * w;
                }
                float u = 1f / VoxelLight.UNIT;
                r *= u; g *= u; b *= u; sr *= u; sg *= u; sb *= u;
            }
            for (int i = 0; i < moving.length; i += DynamicLights.S) {
                float dx = x - moving[i], dy = y - moving[i + 1], dz = z - moving[i + 2];
                float lvl = moving[i + 3] - (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (lvl <= 0) continue;
                float m = Math.max(moving[i + 4], Math.max(moving[i + 5], moving[i + 6]));
                if (m <= 0) continue;
                r = Math.max(r, moving[i + 4] / m * lvl); g = Math.max(g, moving[i + 5] / m * lvl); b = Math.max(b, moving[i + 6] / m * lvl);
            }
            c[0] = r; c[1] = g; c[2] = b; s3[0] = sr; s3[1] = sg; s3[2] = sb;
        }

        private static float clamp(float v) { return v < 0 ? 0 : Math.min(v, 1); }
        private static float smooth(float a, float b, float v) { float t = clamp((v - a) / (b - a)); return t * t * (3 - 2 * t); }

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            int i = Math.min(k++, 3) * 3;
            out.vertex(x, y, z, r * tint[i], g * tint[i + 1], b * tint[i + 2], a, u, v, overlay, light, nx, ny, nz);
        }

        // everything else goes straight through
        @Override public VertexConsumer vertex(double x, double y, double z) { out.vertex(x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { out.color(r, g, b, a); return this; }
        @Override public VertexConsumer uv(float u, float v) { out.uv(u, v); return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { out.overlayCoords(u, v); return this; }
        @Override public VertexConsumer uv2(int u, int v) { out.uv2(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { out.normal(x, y, z); return this; }
        @Override public void endVertex() { out.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) { out.defaultColor(r, g, b, a); }
        @Override public void unsetDefaultColor() { out.unsetDefaultColor(); }
        @Override public VertexConsumer misc(VertexFormatElement e, int... values) { out.misc(e, values); return this; }
    }
}
