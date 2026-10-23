package com.palmtunes.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.palmtunes.PalmConfig;
import com.palmtunes.PalmTunes;
import com.palmtunes.mixin.GameRendererAccessor;
import com.palmtunes.mixin.PostChainAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Motion blur and depth of field for the cinematic's single-subject shots. The approach (and shaders) come from
 * the author's AmbientMotion mod: a private post chain run by hand after the world is drawn, so the HUD stays crisp.
 */
final class CinematicFx {
    private static final ResourceLocation CHAIN = new ResourceLocation(PalmTunes.MOD_ID, "shaders/post/cinematic.json");
    private static final float SHUTTER = 1f / 120f;

    private static PostChain chain;
    private static boolean failed;
    private static int width = -1;
    private static int height = -1;

    private static long lastNanos;
    private static float lastYaw;
    private static float lastPitch;
    private static float yawRate;
    private static float pitchRate;
    private static boolean haveLast;

    private CinematicFx() {
    }

    static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Camera cam = e.getCamera();
        long now = System.nanoTime();
        float dt = haveLast ? Math.min(0.1f, Math.max(1e-4f, (now - lastNanos) / 1e9f)) : 1 / 60f;
        lastNanos = now;
        float yaw = cam.getYRot();
        float pitch = cam.getXRot();
        float dyaw = haveLast ? Mth.wrapDegrees(yaw - lastYaw) : 0;
        float dpitch = haveLast ? pitch - lastPitch : 0;
        lastYaw = yaw;
        lastPitch = pitch;
        haveLast = true;
        float k = 1f - (float) Math.exp(-dt / 0.06f);
        yawRate += (dyaw / dt - yawRate) * k;
        pitchRate += (dpitch / dt - pitchRate) * k;

        PalmConfig cfg = PalmConfig.CLIENT;
        float focus = Cinematic.isOverriding() ? Cinematic.focusAmount() : 0f;
        float strength = cfg.cinematicMotionBlur.get() ? cfg.cinematicBlurStrength.get().floatValue() : 0f;
        boolean motionOn = strength > 0f && focus > 0.01f;
        boolean dofOn = cfg.cinematicDepthOfField.get() && focus > 0.01f;
        if ((!motionOn && !dofOn) || failed || mc.level == null || shaderPackInUse() || !ensure(mc)) {
            return;
        }

        // Rotation-only cameras: now, and one (lengthened) shutter-time ago.
        float shutter = SHUTTER * 8f * strength;
        float fov = (float) ((GameRendererAccessor) mc.gameRenderer).palmtunes$fov(cam, e.getPartialTick(), true);
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 1024f);
        Matrix4f now4 = new Matrix4f(proj).mul(view(pitch, yaw));
        Matrix4f prev4 = new Matrix4f(proj).mul(view(pitch - pitchRate * shutter, yaw - yawRate * shutter));
        Matrix4f inv = new Matrix4f(now4).invert();

        float scale = mc.getWindow().getHeight() / 1080f;
        float motion = motionOn ? focus : 0f;
        var passes = ((PostChainAccessor) chain).palmtunes$passes();
        for (PostPass pass : passes) {
            EffectInstance fx = pass.getEffect();
            fx.safeGetUniform("InvViewProj").set(inv);
            fx.safeGetUniform("PrevViewProj").set(prev4);
            fx.safeGetUniform("MotionAmount").set(motion);
            fx.safeGetUniform("FocusMask").set(1f);
            fx.safeGetUniform("DofMode").set(dofOn ? 1f : 0f);
            fx.safeGetUniform("DofAmount").set(focus);
            fx.safeGetUniform("FocusDistance").set(Math.max(0.15f, Cinematic.focusDistance()));
            fx.safeGetUniform("MaxBlur").set(7f);
            fx.safeGetUniform("NearFar").set(0.05f, mc.gameRenderer.getDepthFar());
        }

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        int[] order = dofOn && motionOn ? new int[]{0, 1, 2, 3} : dofOn ? new int[]{0, 1, 5} : new int[]{4, 3};
        for (int i : order) {
            passes.get(i).process(e.getPartialTick());
        }
        mc.getMainRenderTarget().bindWrite(true);
        RenderSystem.enableDepthTest();
    }

    private static Matrix4f view(float pitch, float yaw) {
        return new Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw + 180f));
    }

    private static boolean ensure(Minecraft mc) {
        var target = mc.getMainRenderTarget();
        try {
            if (chain == null) {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), target, CHAIN);
                width = -1;
            }
            if (width != target.width || height != target.height) {
                chain.resize(target.width, target.height);
                width = target.width;
                height = target.height;
                int hw = Math.max(1, width / 2);
                int hh = Math.max(1, height / 2);
                var half = chain.getTempTarget("half");
                half.resize(hw, hh, Minecraft.ON_OSX);
                half.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
                ((PostChainAccessor) chain).palmtunes$passes().get(0)
                        .setOrthoMatrix(new Matrix4f().setOrtho(0f, hw, 0f, hh, 0.1f, 1000f));
            }
            return true;
        } catch (Exception ex) {
            PalmTunes.LOGGER.error("PalmTunes: couldn't load its post-processing shaders; cinematic blur is off", ex);
            failed = true;
            if (chain != null) {
                chain.close();
                chain = null;
            }
            return false;
        }
    }

    static void reset() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
        failed = false;
    }

    private static boolean irisLooked;
    private static Object irisApi;
    private static java.lang.reflect.Method irisInUse;

    private static boolean shaderPackInUse() {
        try {
            if (!irisLooked) {
                irisLooked = true;
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisInUse = api.getMethod("isShaderPackInUse");
            }
            return irisInUse != null && (Boolean) irisInUse.invoke(irisApi);
        } catch (Throwable t) {
            irisInUse = null;
            return false;
        }
    }
}
