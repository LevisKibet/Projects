package com.ronald.ancientpeaks_lore.light.mixin;

import com.ronald.ancientpeaks_lore.light.DynamicLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mobs and items near a moving light are lit by it too. */
@Mixin(EntityRenderer.class)
public class EntityRendererLightMixin {
    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
    private void logreslight$moving(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int dyn = Math.max(DynamicLights.levelAt(pos), com.ronald.ancientpeaks_lore.light.VoxelLight.extraAt(pos));
        if (dyn > cir.getReturnValueI()) cir.setReturnValue(dyn);
    }
}
