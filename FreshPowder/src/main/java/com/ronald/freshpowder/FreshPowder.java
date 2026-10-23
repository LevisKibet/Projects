package com.ronald.freshpowder;

import com.ronald.freshpowder.world.DriftFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

@Mod(FreshPowder.MODID)
public final class FreshPowder {
    public static final String MODID = "freshpowder";

    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> DRIFTS = FEATURES.register("drifts", () -> new DriftFeature(NoneFeatureConfiguration.CODEC));

    public FreshPowder() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        FEATURES.register(bus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
    }
}
