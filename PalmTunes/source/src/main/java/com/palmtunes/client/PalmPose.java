package com.palmtunes.client;

import com.palmtunes.PalmConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Raises the local player's right arm into an open-palm pose while the panel is up. */
public final class PalmPose {
    private PalmPose() {
    }

    /** Called from the player model's {@code setupAnim}, after the vanilla pose has been computed. */
    public static void apply(ModelPart rightArm, ModelPart rightSleeve, LivingEntity entity, float ageInTicks) {
        Minecraft mc = Minecraft.getInstance();
        // The model is shared by every player, so always start from a normal-length arm.
        rightArm.yScale = 1f;
        rightSleeve.yScale = 1f;
        if (entity != mc.player || !PalmController.isVisible()) {
            return;
        }
        // The arm starts rising a little after the camera has left the eyes, and drops first when closing.
        float p = PalmController.eased(mc.getPartialTick(), 0.2f, 0.85f);
        if (p <= 0f) {
            return;
        }
        PalmConfig cfg = PalmConfig.CLIENT;
        // A very slight float so the hand does not look frozen.
        float hover = Mth.sin(ageInTicks * 0.09f) * 0.025f;

        rightArm.xRot = Mth.lerp(p, rightArm.xRot, cfg.armPitch.get().floatValue() + hover);
        rightArm.yRot = Mth.lerp(p, rightArm.yRot, cfg.armYaw.get().floatValue());
        rightArm.zRot = Mth.lerp(p, rightArm.zRot, cfg.armRoll.get().floatValue());
        float length = Mth.lerp(p, 1f, cfg.armLength.get().floatValue());
        rightArm.yScale = length;
        rightSleeve.copyFrom(rightArm);
        rightSleeve.yScale = length;
    }

    // ---- head -------------------------------------------------------------------------------------

    private static Vec3 panelWorld;
    private static float headYaw;    // degrees, relative to the body
    private static float headPitch;  // degrees, positive looks down

    /** Where the panel floats in the world; the head turns towards it. Set by the panel renderer each frame. */
    static void setPanelWorld(Vec3 world) {
        panelWorld = world;
    }

    /**
     * Shapes the head: hidden while the camera is still inside it (first person hides it for the same
     * reason), and turned to look at the panel while the palm is up.
     */
    public static void adjustHead(ModelPart head, ModelPart hat, LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player) {
            return;
        }
        boolean palm = PalmController.isOverriding();
        boolean cinematic = Cinematic.isOverriding();
        if (!palm && !cinematic) {
            return;
        }
        float pt = mc.getPartialTick();
        double distance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(entity.getEyePosition(pt));
        if (distance < 0.7) {
            head.visible = false;
            hat.visible = false;
            return;
        }
        if (!palm || panelWorld == null) {
            return;
        }
        float p = PalmController.eased(pt, 0.25f, 0.9f);
        if (p <= 0.001f) {
            return;
        }
        Vec3 d = panelWorld.subtract(entity.getEyePosition(pt));
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yawToPanel = (float) (Mth.atan2(-d.x, d.z) * (180.0 / Math.PI));
        float pitchToPanel = (float) (-(Mth.atan2(d.y, horizontal) * (180.0 / Math.PI)));
        float bodyYaw = Mth.rotLerp(pt, entity.yBodyRotO, entity.yBodyRot);
        float targetYaw = Mth.clamp(Mth.wrapDegrees(yawToPanel - bodyYaw), -75f, 75f);
        float targetPitch = Mth.clamp(pitchToPanel, -35f, 35f);
        // Glide, so the head follows the panel instead of snapping to it.
        headYaw += (targetYaw - headYaw) * 0.2f;
        headPitch += (targetPitch - headPitch) * 0.2f;
        head.yRot = Mth.lerp(p, head.yRot, headYaw * Mth.DEG_TO_RAD);
        head.xRot = Mth.lerp(p, head.xRot, headPitch * Mth.DEG_TO_RAD);
        hat.copyFrom(head);
    }
}
