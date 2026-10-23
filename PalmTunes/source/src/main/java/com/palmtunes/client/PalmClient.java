package com.palmtunes.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.palmtunes.PalmConfig;
import com.palmtunes.PalmTunes;
import com.palmtunes.media.MediaManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

/** Wires the client side into Forge: key binding, render layer, per-tick updates. */
public final class PalmClient {
    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.palmtunes.toggle",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.palmtunes");

    private static boolean ambientRequested;

    private PalmClient() {
    }

    public static void init() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(PalmClient::onRegisterKeys);
        modBus.addListener(PalmClient::onAddLayers);
        modBus.addListener(PalmClient::onRegisterOverlays);
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            MediaManager.get().warmUp();
            PalmFallback.logCompat();
        }));

        modBus.addListener((RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((ResourceManagerReloadListener) rm -> CinematicFx.reset()));

        MinecraftForge.EVENT_BUS.addListener(PalmAudio::onPlaySound);
        MinecraftForge.EVENT_BUS.addListener((InputEvent.Key e) -> Cinematic.noteInput());
        MinecraftForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre e) -> Cinematic.noteInput());
        MinecraftForge.EVENT_BUS.addListener((InputEvent.MouseScrollingEvent e) -> Cinematic.noteInput());
        MinecraftForge.EVENT_BUS.addListener((RenderGuiOverlayEvent.Pre e) -> {
            if (Cinematic.hidesHud() && !e.getOverlay().id().equals(new net.minecraft.resources.ResourceLocation(PalmTunes.MOD_ID, "pulse"))) {
                e.setCanceled(true);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ViewportEvent.ComputeFov e) -> {
            if (Cinematic.isOverriding()) {
                e.setFOV(e.getFOV() * Cinematic.fovScale());
            }
        });
        MinecraftForge.EVENT_BUS.addListener(CinematicFx::render);
        MinecraftForge.EVENT_BUS.addListener(PalmFallback::render);
        MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> e.getDispatcher().register(
                Commands.literal("palmtunes").then(Commands.literal("cinematic").executes(c -> {
                    Cinematic.forceStart();
                    return Command.SINGLE_SUCCESS;
                }))));
        MinecraftForge.EVENT_BUS.addListener(PalmClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(PalmClient::onLoggingOut);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> MediaManager.get().shutdownBlocking(), "PalmTunes-Shutdown"));
        PalmTunes.LOGGER.info("PalmTunes client initialised");
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
    }

    private static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("pulse", PalmHud::render);
    }

    private static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : new String[]{"default", "slim"}) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new PalmLayer(renderer, skin.equals("slim")));
            }
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        // Only react to the key in-game, never while typing in chat or a menu.
        while (TOGGLE.consumeClick()) {
            if (mc.screen == null && mc.player != null && !mc.player.isSpectator()) {
                PalmController.open();
            }
        }

        // If the player died or something else took over the screen, wind the panel down.
        if (PalmController.isOpen() && (mc.player == null || !mc.player.isAlive())) {
            PalmController.close();
        }

        // The HUD pulse needs a slow background look at the media session, but only while in a world.
        boolean ambient = mc.player != null && mc.level != null && (PalmConfig.CLIENT.hudEnabled.get() || PalmConfig.CLIENT.muteGameMusic.get());
        if (ambient != ambientRequested) {
            ambientRequested = ambient;
            MediaManager.get().setAmbient(ambient);
        }

        MediaManager.get().configure(PalmConfig.CLIENT.pollIntervalMs.get(), PalmConfig.CLIENT.fetchRemoteArt.get());
        PalmController.tick();
        Cinematic.tick();
        PalmHud.tick();
        PalmAudio.tick();
        ArtTextures.tick();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ambientRequested = false;
        MediaManager.get().setAmbient(false);
        PalmController.reset();
        Cinematic.stopImmediately();
        PalmInteraction.reset();
    }
}
