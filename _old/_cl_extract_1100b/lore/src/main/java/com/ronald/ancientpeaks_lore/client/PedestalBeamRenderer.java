package com.ronald.ancientpeaks_lore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ronald.ancientpeaks_lore.PedestalBlockEntity;
import com.ronald.ancientpeaks_lore.SwordInStoneBlock;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;

/**
 * The beam over the sword stone once Vortigern has fallen there: a pale, faintly golden beacon beam straight up into
 * the sky, drawn the way a beacon's is, so it can be seen from far off at night. Dark while he walks again.
 */
public class PedestalBeamRenderer implements BlockEntityRenderer<PedestalBlockEntity> {
    private static final float[] COLOUR = {0.86f, 0.9f, 1.0f};

    public PedestalBeamRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(PedestalBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var st = be.getBlockState();
        if (!st.hasProperty(SwordInStoneBlock.BEAM) || !st.getValue(SwordInStoneBlock.BEAM) || st.getValue(SwordInStoneBlock.PULLED) || be.getLevel() == null) return;
        BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, partial, 1.0f, be.getLevel().getGameTime(), 1, 1024, COLOUR, 0.2f, 0.25f);
    }

    @Override public boolean shouldRenderOffScreen(PedestalBlockEntity be) { return true; }
    @Override public int getViewDistance() { return 512; }
    @Override public boolean shouldRender(PedestalBlockEntity be, Vec3 cam) {
        return Vec3.atCenterOf(be.getBlockPos()).multiply(1, 0, 1).closerThan(cam.multiply(1, 0, 1), getViewDistance());
    }
}
