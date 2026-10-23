package com.ronald.chroma.light;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.ronald.chroma.light.mixin.PostChainAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Comparator;
import java.util.List;

/**
 * Draws the coloured light: one full-screen pass over the finished world (before the hand and HUD), reading the depth
 * buffer to know where each pixel is. The lights go to the shader in a tiny 64x2 float texture, nearest first, only
 * those in front of the camera, and only as many as the settings allow. No lights in view: no pass, no cost.
 */
public final class LightRenderer {
    private LightRenderer() {}
    static final int MAX = 64;
    private static final ResourceLocation CHAIN = new ResourceLocation(ChromaLight.MODID, "shaders/post/light.json");
    private static PostChain chain;
    private static boolean failed;
    private static int width = -1, height = -1, texture = -1;
    private static final FloatBuffer DATA = MemoryUtil.memAllocFloat(MAX * 2 * 4);

    static void render(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (true) return;   // retired in 0.4.0: blocks are lit in colour as they're built (ColorLight)
        if (!Settings.enabled() || failed || mc.level == null || ChromaLight.shaderPackInUse()) return;
        float partial = e.getPartialTick();
        Vec3 cam = e.getCamera().getPosition();
        Vector3f fwd = e.getCamera().getLookVector();

        int[] vo = null;
        List<Lights.Light> all = Settings.dynamic() || !Lights.TEST.isEmpty() ? Lights.moving(partial) : new java.util.ArrayList<>();
        // in front of the camera (or close enough to light what's in front), nearest first
        all.removeIf(l -> {
            double dx = l.x() - cam.x, dy = l.y() - cam.y, dz = l.z() - cam.z;
            return dx * fwd.x() + dy * fwd.y() + dz * fwd.z() < -l.radius();
        });
        if (all.isEmpty() && vo == null) return;
        all.sort(Comparator.comparingDouble(l -> (l.x() - cam.x) * (l.x() - cam.x) + (l.y() - cam.y) * (l.y() - cam.y) + (l.z() - cam.z) * (l.z() - cam.z)));
        int n = Math.min(all.size(), Math.min(MAX, Settings.maxLights()));
        if (!ensure(mc)) return;

        // pack: row 0 = camera-relative position + radius, row 1 = colour * intensity + kind (0 tint, 1 emit)
        float time = (mc.level.getGameTime() % 24000L + partial) / 20f;
        DATA.clear();
        for (int i = 0; i < MAX; i++) {
            if (i < n) { Lights.Light l = all.get(i); DATA.put((float) (l.x() - cam.x)).put((float) (l.y() - cam.y)).put((float) (l.z() - cam.z)).put(l.radius()); }
            else DATA.put(0).put(0).put(0).put(0);
        }
        for (int i = 0; i < MAX; i++) {
            if (i < n) {
                Lights.Light l = all.get(i);
                float k = l.intensity();
                if (l.flame()) {                                          // a gentle flicker, different for each flame
                    double ph = l.x() * 12.9898 + l.z() * 78.233 + l.y() * 3.1;
                    k *= 0.9f + 0.06f * (float) Math.sin(time * 9.0 + ph) + 0.04f * (float) Math.sin(time * 23.0 + ph * 1.7);
                }
                DATA.put(l.r() * k).put(l.g() * k).put(l.b() * k).put(l.emit() ? Math.max(l.level(), 6) : 15f);
            } else DATA.put(0).put(0).put(0).put(0);
        }
        DATA.flip();
        RenderSystem.bindTexture(texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, MAX, 2, GL11.GL_RGBA, GL11.GL_FLOAT, DATA);

        Matrix4f inv = new Matrix4f(e.getProjectionMatrix()).mul(e.getPoseStack().last().pose()).invert();
        for (PostPass pass : ((PostChainAccessor) chain).chroma$passes()) {
            pass.getEffect().safeGetUniform("InvViewProj").set(inv);
            pass.getEffect().safeGetUniform("LightCount").set((float) n);
            pass.getEffect().safeGetUniform("Strength").set(Settings.strength());
            pass.getEffect().safeGetUniform("HasVolume").set(vo != null ? 1f : 0f);
            if (vo != null) pass.getEffect().safeGetUniform("VolOffset").set((float) (cam.x - vo[0]), (float) (cam.y - vo[1]), (float) (cam.z - vo[2]));
            pass.getEffect().safeGetUniform("VolSize").set((float) VoxelLight.SX, (float) VoxelLight.SY, (float) VoxelLight.SZ);
            float bright = mc.level.dimensionType().hasSkyLight() ? mc.level.getSkyDarken(partial) : 1f;
            pass.getEffect().safeGetUniform("SkyDarken").set(Math.max(0f, Math.min(11f, (1f - (bright - 0.2f) / 0.8f) * 11f)));
        }
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        chain.process(partial);
        mc.getMainRenderTarget().bindWrite(true);
        RenderSystem.enableDepthTest();
    }

    private static boolean ensure(Minecraft mc) {
        var target = mc.getMainRenderTarget();
        try {
            if (texture < 0) {
                texture = GlStateManager._genTexture();
                RenderSystem.bindTexture(texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MAX, 2, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
            }
            if (chain == null) {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), target, CHAIN);
                ((PostChainAccessor) chain).chroma$passes().get(0).addAuxAsset("LightSampler", () -> texture, MAX, 2);
                width = -1;
            }
            if (width != target.width || height != target.height) {
                chain.resize(target.width, target.height);
                width = target.width; height = target.height;
            }
            return true;
        } catch (Exception ex) {
            ChromaLight.LOGGER.error("Chroma Light: couldn't load its shaders; coloured light is off", ex);
            failed = true;
            if (chain != null) { chain.close(); chain = null; }
            return false;
        }
    }

    static void reset() {
        if (chain != null) { chain.close(); chain = null; }
        VoxelLight.reset();
        LightColors.clearAuto();
        failed = false;
    }
}
