package com.ronald.rivergence.mixin;

import com.ronald.rivergence.RivergenceConfig;
import com.ronald.rivergence.plan.Planner;
import com.ronald.rivergence.world.CarveDensity;
import com.ronald.rivergence.world.RiverAccess;
import net.minecraft.core.HolderGetter;
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

/** Each overworld-like world gets a water planner; the chunks it builds see the terrain with the channels cut in. */
@Mixin(RandomState.class)
public abstract class RandomStateMixin implements RiverAccess {
    @Shadow @Final private NoiseRouter router;
    @Unique private Planner rivergence$planner;
    @Unique private NoiseRouter rivergence$carved;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rivergence$plan(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises, long seed, CallbackInfo ci) {
        if (!RivergenceConfig.enabled() || settings.seaLevel() != 63 || settings.noiseSettings().minY() > 0) return;
        int minY = settings.noiseSettings().minY(), maxY = minY + settings.noiseSettings().height();
        rivergence$planner = new Planner((RandomState) (Object) this, minY, maxY);
        NoiseRouter r = this.router;
        rivergence$carved = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(),
                r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                r.initialDensityWithoutJaggedness(), new CarveDensity(r.finalDensity(), rivergence$planner, false),
                r.veinToggle(), r.veinRidged(), r.veinGap());
    }

    @Override public Planner rivergence$planner() { return rivergence$planner; }
    @Override public NoiseRouter rivergence$router() { return rivergence$carved != null ? rivergence$carved : router; }
}
