package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.HighlandsRegistry;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    @Inject(method = "cachedClimateSampler", at = @At("RETURN"))
    private void ancientpeaks$tag(NoiseRouter router, List<Climate.ParameterPoint> spawnTarget, CallbackInfoReturnable<Climate.Sampler> cir) {
        var s = HighlandsRegistry.get(router);
        if (s != null) ((HighlandsAccess) (Object) cir.getReturnValue()).ancientpeaks$set(s);
    }
}
