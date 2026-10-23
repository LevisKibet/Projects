package com.ripplemod.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ripplemod.RippleMod;
import com.ripplemod.sim.RippleField;
import com.ripplemod.sim.RippleShading;
import com.ripplemod.sim.RippleWorld;
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

/** Draws every active ripple patch as a lightly displaced, shaded surface just above the water. */
@Mod.EventBusSubscriber(modid = RippleMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class RippleRenderer {
    private static final int N = RippleField.N;
    private static final float[] HEIGHTS = new float[N * N];
    private static final float[] SLOPES = new float[N * N];
    private static final int[] COLORS = new int[N * N];

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        RippleWorld world = RippleClient.world();
        if (world == null || world.fields().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        float partial = event.getPartialTick();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RippleRenderTypes.SURFACE);

        for (RippleField field : world.fields()) {
            double cx = field.originX + N * RippleField.CELL / 2.0;
            double cz = field.originZ + N * RippleField.CELL / 2.0;
            double dx = cx - cam.x;
            double dz = cz - cam.z;
            if (dx * dx + dz * dz > 96.0 * 96.0) {
                continue;
            }
            drawField(consumer, matrix, field, level, cam, partial, cx, cz);
        }
        buffers.endBatch(RippleRenderTypes.SURFACE);
    }

    private static void drawField(VertexConsumer consumer, Matrix4f matrix, RippleField field, ClientLevel level,
                                  Vec3 cam, float partial, double cx, double cz) {
        field.interpolate(partial, HEIGHTS);
        RippleShading.slopes(HEIGHTS, field.wet, field.fade, SLOPES);
        float brightness = brightness(level, cx, field.surfaceY, cz);

        for (int k = 0; k < SLOPES.length; k++) {
            float s = SLOPES[k];
            COLORS[k] = (s < 0.006F && s > -0.006F) ? 0 : RippleShading.color(s, brightness);
        }

        for (int j = 0; j < N - 1; j++) {
            for (int i = 0; i < N - 1; i++) {
                int k = j * N + i;
                int c00 = COLORS[k];
                int c10 = COLORS[k + 1];
                int c11 = COLORS[k + N + 1];
                int c01 = COLORS[k + N];
                if (((c00 | c10 | c11 | c01) >>> 24) < 4) {
                    continue;
                }
                vertex(consumer, matrix, field, cam, i, j, HEIGHTS[k], c00);
                vertex(consumer, matrix, field, cam, i + 1, j, HEIGHTS[k + 1], c10);
                vertex(consumer, matrix, field, cam, i + 1, j + 1, HEIGHTS[k + N + 1], c11);
                vertex(consumer, matrix, field, cam, i, j + 1, HEIGHTS[k + N], c01);
            }
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, RippleField field, Vec3 cam,
                               int i, int j, float height, int color) {
        float x = (float) (field.worldX(i) - cam.x);
        // Sits a hair above the water so it never z-fights, and rises with the wave crests.
        float y = (float) (field.surfaceY + 0.03 + Mth.clamp(height, -0.025F, 0.25F) - cam.y);
        float z = (float) (field.worldZ(j) - cam.z);
        consumer.vertex(matrix, x, y, z)
                .color((color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24)
                .endVertex();
    }

    /** Rough light level at the patch, so ripples are dim at night and in caves. */
    private static float brightness(ClientLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y + 0.1, z);
        int packed = LevelRenderer.getLightColor(level, pos);
        float sky = LightTexture.sky(packed) / 15F * level.getSkyDarken(1.0F);
        float block = LightTexture.block(packed) / 15F;
        return Mth.clamp(Math.max(sky, block), 0.22F, 1.0F);
    }
}
