package com.palmtunes.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.joml.Vector3f;

/**
 * Runs inside the player renderer, after the arm has been posed, so we can ask the arm's own
 * transform where the palm ended up on screen this frame.
 */
public final class PalmLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    /** Nano time of the last frame this layer drew the panel; the fallback checks it. */
    static volatile long lastDrawNanos;

    private final boolean slimArms;

    public PalmLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent, boolean slimArms) {
        super(parent);
        this.slimArms = slimArms;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player || !PalmController.isVisible() || com.palmtunes.PalmConfig.CLIENT.bodyAnchor.get()) {
            return;
        }
        // Inventory previews and other screens reuse the player renderer with a different projection.
        if (mc.screen != null && !(mc.screen instanceof PalmScreen)) {
            return;
        }
        // The panel fades in last, once the hand is up, and leaves first.
        float anim = PalmController.eased(partialTick, 0.55f, 1.0f);
        if (anim <= 0.02f) {
            PalmInteraction.invalidateRect();
            return;
        }

        // The arm's origin is the shoulder; the palm's inner surface is 1px (0.5px on slim arms)
        // along +x and 8px down the arm. translateAndRotate works in 1/16 block units.
        poseStack.pushPose();
        getParentModel().rightArm.translateAndRotate(poseStack);
        float surface = slimArms ? 0.5f : 1.0f;
        Vector3f palmInCameraSpace = new Vector3f(surface / 16f, 8f / 16f, 0f).mulPosition(poseStack.last().pose());
        poseStack.popPose();

        lastDrawNanos = System.nanoTime();
        PalmPanelRenderer.render(buffers, palmInCameraSpace, anim);
    }
}
