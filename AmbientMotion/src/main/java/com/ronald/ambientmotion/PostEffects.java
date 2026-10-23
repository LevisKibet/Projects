package com.ronald.ambientmotion;

import com.mojang.blaze3d.systems.RenderSystem;
import com.ronald.ambientmotion.mixin.GameRendererAccessor;
import com.ronald.ambientmotion.mixin.PostChainAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Motion blur and depth of field on the finished world image (before the hand and the HUD, so those stay crisp):
 * assets/ambientmotion/shaders/post/ambient.json.
 *
 * Motion blur follows how real-time engines keep camera blur comfortable: it comes from the camera's turning only
 * (walking and view bobbing never blur), the turn rate is smoothed over a few frames (mouse input arrives unevenly,
 * which made the old blur shudder), it is scaled to a fixed shutter time so it looks the same at 30 or 240 fps, and
 * slow turns get none at all - it fades in only as you turn quickly.
 *
 * Cost: nothing at all on frames where neither effect is showing (most of them). Motion blur takes 4-16 samples a
 * pixel, only while you turn fast. Depth of field blurs at half resolution, then merges at full size.
 *
 * Depth of field focuses on what the crosshair rests on, and only when that is right next to you: the background
 * softens over a moment, and eases back when you look away or step back.
 */
public final class PostEffects {
    private PostEffects() {}

    private static final ResourceLocation CHAIN = new ResourceLocation(AmbientMotion.MODID, "shaders/post/ambient.json");
    /** Shutter time at full strength: a 180-degree shutter at 60 fps. */
    private static final float SHUTTER = 1f / 120f;
    /** Turn rates (degrees per second) below which there is no blur, and above which it is fully in. */
    private static final float CALM = 40f, QUICK = 220f;

    private static PostChain chain;
    private static boolean failed;
    private static int width = -1, height = -1;

    private static long lastNanos;
    private static float lastYaw, lastPitch, yawRate, pitchRate;
    private static boolean haveLast;
    private static float dofAmount, focus = 1f;

    static void render(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = e.getCamera();
        long now = System.nanoTime();
        float dt = haveLast ? Math.min(0.1f, Math.max(1e-4f, (now - lastNanos) / 1e9f)) : 1 / 60f;
        lastNanos = now;

        // ---- camera turn rate, smoothed (time constant ~45 ms)
        float yaw = cam.getYRot(), pitch = cam.getXRot();
        float dyaw = haveLast ? Mth.wrapDegrees(yaw - lastYaw) : 0, dpitch = haveLast ? pitch - lastPitch : 0;
        lastYaw = yaw; lastPitch = pitch; haveLast = true;
        float k = 1f - (float) Math.exp(-dt / 0.045f);
        yawRate += (dyaw / dt - yawRate) * k;
        pitchRate += (dpitch / dt - pitchRate) * k;

        // ---- focus: what the crosshair rests on, if it is close
        Settings.DofMode mode = Settings.dofMode();
        float target = 0;
        if (mode == Settings.DofMode.CLOSE_UP && mc.player != null) {
            double d = focusDistance(mc, cam);
            if (d > 0 && d - 0.3 <= Settings.focusRange()) {                // measured from your body, not your eyes
                target = 1;
                focus = dofAmount < 0.02f ? (float) d : focus + ((float) d - focus) * (1f - (float) Math.exp(-dt / 0.12f));
            }
        } else if (mode == Settings.DofMode.TILT_SHIFT) target = 1;
        dofAmount += (target - dofAmount) * (1f - (float) Math.exp(-dt / (target > dofAmount ? 0.22f : 0.30f)));

        float blur = Settings.motionBlur();
        boolean dofOn = mode != Settings.DofMode.OFF && Settings.dofStrength() > 0 && dofAmount > 0.002f;
        float speed = (float) Math.sqrt(yawRate * yawRate + pitchRate * pitchRate);
        float ease = blur > 0 ? smoothstep(CALM, QUICK, speed) : 0f;
        boolean motionOn = ease > 0.001f;
        // nothing to do this frame (not turning fast, nothing close in focus): no passes at all, no cost
        if ((!motionOn && !dofOn) || failed || mc.level == null || AmbientMotion.shaderPackInUse()) return;
        if (!ensure(mc)) return;

        // ---- the two cameras for the blur: now, and one shutter-time ago (rotation only)
        float shutter = SHUTTER * blur * 2f;                                 // 100% = a 360-degree shutter
        float fov = (float) ((GameRendererAccessor) mc.gameRenderer).ambientmotion$fov(cam, e.getPartialTick(), true);
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 1024f);
        Matrix4f now4 = new Matrix4f(proj).mul(view(pitch, yaw));
        Matrix4f prev4 = new Matrix4f(proj).mul(view(pitch - pitchRate * shutter, yaw - yawRate * shutter));
        Matrix4f inv = new Matrix4f(now4).invert();

        float scale = mc.getWindow().getHeight() / 1080f;
        for (PostPass pass : ((PostChainAccessor) chain).ambientmotion$passes()) {
            EffectInstance fx = pass.getEffect();
            fx.safeGetUniform("InvViewProj").set(inv);
            fx.safeGetUniform("PrevViewProj").set(prev4);
            fx.safeGetUniform("MotionAmount").set(ease);
            fx.safeGetUniform("DofMode").set(dofOn ? (float) mode.ordinal() : 0f);
            fx.safeGetUniform("DofAmount").set(dofAmount);
            fx.safeGetUniform("FocusDistance").set(Math.max(0.15f, focus));
            fx.safeGetUniform("MaxBlur").set(Math.min(24f, (4f + 14f * Settings.dofStrength()) * Math.max(0.6f, scale)));
            fx.safeGetUniform("NearFar").set(0.05f, mc.gameRenderer.getDepthFar());
        }

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        // only the passes this frame needs (see post/ambient.json): blur at half size, merge, motion, copy back
        var passes = ((PostChainAccessor) chain).ambientmotion$passes();
        int[] order = dofOn && motionOn ? new int[]{0, 1, 2, 3} : dofOn ? new int[]{0, 1, 5} : new int[]{4, 3};
        for (int i : order) passes.get(i).process(e.getPartialTick());
        mc.getMainRenderTarget().bindWrite(true);
        RenderSystem.enableDepthTest();
    }

    private static Matrix4f view(float pitch, float yaw) {
        return new Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw + 180f));
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    /** Distance from the eyes to the point the crosshair rests on (a block face, or the entity's body), or -1. */
    private static double focusDistance(Minecraft mc, Camera cam) {
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return -1;
        Vec3 eye = cam.getPosition();
        if (hit instanceof BlockHitResult b) return b.getLocation().distanceTo(eye);
        if (hit instanceof EntityHitResult eh) {
            Entity en = eh.getEntity();
            Vec3 look = Vec3.directionFromRotation(cam.getXRot(), cam.getYRot());
            AABB box = en.getBoundingBox().inflate(en.getPickRadius());
            return box.clip(eye, eye.add(look.scale(8))).map(p -> p.distanceTo(eye)).orElse((double) en.distanceTo(mc.player));
        }
        return -1;
    }

    private static boolean ensure(Minecraft mc) {
        var target = mc.getMainRenderTarget();
        try {
            if (chain == null) { chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), target, CHAIN); width = -1; }
            if (width != target.width || height != target.height) {
                chain.resize(target.width, target.height);
                width = target.width; height = target.height;
                // the depth of field blur works at half size, smoothly upscaled
                int hw = Math.max(1, width / 2), hh = Math.max(1, height / 2);
                var half = chain.getTempTarget("half");
                half.resize(hw, hh, net.minecraft.client.Minecraft.ON_OSX);
                half.setFilterMode(org.lwjgl.opengl.GL11.GL_LINEAR);
                ((PostChainAccessor) chain).ambientmotion$passes().get(0).setOrthoMatrix(new Matrix4f().setOrtho(0f, hw, 0f, hh, 0.1f, 1000f));
            }
            return true;
        } catch (Exception ex) {
            AmbientMotion.LOGGER.error("Ambient Motion: couldn't load its post-processing shaders; motion blur and depth of field are off", ex);
            failed = true;
            if (chain != null) { chain.close(); chain = null; }
            return false;
        }
    }

    /** Resource packs reloaded: build the chain again next frame. */
    static void reset() {
        if (chain != null) { chain.close(); chain = null; }
        failed = false;
    }
}
