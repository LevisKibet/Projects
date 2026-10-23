package com.ronald.freshpowder.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ronald.freshpowder.Config;
import com.ronald.freshpowder.FreshPowder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Footprints and paw prints pressed into the snow by anything walking near you: little flat shapes laid on the snow's
 * surface, following it as it's pressed down or builds up, fading after a while. Nothing is stored in the world.
 */
@Mod.EventBusSubscriber(modid = FreshPowder.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class Footprints {
    private Footprints() {}

    private static final class Print {
        final int bx, by, bz;
        final float x, z, cos, sin, scale;
        final boolean boot;
        final long born;
        float top = Float.NaN;                           // the surface height under it, kept up to date
        Print(int bx, int by, int bz, float x, float z, float yaw, float scale, boolean boot, long born) {
            this.bx = bx; this.by = by; this.bz = bz; this.x = x; this.z = z;
            this.cos = Mth.cos(yaw); this.sin = Mth.sin(yaw); this.scale = scale; this.boot = boot; this.born = born;
        }
    }

    private static final ArrayDeque<Print> PRINTS = new ArrayDeque<>();
    /** Per walker: last x, last z, distance since the last print, which foot is next. */
    private static final Map<Entity, double[]> WALKERS = new WeakHashMap<>();
    private static final double RANGE = 48;

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        if (!Config.FOOTPRINTS.get()) { PRINTS.clear(); return; }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        long now = level.getGameTime();
        for (Entity en : level.entitiesForRendering()) {
            if (!(en instanceof LivingEntity le) || en.isSpectator() || en.getBbWidth() < 0.3f) continue;
            if (en.distanceToSqr(cam) > RANGE * RANGE) continue;
            double[] w = WALKERS.computeIfAbsent(en, k -> new double[]{en.getX(), en.getZ(), 0, 0});
            double dx = en.getX() - w[0], dz = en.getZ() - w[1];
            w[0] = en.getX(); w[1] = en.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > 3 || !le.onGround()) { w[2] = 0; continue; }
            if (d < 1e-4) continue;
            boolean boot = en instanceof Player || (en.getBbHeight() > 1.4f && en.getBbWidth() < 0.8f);
            float scale = Mth.clamp(en.getBbWidth() / 0.6f, 0.6f, 1.8f);
            w[2] += d;
            if (w[2] < (boot ? 0.62 : 0.42) * scale) continue;
            w[2] = 0;
            w[3] = 1 - w[3];
            BlockPos p = en.blockPosition();
            BlockState s = level.getBlockState(p);
            if (!s.is(Blocks.SNOW)) {
                p = p.below();
                BlockState b = level.getBlockState(p);
                if (!b.is(Blocks.SNOW_BLOCK) && !b.is(Blocks.POWDER_SNOW) && !b.is(Blocks.SNOW)) continue;
            }
            float yaw = (float) Math.atan2(dz, dx);
            double side = (w[3] == 0 ? -1 : 1) * 0.13 * scale * (boot ? 1 : 0.8);
            float px = (float) (en.getX() - Math.sin(yaw) * side), pz = (float) (en.getZ() + Math.cos(yaw) * side);
            PRINTS.addLast(new Print(p.getX(), p.getY(), p.getZ(), px, pz, yaw, scale, boot, now));
            while (PRINTS.size() > Config.MAX_FOOTPRINTS.get()) PRINTS.removeFirst();
        }
        // keep a slice of the prints sitting on the (smooth) surface as it changes
        int k = 0, slice = (int) (now % 10);
        BlockPos.MutableBlockPos mm = new BlockPos.MutableBlockPos();
        for (Print p : PRINTS) {
            if (!Float.isNaN(p.top) && (k++ % 10) != slice) continue;
            BlockState s = level.getBlockState(mm.set(p.bx, p.by, p.bz));
            if (s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) < 8)
                p.top = p.by + SnowSurface.heightAt(level, mm.immutable(), s.getValue(SnowLayerBlock.LAYERS), p.x - p.bx, p.z - p.bz);
            else if (s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) p.top = p.by + 1;
            else p.top = Float.NEGATIVE_INFINITY;
        }
        long life = Config.FOOTPRINT_SECONDS.get() * 20L;
        while (!PRINTS.isEmpty() && now - PRINTS.peekFirst().born > life) PRINTS.removeFirst();
    }

    // the shapes, as rectangles (along, across from, along to, across to), in blocks for a full-size print
    private static final float[][] BOOT = {{-0.02f, -0.065f, 0.17f, 0.065f}, {-0.17f, -0.055f, -0.06f, 0.055f}};
    private static final float[][] PAW = {{-0.06f, -0.055f, 0.03f, 0.055f}, {0.05f, -0.075f, 0.09f, -0.035f}, {0.06f, -0.02f, 0.10f, 0.02f}, {0.05f, 0.035f, 0.09f, 0.075f}};

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || PRINTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 cam = e.getCamera().getPosition();
        Matrix4f mat = e.getPoseStack().last().pose();
        float t = (level.getGameTime() + e.getPartialTick());
        float life = Config.FOOTPRINT_SECONDS.get() * 20f;
        float dark = level.getSkyDarken(1.0f);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(PrintRenderType.PRINTS);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Iterator<Print> it = PRINTS.iterator();
        while (it.hasNext()) {
            Print p = it.next();
            double rx = p.x - cam.x, rz = p.z - cam.z;
            if (rx * rx + rz * rz > RANGE * RANGE) continue;
            if (p.top == Float.NEGATIVE_INFINITY) { it.remove(); continue; }   // melted or dug away
            if (Float.isNaN(p.top)) continue;
            double top = p.top;
            float age = (t - p.born) / life;
            float a = 0.38f * (age > 0.6f ? 1 - (age - 0.6f) / 0.4f : 1);
            if (a <= 0.01f) continue;
            int light = LevelRenderer.getLightColor(level, m.set(p.bx, (int) Math.floor(top + 0.05), p.bz));
            float bright = Mth.clamp(Math.max(LightTexture.sky(light) / 15f * dark, LightTexture.block(light) / 15f), 0.25f, 1f);
            int r = (int) (118 * bright), g = (int) (138 * bright), b = (int) (168 * bright), al = (int) (a * 255);
            float y = (float) (top + 0.004 - cam.y);
            for (float[] q : p.boot ? BOOT : PAW) quad(vc, mat, p, q, y, cam, r, g, b, al);
        }
        buffers.endBatch(PrintRenderType.PRINTS);
    }

    private static void quad(VertexConsumer vc, Matrix4f mat, Print p, float[] q, float y, Vec3 cam, int r, int g, int b, int a) {
        float s = p.scale;
        float[][] c = {{q[0], q[1]}, {q[2], q[1]}, {q[2], q[3]}, {q[0], q[3]}};
        for (float[] k : c) {
            float u = k[0] * s, v = k[1] * s;
            float wx = p.x + u * p.cos - v * p.sin, wz = p.z + u * p.sin + v * p.cos;
            vc.vertex(mat, (float) (wx - cam.x), y, (float) (wz - cam.z)).color(r, g, b, a).endVertex();
        }
    }
}
