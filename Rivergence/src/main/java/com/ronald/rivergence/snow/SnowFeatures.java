package com.ronald.rivergence.snow;

import com.ronald.rivergence.Rivergence;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class SnowFeatures {
    private SnowFeatures() {}
    public static final DeferredRegister<Feature<?>> REG = DeferredRegister.create(Registries.FEATURE, Rivergence.MODID);
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> DRIFTS = REG.register("snow_drifts", () -> new DriftFeature(NoneFeatureConfiguration.CODEC));
}
