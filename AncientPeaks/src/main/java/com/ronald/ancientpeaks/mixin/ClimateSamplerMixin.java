package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Climate.Sampler.class)
public abstract class ClimateSamplerMixin implements HighlandsAccess {
    @Unique private IslandSampler ancientpeaks$s;
    @Override public IslandSampler ancientpeaks$sampler() { return ancientpeaks$s; }
    @Override public void ancientpeaks$set(IslandSampler s) { ancientpeaks$s = s; }
}
