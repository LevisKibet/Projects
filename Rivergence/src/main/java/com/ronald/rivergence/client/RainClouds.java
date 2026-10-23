package com.ronald.rivergence.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ronald.rivergence.Rivergence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.CloudStatus;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * What a passing shower looks like from anywhere round about: a dark, heavy cloud over it (blocky, like Minecraft's
 * own clouds, only lower and greyer), grey veils of rain hanging from it to the ground, and - with the sun low
 * behind you and the shower ahead - a rainbow.
 */
@Mod.EventBusSubscriber(modid = Rivergence.MODID, value = Dist.CLIENT)
public final class RainClouds {
    private RainClouds() {}

    /** Cells the size of vanilla's (12 blocks across, 4 high), shaped by the same cloud texture (so packs that change it change these too). */
    private static final int CELL = 12;
    private static final ResourceLocation CLOUDS = new ResourceLocation("textures/environment/clouds.png");
    private static final ResourceLocation SHAFT = new ResourceLocation(Rivergence.MODID, "textures/environment/rain_shaft.png");
    private static NativeImage map;
    private static float solidU = 0.5f, solidV = 0.5f;
    private static boolean tried;
    private static com.mojang.blaze3d.vertex.VertexBuffer VB;

    private static double hash(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** The cloud texture, read once (its opaque pixels are where clouds are, as for vanilla's). */
    private static void load() {
        if (tried) return;
        tried = true;
        try (var in = Minecraft.getInstance().getResourceManager().open(CLOUDS)) {
            map = NativeImage.read(in);
            outer:
            for (int y = 0; y < map.getHeight(); y++) for (int x = 0; x < map.getWidth(); x++)
                if (((map.getPixelRGBA(x, y) >>> 24) & 255) > 200) { solidU = (x + 0.5f) / map.getWidth(); solidV = (y + 0.5f) / map.getHeight(); break outer; }
        } catch (Exception ex) { map = null; }
    }

    private static boolean texCloud(int i, int j) {
        if (map == null) return hash(i, j, 77) < 0.5;
        int x = Math.floorMod(i, map.getWidth()), y = Math.floorMod(j, map.getHeight());
        return ((map.getPixelRGBA(x, y) >>> 24) & 255) > 25;
    }

    /** How many 4-block layers of cloud a shower has over cell (i, j): a solid dark core, ragged at the edges like vanilla's. */
    private static int layers(WaterWeather.Shower s, int i, int j) {
        float cx = i * CELL + CELL / 2f, cz = j * CELL + CELL / 2f;
        float d = (float) Math.hypot(cx - s.x, cz - s.z) / (s.radius * 1.2f);
        if (d >= 1) return 0;
        boolean tex = texCloud(i, j);
        int n = d < 0.62f || tex ? 1 : 0;
        if (n > 0 && (d < 0.45f || d < 0.75f && tex)) n = 2;
        return n;
    }

    /** With a shader pack in use, its own sky does the clouds (it sees the shower's rain), so ours stand aside. */
    private static boolean shaderPack() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isShaderPackInUse").invoke(inst);
        } catch (Throwable t) { return false; }
    }

    private static com.mojang.blaze3d.vertex.VertexBuffer SHADOW_VB;
    private static final Matrix4f IDENTITY = new Matrix4f();

    /**
     * Called from inside Luxium's cloud-shadow pass (see LuxiumCloudShadowMixin): the rain clouds drawn into its shadow
     * map with its own shadow shader, so they darken the ground under them just as its clouds do. Positions are
     * relative to the pass's centre; whatever shader and target the pass has set up are used as they are.
     */
    public static void castShadows(com.mojang.blaze3d.vertex.PoseStack pose, Matrix4f proj, double cx, double cy, double cz) {
        List<WaterWeather.Shower> showers = WaterWeather.seen;
        if (showers.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.options.getCloudsType() == CloudStatus.OFF) return;
        float cloudY = level.effects().getCloudHeight();
        if (Float.isNaN(cloudY)) return;
        var shader = RenderSystem.getShader();
        if (shader == null) return;
        load();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        for (WaterWeather.Shower s : showers) {
            if (s.strength < 0.25f) continue;
            float base = cloudY - 8.5f;
            int i0 = Mth.floor((s.x - s.radius * 1.25f) / CELL), i1 = Mth.floor((s.x + s.radius * 1.25f) / CELL);
            int j0 = Mth.floor((s.z - s.radius * 1.25f) / CELL), j1 = Mth.floor((s.z + s.radius * 1.25f) / CELL);
            for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
                int n = layers(s, i, j);
                if (n == 0) continue;
                float x0 = (float) (i * CELL - cx), x1 = x0 + CELL, z0 = (float) (j * CELL - cz), z1 = z0 + CELL;
                float y0 = (float) (base - (n - 1) * 4 - cy), y1 = (float) (base + 4 - cy);
                box(b, IDENTITY, x0, y0, z0, x1, y1, z1, solidU, solidV, 1, 1, 1, 1, true, true, true, true);
            }
        }
        BufferBuilder.RenderedBuffer rb = b.end();
        if (rb.isEmpty()) { rb.release(); return; }
        RenderSystem.setShaderTexture(0, CLOUDS);
        RenderSystem.disableCull();
        if (SHADOW_VB == null) SHADOW_VB = new com.mojang.blaze3d.vertex.VertexBuffer(com.mojang.blaze3d.vertex.VertexBuffer.Usage.DYNAMIC);
        SHADOW_VB.bind();
        SHADOW_VB.upload(rb);
        SHADOW_VB.drawWithShader(pose.last().pose(), proj, shader);
        com.mojang.blaze3d.vertex.VertexBuffer.unbind();
        RenderSystem.enableCull();
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        List<WaterWeather.Shower> showers = WaterWeather.seen;
        if (showers.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) { rainbow(e, level, showers); return; }
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (mc.options.getCloudsType() == CloudStatus.OFF) return;
        load();
        Vec3 cam = e.getCamera().getPosition();
        Matrix4f mat = e.getPoseStack().last().pose();
        float pt = e.getPartialTick();
        boolean clouds = !shaderPack();
        float cloudY = level.effects().getCloudHeight();
        if (Float.isNaN(cloudY)) return;
        Vec3 cc = level.getCloudColor(pt);
        // a rain cloud: vanilla's cloud colour, darkened
        float lr = (float) cc.x * 0.55f, lg = (float) cc.y * 0.56f, lb = (float) cc.z * 0.6f;

        if (clouds) {
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
            for (WaterWeather.Shower s : showers) {
                double dist = Math.hypot(s.x - cam.x, s.z - cam.z);
                float fade = s.strength * Mth.clamp((float) (1 - (dist - 440) / 100), 0, 1);
                if (fade < 0.05f) continue;
                float a = Math.min(1, fade * 1.4f);                                    // solid, like a heavy rain cloud
                float base = cloudY - 8.5f;                                         // just under vanilla's layer
                int i0 = Mth.floor((s.x - s.radius * 1.25f) / CELL), i1 = Mth.floor((s.x + s.radius * 1.25f) / CELL);
                int j0 = Mth.floor((s.z - s.radius * 1.25f) / CELL), j1 = Mth.floor((s.z + s.radius * 1.25f) / CELL);
                for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
                    int n = layers(s, i, j);
                    if (n == 0) continue;
                    boolean tex = texCloud(i, j);
                    float u = tex ? (Math.floorMod(i, 256) + 0.5f) / 256f : solidU, v = tex ? (Math.floorMod(j, 256) + 0.5f) / 256f : solidV;
                    float x0 = (float) (i * CELL - cam.x), x1 = x0 + CELL, z0 = (float) (j * CELL - cam.z), z1 = z0 + CELL;
                    float y0 = (float) (base - (n - 1) * 4 - cam.y), y1 = (float) (base + 4 - cam.y);
                    // as vanilla shades its cloud faces: tops full, bottoms 0.7, sides 0.9 and 0.8
                    box(b, mat, x0, y0, z0, x1, y1, z1, u, v, lr, lg, lb, a,
                            layers(s, i - 1, j) < n, layers(s, i + 1, j) < n, layers(s, i, j - 1) < n, layers(s, i, j + 1) < n);
                }
            }
            RenderSystem.setShader(GameRenderer::getPositionTexColorNormalShader);
            RenderSystem.setShaderTexture(0, CLOUDS);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.disableCull();
            BufferBuilder.RenderedBuffer rb = b.end();
            if (rb.isEmpty()) { rb.release(); RenderSystem.enableCull(); RenderSystem.disableBlend(); }
            else {
            // as vanilla draws its clouds: depth first, then colour, so you never see the faces inside them
            if (VB == null) VB = new com.mojang.blaze3d.vertex.VertexBuffer(com.mojang.blaze3d.vertex.VertexBuffer.Usage.DYNAMIC);
            var vb = VB;
            vb.bind();
            vb.upload(rb);
            var proj = RenderSystem.getProjectionMatrix();
            RenderSystem.colorMask(false, false, false, false);
            vb.drawWithShader(RenderSystem.getModelViewMatrix(), proj, RenderSystem.getShader());
            RenderSystem.colorMask(true, true, true, true);
            vb.drawWithShader(RenderSystem.getModelViewMatrix(), proj, RenderSystem.getShader());
            com.mojang.blaze3d.vertex.VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            }
        }

        // shafts of rain hanging from the cloud to the ground, as a shower looks from far off: streaky grey curtains,
        // densest in the middle, soft at the sides and fading toward the ground. Seen from a few hundred blocks, they
        // thin out as you come near, and close up (within ~50 blocks of the shower) they're gone and the rain itself
        // falls round you
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        boolean any = false;
        float fall = ((level.getGameTime() % 1024) + pt) * 0.035f;            // the streaks fall
        float dr = (float) cc.x, dg = (float) cc.y, db = (float) cc.z;          // daylight
        for (WaterWeather.Shower s : showers) {
            double dist = Math.hypot(s.x - cam.x, s.z - cam.z);
            // seen from outside it, right up to about 30 blocks from where the rain falls; under it, just the rain
            double edge = dist - s.radius;
            float show = s.strength * smooth(8, 30, (float) edge) * (1 - smooth(440, 540, (float) dist));
            if (show < 0.02f) continue;
            // the wind leans the shafts all one way
            double lean = hash(1, 2, s.seed) * Math.PI * 2;
            float lx = (float) Math.cos(lean) * 10, lz = (float) Math.sin(lean) * 10;
            float top = (float) (cloudY - 9 - cam.y), bot = (float) (s.ground - 6 - cam.y);
            int n = 22;
            for (int k = 0; k < n; k++) {
                double ang = hash(k, 3, s.seed) * Math.PI * 2, rr = Math.sqrt(hash(k, 5, s.seed)) * s.radius * 0.8;
                float cx = (float) (s.x + Math.cos(ang) * rr - cam.x), cz = (float) (s.z + Math.sin(ang) * rr - cam.z);
                float w = 22 + (float) hash(k, 7, s.seed) * 46;
                float dense = 1 - (float) (rr / (s.radius * 0.8)) * 0.6f;              // thicker toward the middle
                // turned to face the camera about the upright
                float fx = -cz, fz = cx, fl = (float) Math.sqrt(fx * fx + fz * fz);
                if (fl < 1e-3f) continue;
                fx = fx / fl * w / 2; fz = fz / fl * w / 2;
                float u0 = (float) hash(k, 11, s.seed), u1 = u0 + 1;
                float vTop = -fall * (0.8f + (float) hash(k, 9, s.seed) * 0.4f) + (float) hash(k, 13, s.seed) * 4;
                float vBot = vTop + (top - bot) / 48f;
                float a = 0.42f * show * dense;
                float g = 0.62f;
                float r = dr * g, gg = dg * g * 1.02f, bl = db * g * 1.08f;
                b.vertex(mat, cx - fx + lx, top, cz - fz + lz).uv(u0, vTop).color(r, gg, bl, a).endVertex();
                b.vertex(mat, cx - fx, bot, cz - fz).uv(u0, vBot).color(r, gg, bl, a * 0.35f).endVertex();
                b.vertex(mat, cx + fx, bot, cz + fz).uv(u1, vBot).color(r, gg, bl, a * 0.35f).endVertex();
                b.vertex(mat, cx + fx + lx, top, cz + fz + lz).uv(u1, vTop).color(r, gg, bl, a).endVertex();
                any = true;
            }
        }
        if (any) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, SHAFT);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            BufferUploader.drawWithShader(b.end());
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        } else b.end().release();
    }

    private static float smooth(float a, float b, float v) { float t = Mth.clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }

    private static void box(BufferBuilder b, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, float u, float v,
                            float r, float g, float bl, float a, boolean west, boolean east, boolean north, boolean south) {
        face(b, m, u, v, r, g, bl, a, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        face(b, m, u, v, r * 0.7f, g * 0.7f, bl * 0.7f, a, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        if (west) face(b, m, u, v, r * 0.9f, g * 0.9f, bl * 0.9f, a, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        if (east) face(b, m, u, v, r * 0.9f, g * 0.9f, bl * 0.9f, a, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        if (north) face(b, m, u, v, r * 0.8f, g * 0.8f, bl * 0.8f, a, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        if (south) face(b, m, u, v, r * 0.8f, g * 0.8f, bl * 0.8f, a, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
    }

    private static void face(BufferBuilder b, Matrix4f m, float u, float v, float r, float g, float bl, float a, float nx, float ny, float nz, float... p) {
        for (int k = 0; k < 4; k++)
            b.vertex(m, p[k * 3], p[k * 3 + 1], p[k * 3 + 2]).uv(u, v).color(r, g, bl, a).normal(nx, ny, nz).endVertex();
    }

    /**
     * A rainbow: with the sun low behind you (under 42° up) and a shower ahead of you in the antisolar direction,
     * an arc of colour 42° round the point opposite the sun. Brightest as the shower passes off (toward its end).
     */
    private static void rainbow(RenderLevelStageEvent e, ClientLevel level, List<WaterWeather.Shower> showers) {
        float pt = e.getPartialTick();
        float ang = level.getSunAngle(pt);
        float sx = -Mth.sin(ang), sy = Mth.cos(ang);                         // the sun's direction (it moves in x/y)
        if (sy < 0.04f || sy > 0.62f) return;                                 // low sun, but up
        Vec3 cam = e.getCamera().getPosition();
        float vis = 0;
        for (WaterWeather.Shower s : showers) {
            double dx = s.x - cam.x, dz = s.z - cam.z, d = Math.hypot(dx, dz);
            if (d < s.radius * 0.6 || d > s.radius * 3.2) continue;            // not from deep inside it, not from too far
            double toward = -dx / d * Math.signum(sx);                         // the shower lies away from the sun
            if (toward < 0.25) continue;
            float late = Mth.clamp((s.phase - 0.45f) / 0.25f, 0, 1);
            vis = Math.max(vis, s.strength * late * (float) Math.min(1, (toward - 0.25) * 2));
        }
        vis *= 1 - level.getRainLevel(pt);
        if (vis < 0.02f) return;
        // round the antisolar point A, 42° out; drawn far off on the sky, behind everything
        float ax = -sx, ay = -sy;
        // two directions square to A: across (z) and "up" in A's plane
        float ux = -ay, uy = ax;
        if (uy < 0) { ux = -ux; uy = -uy; }
        Matrix4f mat = e.getPoseStack().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float[][] bands = {{0.55f, 0.2f, 0.75f}, {0.25f, 0.3f, 0.9f}, {0.2f, 0.7f, 0.9f}, {0.3f, 0.85f, 0.3f}, {0.95f, 0.9f, 0.25f}, {0.95f, 0.55f, 0.15f}, {0.9f, 0.2f, 0.15f}};
        float R = 200, a = 0.32f * vis;
        int steps = 96;
        for (int k = 0; k < bands.length; k++) {
            float r0 = (float) Math.toRadians(39.5 + k * 0.62), r1 = (float) Math.toRadians(39.5 + (k + 1) * 0.62);
            float[] c = bands[k];
            float edge = k == 0 || k == bands.length - 1 ? 0.55f : 1f;
            for (int i = 0; i < steps; i++) {
                double p0 = Math.PI * 2 * i / steps, p1 = Math.PI * 2 * (i + 1) / steps;
                float[] v00 = ring(ax, ay, ux, uy, r0, p0), v10 = ring(ax, ay, ux, uy, r0, p1), v11 = ring(ax, ay, ux, uy, r1, p1), v01 = ring(ax, ay, ux, uy, r1, p0);
                if (v00[1] < -0.08f && v10[1] < -0.08f) continue;               // below the horizon
                float al = a * edge;
                b.vertex(mat, v00[0] * R, v00[1] * R, v00[2] * R).color(c[0], c[1], c[2], al).endVertex();
                b.vertex(mat, v10[0] * R, v10[1] * R, v10[2] * R).color(c[0], c[1], c[2], al).endVertex();
                b.vertex(mat, v11[0] * R, v11[1] * R, v11[2] * R).color(c[0], c[1], c[2], al).endVertex();
                b.vertex(mat, v01[0] * R, v01[1] * R, v01[2] * R).color(c[0], c[1], c[2], al).endVertex();
            }
        }
        Tesselator.getInstance().end();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** A point on the circle radius rad round A, at angle p (U is in A's vertical plane, z across it). */
    private static float[] ring(float ax, float ay, float ux, float uy, float rad, double p) {
        float c = Mth.cos(rad), s = Mth.sin(rad);
        float cp = (float) Math.cos(p), sp = (float) Math.sin(p);
        return new float[]{ax * c + ux * s * cp, ay * c + uy * s * cp, s * sp};
    }
}
