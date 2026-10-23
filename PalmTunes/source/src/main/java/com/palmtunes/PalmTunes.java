package com.palmtunes;

import com.mojang.logging.LogUtils;
import com.palmtunes.client.PalmClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * PalmTunes: a glass music panel hovering over your palm, wired to whatever your computer is playing.
 * Client-only; on a dedicated server this class loads and does nothing.
 */
@Mod(PalmTunes.MOD_ID)
public final class PalmTunes {
    public static final String MOD_ID = "palmtunes";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PalmTunes() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PalmConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            PalmClient.init();
        }
    }
}
