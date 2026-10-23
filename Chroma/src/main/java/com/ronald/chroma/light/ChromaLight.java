package com.ronald.chroma.light;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
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
 * Chroma's coloured light (client side only; started from the Chroma mod class).
 * Stands aside while an Oculus shader pack is in use. Settings: config/chroma-client.toml, or Mods > Chroma > Config,
 * or the key you bind under "Chroma".
 *
 * Try it: /chroma test <colour> [radius] puts a coloured light where you're looking (colour as a name like red,
 * green, blue, gold, purple, cyan or a hex code like ff4400); /chroma clear removes them.
 */
public final class ChromaLight {
    public static final String MODID = "chroma";
    public static final Logger LOGGER = LogUtils.getLogger();
    static final KeyMapping OPEN_SETTINGS = new KeyMapping("key.chroma.settings", InputConstants.UNKNOWN.getValue(), "key.categories.chroma");

    private ChromaLight() {}

    /** False when another copy of this lighting is doing the work (Chronicles of Logres, or the old Chroma Light). */
    public static boolean ACTIVE = true;

    /** Called from Chroma's constructor, on the client only. */
    public static void init(net.minecraftforge.eventbus.api.IEventBus modBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        if (com.ronald.chroma.light.mixin.LightMixinPlugin.standDown()) {
            ACTIVE = false;
            LOGGER.info("Chroma: Chronicles of Logres (or the old Chroma Light) is doing the coloured lighting, so Chroma's own copy stands aside");
            return;
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Settings.SPEC, "chroma-client.toml");
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new SettingsScreen(parent)));
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN_SETTINGS));
        modBus.addListener((RegisterClientReloadListenersEvent e) ->
                e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> LightRenderer.reset()));
        MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent e) -> {
            if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) LightRenderer.render(e);
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) return;
            Lights.tick();
            DynamicLights.tick();
            VoxelLight.tick();
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_SETTINGS.consumeClick()) if (mc.screen == null) mc.setScreen(new SettingsScreen(null));
        });
        MinecraftForge.EVENT_BUS.addListener(ChromaLight::commands);
    }

    private static void commands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("chroma")
                .then(Commands.literal("test").then(Commands.argument("colour", StringArgumentType.word())
                        .executes(c -> test(StringArgumentType.getString(c, "colour"), 8f))
                        .then(Commands.argument("radius", FloatArgumentType.floatArg(1f, 32f))
                                .executes(c -> test(StringArgumentType.getString(c, "colour"), FloatArgumentType.getFloat(c, "radius"))))))
                .then(Commands.literal("clear").executes(c -> { Lights.TEST.clear(); say("Test lights cleared"); return 1; }))
                .then(Commands.literal("toggle").executes(c -> {
                    Settings.ENABLED.set(!Settings.enabled()); Settings.SPEC.save();
                    say("Coloured light " + (Settings.enabled() ? "on" : "off")); return 1; })));
    }

    private static int test(String colour, float radius) {
        Minecraft mc = Minecraft.getInstance();
        float[] rgb = parse(colour);
        if (rgb == null) { say("Unknown colour: try red, orange, gold, green, cyan, blue, purple, pink, white or a hex code like ff4400"); return 0; }
        HitResult hit = mc.hitResult;
        Vec3 at = hit != null && hit.getType() != HitResult.Type.MISS ? hit.getLocation() : mc.player.getEyePosition().add(mc.player.getLookAngle().scale(3));
        Vec3 back = mc.player.getEyePosition().subtract(at).normalize().scale(0.5);      // just off the surface
        Lights.TEST.add(new Lights.Light(at.x + back.x, at.y + back.y, at.z + back.z, rgb[0], rgb[1], rgb[2], radius, 1.3f, true, false));
        say("Test light added (" + Lights.TEST.size() + ")");
        return 1;
    }

    static float[] parse(String s) {
        switch (s.toLowerCase()) {
            case "red": return new float[]{1f, 0.12f, 0.1f};
            case "orange": return new float[]{1f, 0.5f, 0.15f};
            case "gold": case "yellow": return new float[]{1f, 0.82f, 0.3f};
            case "green": return new float[]{0.3f, 1f, 0.35f};
            case "cyan": return new float[]{0.25f, 0.9f, 1f};
            case "blue": return new float[]{0.25f, 0.45f, 1f};
            case "purple": return new float[]{0.7f, 0.3f, 1f};
            case "pink": return new float[]{1f, 0.45f, 0.8f};
            case "white": return new float[]{1f, 1f, 1f};
            default:
                try {
                    int v = Integer.parseInt(s.replace("#", ""), 16);
                    return new float[]{((v >> 16) & 255) / 255f, ((v >> 8) & 255) / 255f, (v & 255) / 255f};
                } catch (NumberFormatException ex) { return null; }
        }
    }

    private static void say(String s) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(s), false);
    }

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
