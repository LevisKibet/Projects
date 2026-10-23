package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.HighlandsDensity;
import com.ronald.ancientpeaks.world.HighlandsRegistry;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RandomState.class)
public abstract class RandomStateMixin implements HighlandsAccess {
    @Shadow @Final private NoiseRouter router;
    @Shadow @Final private Climate.Sampler sampler;
    @Unique private IslandSampler ancientpeaks$s;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ancientpeaks$find(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises, long seed, CallbackInfo ci) {
        final IslandSampler[] found = new IslandSampler[1];
        this.router.finalDensity().mapAll(df -> {
            if (df instanceof HighlandsDensity h && h.sampler() != null && found[0] == null) found[0] = h.sampler();
            return df;
        });
        if (found[0] == null) return;
        IslandSampler s = found[0];
        DensityFunction cont = this.router.continents();
        s.setContinents((x, z) -> cont.compute(new DensityFunction.SinglePointContext(x, 0, z)));
        this.ancientpeaks$s = s;
        ((HighlandsAccess) (Object) this.sampler).ancientpeaks$set(s);
        HighlandsRegistry.put(this.router, s);
    }

    @Override public IslandSampler ancientpeaks$sampler() { return ancientpeaks$s; }
    @Override public void ancientpeaks$set(IslandSampler s) { ancientpeaks$s = s; }
}
