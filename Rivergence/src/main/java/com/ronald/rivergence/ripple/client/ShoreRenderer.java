package com.ronald.rivergence.ripple.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ronald.rivergence.ripple.RippleConfig;

import com.ronald.rivergence.ripple.sim.ShoreMap;
import com.ronald.rivergence.ripple.sim.ShoreShading;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/** Paints wet sand and washing foam over the top of beach blocks. */
@Mod.EventBusSubscriber(modid = com.ronald.rivergence.Rivergence.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ShoreRenderer {
    private static final int CELLS = 8;                  // pixels per block side in the cached data
    private static final int PER_COLUMN = CELLS * CELLS * ShoreShading.STATICS;

    // Position-only random values, kept so they are only worked out once per column.
    private static final Long2ObjectOpenHashMap<float[]> STATICS = new Long2ObjectOpenHashMap<>();

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        ShoreMap map = ShoreClient.map();
        if (map == null || map.wetColumns.length == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        if (STATICS.size() > 60000) {
            STATICS.clear();
        }

        Vec3 cam = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        float seconds = (level.getGameTime() + event.getPartialTick()) / 20F;
        double range = ShoreClient.reach();
        double rangeSqr = range * range;

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RippleRenderTypes.SURFACE);
        BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
        float fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        float fogEnd = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        float skyDarken = level.getSkyDarken(1.0F);

        for (int k : map.wetColumns) {
            int x = map.originX + k % map.size;
            int z = map.originZ + k / map.size;
            double dx = x + 0.5 - cam.x;
            double dz = z + 0.5 - cam.z;
            if (dx * dx + dz * dz > rangeSqr) {
                continue;
            }
            // Nothing to see once the sand is too far from the water.
            if (map.distanceAt(x + 0.5, z + 0.5) > 5.3F) {
                continue;
            }
            int top = map.top[k];
            int step = map.stepAt(x + 0.5, z + 0.5);
            // fade into the distance fog as the land under it does
            double far = Math.sqrt(dx * dx + dz * dz + (map.top[k] + 1 - cam.y) * (map.top[k] + 1 - cam.y));
            float fade = fogStart >= fogEnd ? 1F : 1F - (float) Mth.clamp((far - fogStart) / (fogEnd - fogStart), 0, 1);
            fade = fade * fade * (3 - 2 * fade);
            if (fade < 0.02F) {
                continue;
            }
            boolean glossy = map.kind[k] == ShoreMap.GLOSSY;
            lightPos.set(x, top + 1, z);
            int packed = LevelRenderer.getLightColor(level, lightPos);
            float sky = LightTexture.sky(packed) / 15F * skyDarken;
            float block = LightTexture.block(packed) / 15F;
            float brightness = Mth.clamp(Math.max(sky, block), 0.25F, 1.0F);

            // Fine pixels close up, chunkier ones further away.
            double near = dx * dx + dz * dz;
            int sub = near < 144.0 ? 8 : near < 576.0 ? 4 : near < 4096.0 ? 2 : 1;
            int stride = CELLS / sub;
            float[] statics = statics(x, z);
            float y = (float) (top + 1.0 + 0.012 - cam.y);

            for (int b = 0; b < sub; b++) {
                for (int a = 0; a < sub; a++) {
                    double wx = x + (a + 0.5) / sub;
                    double wz = z + (b + 0.5) / sub;
                    float d = map.distanceAt(wx, wz);
                    int off = ((b * stride) * CELLS + a * stride) * ShoreShading.STATICS;
                    int c = glossy
                            ? ShoreShading.colorGlossy(step, d, statics, off, seconds, brightness)
                            : ShoreShading.color(step, d, statics, off, seconds, brightness);
                    if (fade < 1F) c = (c & 0xFFFFFF) | ((int) ((c >>> 24) * fade) << 24);
                    if ((c >>> 24) < 4) {
                        continue;
                    }
                    float x0 = (float) (x + a / (double) sub - cam.x);
                    float x1 = (float) (x + (a + 1) / (double) sub - cam.x);
                    float z0 = (float) (z + b / (double) sub - cam.z);
                    float z1 = (float) (z + (b + 1) / (double) sub - cam.z);
                    vertex(consumer, matrix, x0, y, z0, c);
                    vertex(consumer, matrix, x1, y, z0, c);
                    vertex(consumer, matrix, x1, y, z1, c);
                    vertex(consumer, matrix, x0, y, z1, c);
                }
            }
        }
        buffers.endBatch(RippleRenderTypes.SURFACE);
    }

    private static float[] statics(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        float[] st = STATICS.get(key);
        if (st == null) {
            st = new float[PER_COLUMN];
            for (int b = 0; b < CELLS; b++) {
                for (int a = 0; a < CELLS; a++) {
                    ShoreShading.fillStatics(x, z, x + (a + 0.5) / CELLS, z + (b + 0.5) / CELLS, st,
                            (b * CELLS + a) * ShoreShading.STATICS);
                }
            }
            STATICS.put(key, st);
        }
        return st;
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, int color) {
        consumer.vertex(matrix, x, y, z)
                .color((color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24)
                .endVertex();
    }
}
