package com.ronald.chromalight.mixin;

import com.ronald.chromalight.DynamicLights;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks near a moving light are drawn lit by it (the chunk builder asks here for every block's light). */
@Mixin(LevelRenderer.class)
public class LevelRendererLightMixin {
    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"), cancellable = true)
    private static void chromalight$moving(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int v = cir.getReturnValueI();
        int r = DynamicLights.raise(v, pos);
        if (r != v) cir.setReturnValue(r);
    }
}
