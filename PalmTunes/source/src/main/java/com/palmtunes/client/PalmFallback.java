package com.palmtunes.client;

import com.palmtunes.PalmConfig;
import com.palmtunes.PalmTunes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the panel from the world instead of from inside the player renderer, for when another mod has
 * replaced that renderer (Epic Fight in battle mode, for example) so the render layer never runs.
 * The palm is then worked out from the player's body: the vanilla arm joint, posed the way PalmTunes poses it.
 */
final class PalmFallback {
    private PalmFallback() {
    }

    static void logCompat() {
        for (String id : new String[]{"epicfight", "entity_model_features", "entity_texture_features", "playeranimator",
                "bettercombat", "firstperson", "skinlayers3d"}) {
            if (ModList.get().isLoaded(id)) {
                PalmTunes.LOGGER.info("PalmTunes: detected {}; the panel falls back to a body-anchored position if the player renderer is replaced", id);
            }
        }
    }

    static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !PalmController.isVisible()) {
            return;
        }
        if (mc.screen != null && !(mc.screen instanceof PalmScreen)) {
            return;
        }
        boolean forced = PalmConfig.CLIENT.bodyAnchor.get();
        // The normal render layer drew this frame: nothing to do.
        if (!forced && System.nanoTime() - PalmLayer.lastDrawNanos < 100_000_000L) {
            return;
        }
        float pt = e.getPartialTick();
        float anim = PalmController.eased(pt, 0.55f, 1.0f);
        if (anim <= 0.02f) {
            PalmInteraction.invalidateRect();
            return;
        }

        // Pose of the arm, as PalmPose sets it.
        PalmConfig cfg = PalmConfig.CLIENT;
        float p = PalmController.eased(pt, 0.2f, 0.85f);
        float xr = Mth.lerp(p, 0f, cfg.armPitch.get().floatValue());
        float yr = Mth.lerp(p, 0f, cfg.armYaw.get().floatValue());
        float zr = Mth.lerp(p, 0f, cfg.armRoll.get().floatValue());
        float len = Mth.lerp(p, 1f, cfg.armLength.get().floatValue());

        Vec3 feet = player.getPosition(pt);
        Vec3 cam = e.getCamera().getPosition();
        float bodyYaw = Mth.rotLerp(pt, player.yBodyRotO, player.yBodyRot);
        Matrix4f m = new Matrix4f()
                .translate((float) (feet.x - cam.x), (float) (feet.y - cam.y), (float) (feet.z - cam.z))
                .rotateY((180f - bodyYaw) * Mth.DEG_TO_RAD)
                .scale(-1f, -1f, 1f)
                .translate(0f, -1.501f, 0f)
                .translate(-5f / 16f, 2f / 16f, 0f)
                .rotateZYX(zr, yr, xr)
                .scale(1f, len, 1f);
        // m now maps arm-local model units to camera-relative world space.
        Vector3f palmWorld = m.transformPosition(new Vector3f(1f / 16f, 8f / 16f, 0f));
        // ...and the level's view matrix takes it the rest of the way to camera space, as the layer sees it.
        Vector3f palm = e.getPoseStack().last().pose().transformPosition(palmWorld);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PalmPanelRenderer.render(buffers, palm, anim);
        buffers.endBatch();
    }
}
