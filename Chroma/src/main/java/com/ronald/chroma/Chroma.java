package com.ronald.chroma;

import com.mojang.logging.LogUtils;
import com.ronald.chroma.content.ChromaContent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Chroma: coloured light (moved here from Chronicles of Logres so it works on its own), and lights to show it off -
 * LED strips and string lights laid along walls and bent into curves with the Light Shaper.
 */
@Mod(Chroma.MODID)
public class Chroma {
    public static final String MODID = "chroma";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Chroma() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ChromaContent.register(bus);
        ChromaNet.register();
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> ChromaNet.forget(e.getEntity().getUUID()));
        if (FMLEnvironment.dist.isClient()) {
            com.ronald.chroma.light.ChromaLight.init(bus);
            com.ronald.chroma.client.ChromaClient.init(bus);
        }
    }
}
