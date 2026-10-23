package com.ronald.rivergence.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.ronald.rivergence.Rivergence;
import com.ronald.rivergence.RivergenceConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Sheets of mist lying over lakes and rivers, a few layers deep, streaked and slowly drifting, out to the edge of the
 * view; and low grey cloud banks over the patches of water where a shower is passing. Where the water is comes from a
 * coarse survey of every chunk in view (one point per 4x4 blocks), kept up to date a few chunks at a time.
 */
@Mod.EventBusSubscriber(modid = Rivergence.MODID, value = Dist.CLIENT)
public final class FogLayers {
    private FogLayers() {}

    static final ResourceLocation FOG = new ResourceLocation(Rivergence.MODID, "textures/environment/fog_layer.png");
    static final ResourceLocation CLOUD = new ResourceLocation(Rivergence.MODID, "textures/environment/rain_cloud.png");
    private static final int NONE = Short.MIN_VALUE;

    /** Per chunk: the water surface's height in each 4x4-block cell (NONE where there's none), and the mesh made from it. */
    private static final class Patch {
        final short[] y = new short[16];
        float[] mesh;            // per quad: x0 z0 x1 z1 y a00 a10 a11 a01
        long made;
        int water;
    }
    private static final Long2ObjectOpenHashMap<Patch> PATCHES = new Long2ObjectOpenHashMap<>();
    private static ClientLevel seen;
    private static int cursor;

    /** How thick the mist lies (0..1), eased: dawn, the rain, a little at night. */
    static float mist;

    static float waterShare(int bx, int bz, int radius) {
        int cells = 0, wet = 0;
        for (int cx = (bx - radius) >> 4; cx <= (bx + radius) >> 4; cx++) for (int cz = (bz - radius) >> 4; cz <= (bz + radius) >> 4; cz++) {
            Patch p = PATCHES.get(ChunkPos.asLong(cx, cz));
            if (p == null) continue;
            cells += 16; wet += p.water;
        }
        return cells == 0 ? 0 : wet / (float) cells;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != seen) { PATCHES.clear(); seen = level; }
        if (level == null || mc.player == null || !RivergenceConfig.weather()) return;
        int view = mc.options.getEffectiveRenderDistance();
        ChunkPos at = mc.player.chunkPosition();
        long now = level.getGameTime();
        // survey a few chunks each tick, round the view in a spiral-ish sweep, nearest refreshed most often
        int side = view * 2 + 1, total = side * side;
        for (int n = 0; n < 24; n++) {
            cursor = (cursor + 1) % total;
            int cx = at.x + cursor % side - view, cz = at.z + cursor / side - view;
            if (!level.hasChunk(cx, cz)) continue;
            long key = ChunkPos.asLong(cx, cz);
            Patch p = PATCHES.get(key);
            int d = Math.max(Math.abs(cx - at.x), Math.abs(cz - at.z));
            if (p != null && now - p.made < (d < 4 ? 100 : 600)) continue;
            survey(level, cx, cz, key);
        }
        // forget chunks well out of view
        if (now % 100 == 0) PATCHES.long2ObjectEntrySet().removeIf(en -> {
            int cx = ChunkPos.getX(en.getLongKey()), cz = ChunkPos.getZ(en.getLongKey());
            return Math.max(Math.abs(cx - at.x), Math.abs(cz - at.z)) > view + 2;
        });
        // how thick the mist is now
        float dawn = WaterWeather.dawn(level.getDayTime());
        long t = Math.floorMod(level.getDayTime(), 24000L);
        float night = t > 13500 && t < 22200 ? 0.25f : 0;
        float target = Math.max(dawn, Math.max(night, level.isRaining() ? 0.55f : 0));
        mist += (target - mist) * 0.01f;
    }

    private static void survey(ClientLevel level, int cx, int cz, long key) {
        Patch p = new Patch();
        p.made = level.getGameTime();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            int x = (cx << 4) + i * 4 + 2, z = (cz << 4) + j * 4 + 2;
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            short y = (short) NONE;
            // fresh water only: lakes and rivers stand above the sea
            if (top >= 63 && level.getFluidState(m.set(x, top, z)).is(FluidTags.WATER)) { y = (short) top; p.water++; }
            p.y[i + j * 4] = y;
        }
        PATCHES.put(key, p);
        mesh(key, cx, cz, p);
        // the neighbours' soft edges may change with this one
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            long k2 = ChunkPos.asLong(cx + dx, cz + dz);
            Patch q = PATCHES.get(k2);
            if (q != null && q.water > 0) mesh(k2, cx + dx, cz + dz, q);
        }
    }

    private static int cellY(int gx, int gz) {
        Patch p = PATCHES.get(ChunkPos.asLong(gx >> 2, gz >> 2));
        return p == null ? NONE : p.y[(gx & 3) + (gz & 3) * 4];
    }

    /** One quad per wet cell; each corner as thick as the share of the four cells round it that are water at that height. */
    private static void mesh(long key, int cx, int cz, Patch p) {
        if (p.water == 0) { p.mesh = null; return; }
        float[] out = new float[p.water * 9];
        int n = 0;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            int y = p.y[i + j * 4];
            if (y == NONE) continue;
            int gx = (cx << 2) + i, gz = (cz << 2) + j;
            out[n++] = gx * 4; out[n++] = gz * 4; out[n++] = gx * 4 + 4; out[n++] = gz * 4 + 4; out[n++] = y + 1;
            out[n++] = corner(gx, gz, y); out[n++] = corner(gx + 1, gz, y); out[n++] = corner(gx + 1, gz + 1, y); out[n++] = corner(gx, gz + 1, y);
        }
        p.mesh = out;
    }

    private static float corner(int gx, int gz, int y) {
        int c = 0;
        for (int dx = -1; dx <= 0; dx++) for (int dz = -1; dz <= 0; dz++) {
            int o = cellY(gx + dx, gz + dz);
            if (o != NONE && Math.abs(o - y) <= 1) c++;
        }
        return c * c / 16f;                                            // soft at the shore
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !RivergenceConfig.weather() || !level.dimensionType().natural()) return;
        Vec3 cam = e.getCamera().getPosition();
        float time = (level.getGameTime() + e.getPartialTick()) / 20f;
        float light = Mth.clamp(level.getSkyDarken(e.getPartialTick()) * 1.1f, 0.25f, 1f);
        float reach = mc.options.getEffectiveRenderDistance() * 16f;
        Matrix4f mat = e.getPoseStack().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        if (mist > 0.02f && !PATCHES.isEmpty()) {
            bind(mc, FOG);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            // layers: height above the water, how thick, texture scale, drift
            float[][] layers = {{0.25f, 0.55f, 1 / 22f, 0.10f, 0.03f}, {0.9f, 0.45f, 1 / 30f, -0.06f, 0.08f}, {1.7f, 0.32f, 1 / 40f, 0.05f, -0.05f}, {2.6f, 0.2f, 1 / 52f, -0.03f, -0.07f}};
            float r = 0.9f * light + 0.1f, g = 0.93f * light + 0.07f, bl = Math.min(1, light + 0.05f);
            boolean any = false;
            for (Patch p : PATCHES.values()) {
                if (p.mesh == null) continue;
                for (int q = 0; q < p.mesh.length; q += 9) {
                    float x0 = p.mesh[q], z0 = p.mesh[q + 1], x1 = p.mesh[q + 2], z1 = p.mesh[q + 3], wy = p.mesh[q + 4];
                    double dx = (x0 + 2) - cam.x, dz = (z0 + 2) - cam.z;
                    float dist = (float) Math.sqrt(dx * dx + dz * dz);
                    if (dist > reach) continue;
                    float far = 1 - Mth.clamp((dist - reach * 0.75f) / (reach * 0.25f), 0, 1);
                    float nearFade = Mth.clamp(dist / 3f, 0.35f, 1);          // thin round the player, so it doesn't blind
                    for (float[] L : layers) {
                        float y = wy - 1 + L[0];
                        float a = mist * L[1] * far * nearFade;
                        if (a < 0.01f) continue;
                        float su = L[2], ou = time * L[3] * su * 4, ov = time * L[4] * su * 4;
                        vert(b, mat, cam, x0, y, z0, x0 * su + ou, z0 * su + ov, r, g, bl, a * p.mesh[q + 5]);
                        vert(b, mat, cam, x1, y, z0, x1 * su + ou, z0 * su + ov, r, g, bl, a * p.mesh[q + 6]);
                        vert(b, mat, cam, x1, y, z1, x1 * su + ou, z1 * su + ov, r, g, bl, a * p.mesh[q + 7]);
                        vert(b, mat, cam, x0, y, z1, x0 * su + ou, z1 * su + ov, r, g, bl, a * p.mesh[q + 8]);
                        any = true;
                    }
                }
            }
            var built = b.end();
            if (any) BufferUploader.drawWithShader(built); else built.release();
        }

        // cloud banks over passing showers
        float cloudY = 150;
        boolean drew = false;
        BufferBuilder b = null;
        for (WaterWeather.Shower s : WaterWeather.showersInView(level, cam, reach)) {
            if (!drew) { bind(mc, CLOUD); b = Tesselator.getInstance().getBuilder(); b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR); drew = true; }
            float shade = 0.42f + 0.25f * light;
            int steps = 12;
            float cell = s.radius * 2 / steps;
            for (int k = 0; k < 2; k++) {
                float y = cloudY + k * 6, su = 1 / (90f + k * 40), ou = time * 0.004f * (k + 1), ov = time * 0.002f;
                for (int i = 0; i < steps; i++) for (int j = 0; j < steps; j++) {
                    float x0 = s.x - s.radius + i * cell, z0 = s.z - s.radius + j * cell, x1 = x0 + cell, z1 = z0 + cell;
                    float a00 = s.strength * edge(s, x0, z0), a10 = s.strength * edge(s, x1, z0), a11 = s.strength * edge(s, x1, z1), a01 = s.strength * edge(s, x0, z1);
                    if (a00 + a10 + a11 + a01 < 0.01f) continue;
                    float c = shade - k * 0.06f;
                    vert(b, mat, cam, x0, y, z0, x0 * su + ou, z0 * su + ov, c, c, c + 0.03f, a00 * 0.85f);
                    vert(b, mat, cam, x1, y, z0, x1 * su + ou, z0 * su + ov, c, c, c + 0.03f, a10 * 0.85f);
                    vert(b, mat, cam, x1, y, z1, x1 * su + ou, z1 * su + ov, c, c, c + 0.03f, a11 * 0.85f);
                    vert(b, mat, cam, x0, y, z1, x0 * su + ou, z1 * su + ov, c, c, c + 0.03f, a01 * 0.85f);
                }
            }
        }
        if (drew) BufferUploader.drawWithShader(b.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static float edge(WaterWeather.Shower s, float x, float z) {
        float d = (float) Math.hypot(x - s.x, z - s.z) / s.radius;
        return Mth.clamp((1 - d) * 2.2f, 0, 1);
    }

    private static void bind(Minecraft mc, ResourceLocation tex) {
        var t = mc.getTextureManager().getTexture(tex);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.bindTexture(t.getId());
        t.setFilter(true, false);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
    }

    private static void vert(BufferBuilder b, Matrix4f mat, Vec3 cam, float x, float y, float z, float u, float v, float r, float g, float bl, float a) {
        b.vertex(mat, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).uv(u, v).color(r, g, bl, a).endVertex();
    }
}
