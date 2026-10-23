package com.ronald.chroma.light.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ronald.chroma.light.ColorLight;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every block face the world is built from gets its corners coloured by the light falling on them. */
@Mixin(ModelBlockRenderer.class)
public class ModelBlockRendererMixin {
    @Inject(method = "putQuadData", at = @At("HEAD"))
    private void chroma$pos(BlockAndTintGetter level, BlockState state, BlockPos pos, VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad,
                                 float b0, float b1, float b2, float b3, int l0, int l1, int l2, int l3, int overlay, CallbackInfo ci) {
        ColorLight.at(pos);
    }

    @Redirect(method = "putQuadData", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFF[IIZ)V"), require = 0)
    private void chroma$tint(VertexConsumer out, PoseStack.Pose pose, BakedQuad quad, float[] brightness, float r, float g, float b, int[] lights, int overlay, boolean readExisting) {
        ColorLight.put(out, pose, quad, brightness, r, g, b, lights, overlay, readExisting);
    }
}
