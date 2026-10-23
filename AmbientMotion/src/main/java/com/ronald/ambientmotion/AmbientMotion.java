package com.ronald.ambientmotion;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Ambient Motion: camera motion blur and depth of field for vanilla Minecraft. Client only; no other mods needed.
 * Stands aside while an Oculus shader pack is in use (the pack draws everything its own way).
 */
@Mod(AmbientMotion.MODID)
public class AmbientMotion {
    public static final String MODID = "ambientmotion";
    public static final Logger LOGGER = LogUtils.getLogger();
    static final KeyMapping OPEN_SETTINGS = new KeyMapping("key.ambientmotion.settings", InputConstants.UNKNOWN.getValue(), "key.categories.ambientmotion");

    public AmbientMotion() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Settings.SPEC);
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new SettingsScreen(parent)));
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN_SETTINGS));
        FMLJavaModLoadingContext.get().getModEventBus().addListener((net.minecraftforge.client.event.RegisterClientReloadListenersEvent e) ->
                e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> PostEffects.reset()));
        MinecraftForge.EVENT_BUS.addListener(AmbientMotion::onRenderStage);
        MinecraftForge.EVENT_BUS.addListener(AmbientMotion::onClientTick);
    }

    private static void onRenderStage(RenderLevelStageEvent e) {
        if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) PostEffects.render(e);
    }

    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_SETTINGS.consumeClick()) if (mc.screen == null) mc.setScreen(new SettingsScreen(null));
    }

    /** Is an Oculus/Iris shader pack drawing the world right now? (looked up by name: no dependency) */
    private static Object irisApi;
    private static java.lang.reflect.Method irisInUse;
    private static boolean irisLooked;
    static boolean shaderPackInUse() {
        try {
            if (!irisLooked) {
                irisLooked = true;
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisInUse = api.getMethod("isShaderPackInUse");
            }
            return irisInUse != null && (Boolean) irisInUse.invoke(irisApi);
        } catch (Throwable t) {
            irisInUse = null;
            return false;
        }
    }
}
