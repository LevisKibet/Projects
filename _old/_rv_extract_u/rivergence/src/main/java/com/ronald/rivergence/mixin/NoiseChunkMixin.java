package com.ronald.rivergence.mixin;

import com.ronald.rivergence.plan.Planner;
import com.ronald.rivergence.world.CarveDensity;
import com.ronald.rivergence.world.RiverAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Chunks (and column height checks) are built from the terrain with the water's channels cut in. The beds are cut
 * once more after structures have shaped the ground round them, so a village's foundations never dam a river.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/RandomState;router()Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter rivergence$carved(RandomState rs) {
        RiverAccess a = (RiverAccess) (Object) rs;
        CarveDensity.SETTING_UP.set(a.rivergence$planner());
        return a.rivergence$router();
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunctions;cacheAllInCell(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;"), index = 0)
    private DensityFunction rivergence$beds(DensityFunction withStructures) {
        Planner p = CarveDensity.SETTING_UP.get();
        CarveDensity.SETTING_UP.remove();
        return p == null ? withStructures : new CarveDensity(withStructures, p, true);
    }
}
