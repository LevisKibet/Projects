package com.ronald.rivergence;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/** Rivergence: the overworld's water - streams, rivers, lakes, waterfalls and wetlands - following the land, suited to each biome. */
@Mod(Rivergence.MODID)
public class Rivergence {
    public static final String MODID = "rivergence";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Rivergence() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, RivergenceConfig.SPEC);
        com.ronald.rivergence.world.RemoveIfEnabled.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        if (FMLEnvironment.dist.isClient()) RivergenceSettingsScreen.register();
        com.ronald.rivergence.world.FaunaSpawner.init();
        com.ronald.rivergence.dev.DevProbe.init();
    }
}
