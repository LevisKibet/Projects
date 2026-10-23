package com.ronald.rivergence.mixin;

import com.ronald.rivergence.world.RiverAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Chunks (and column height checks) are built from the terrain with the water's channels cut in. */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/RandomState;router()Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter rivergence$carved(RandomState rs) {
        return ((RiverAccess) (Object) rs).rivergence$router();
    }
}
