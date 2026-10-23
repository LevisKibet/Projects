package com.ronald.ancientpeaks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.ronald.ancientpeaks.world.GorgeRiverFeature;
import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.HighlandsDensity;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(AncientPeaks.MODID)
public class AncientPeaks {
    public static final String MODID = "ancientpeaks";
    /** The separate lore mod. When it is installed it takes over the gateways (and everything around them). */
    public static final String LORE_MODID = "ancientpeaks_lore";
    /** Run for every chunk after all of its decoration (including the island's water); other mods paint on top here. */
    public static final java.util.List<java.util.function.BiConsumer<net.minecraft.world.level.WorldGenLevel, net.minecraft.world.level.ChunkPos>> DECORATION_HOOKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** True only once the lore mod says (mods.toml modproperties: takesOverGateways=true) that it handles the gateways itself. */
    public static boolean loreLoaded() {
        return net.minecraftforge.fml.ModList.get().getModContainerById(LORE_MODID)
                .map(c -> Boolean.parseBoolean(String.valueOf(c.getModInfo().getModProperties().getOrDefault("takesOverGateways", "false"))))
                .orElse(false);
    }
    public static final Logger LOGGER = LogUtils.getLogger();
    /** Island biome per IslandSampler.Zone ordinal (NONE stays null = keep the ocean's biome). */
    public static volatile Holder<Biome>[] zoneBiomes;
    private static final String[] ZONE_BIOMES = {null, "ancient_highlands", "highland_meadow", "highland_forest", "highland_plains", "highland_gorge", "highland_shore", "highland_peaks", "highland_tarn"};

    private static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_TYPES = DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MODID);
    private static final DeferredRegister<Codec<? extends net.minecraft.world.level.levelgen.SurfaceRules.ConditionSource>> CONDITIONS = DeferredRegister.create(Registries.MATERIAL_CONDITION, MODID);
    public static final RegistryObject<Codec<? extends net.minecraft.world.level.levelgen.SurfaceRules.ConditionSource>> STEEP_ANY = CONDITIONS.register("steep_any", () -> com.ronald.ancientpeaks.world.SteepAny.CODEC.codec());
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, MODID);
    public static final RegistryObject<Codec<? extends DensityFunction>> HIGHLANDS_DENSITY = DENSITY_TYPES.register("highlands", () -> HighlandsDensity.CODEC.codec());
    public static final RegistryObject<Codec<? extends DensityFunction>> ISLAND_FLOOD = DENSITY_TYPES.register("island_flood", () -> com.ronald.ancientpeaks.world.IslandFlood.CODEC.codec());
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> GORGE_DECOR = FEATURES.register("gorge_decor", () -> new com.ronald.ancientpeaks.world.GorgeDecorFeature(NoneFeatureConfiguration.CODEC));
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> GORGE_RIVER = FEATURES.register("gorge_river", () -> new GorgeRiverFeature(NoneFeatureConfiguration.CODEC));

    private static final DeferredRegister<net.minecraft.world.level.levelgen.structure.StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, MODID);
    public static final RegistryObject<net.minecraft.world.level.levelgen.structure.StructureType<com.ronald.ancientpeaks.structure.AnchoredStructure>> ANCHORED =
            STRUCTURE_TYPES.register("anchored", () -> () -> com.ronald.ancientpeaks.structure.AnchoredStructure.CODEC);

    public AncientPeaks() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        // Streams Reflowing, when installed, waters the island too - its streams supersede our own
        Island.OWN_STREAMS = !net.minecraftforge.fml.ModList.get().isLoaded("streamsreflowing");
        Island.PLAINS_OPEN = !net.minecraftforge.fml.ModList.get().isLoaded("ancientpeaks_lore");
        LOGGER.info("Island streams: {}", Island.OWN_STREAMS ? "our own" : "Streams Reflowing's");
        com.ronald.ancientpeaks.world.DevProbe.init();
        IslandMapTrades.register();
        DENSITY_TYPES.register(bus);
        CONDITIONS.register(bus);
        FEATURES.register(bus);
        STRUCTURE_TYPES.register(bus);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, RuinsConfig.SPEC);
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) RuinsSettingsScreen.register();
        MinecraftForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStartedEvent e) -> {
            if (Gateways.enabled()) com.ronald.ancientpeaks.compat.GatewayLink.warmUp(e.getServer().overworld());
        });
        MinecraftForge.EVENT_BUS.addListener(this::onCommands);
    }

    @SuppressWarnings("unchecked")
    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        Registry<Biome> reg = event.getServer().registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome>[] arr = new Holder[ZONE_BIOMES.length];
        for (int i = 1; i < ZONE_BIOMES.length; i++) {
            arr[i] = reg.getHolder(ResourceKey.create(Registries.BIOME, new ResourceLocation(MODID, ZONE_BIOMES[i]))).orElse(null);
            if (arr[i] == null) LOGGER.error("Missing island biome {}", ZONE_BIOMES[i]);
        }
        zoneBiomes = arr;
        LOGGER.info("Ancient Highlands island biomes ready");
    }

    private void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal(MODID).requires(s -> s.hasPermission(2))
                .then(Commands.literal("landing").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerLevel level = src.getLevel();
                    IslandSampler s = ((HighlandsAccess) (Object) level.getChunkSource().randomState()).ancientpeaks$sampler();
                    Vec3 p = src.getPosition();
                    Island is = s == null ? null : s.islandInSector(Math.floorDiv((int) p.x, IslandSampler.SECTOR), Math.floorDiv((int) p.z, IslandSampler.SECTOR));
                    if (is == null) { src.sendFailure(Component.literal("No island in this sector")); return 0; }
                    if (loreLoaded()) { src.sendFailure(Component.literal("Gateways are handled by the Ancient Peaks lore mod")); return 0; }
                    if (!Gateways.enabled()) { src.sendFailure(Component.literal("Highland Gateways are off (Immersive Gateways isn't installed, or they're disabled in the config)")); return 0; }
                    final Island target = is;
                    src.sendSuccess(() -> Component.literal("Building the island gateway in the background..."), false);
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        net.minecraft.core.BlockPos core = com.ronald.ancientpeaks.compat.GatewayLink.buildIslandGateway(level, s, target);
                        LOGGER.info("Island gateway built at {}", core);
                    });
                    return 1;
                }))
                .then(Commands.literal("island").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerLevel level = src.getLevel();
                    IslandSampler s = ((HighlandsAccess) (Object) level.getChunkSource().randomState()).ancientpeaks$sampler();
                    if (s == null) { src.sendFailure(Component.literal("Ancient Highlands islands are not active in this dimension")); return 0; }
                    Vec3 p = src.getPosition();
                    int sx = Math.floorDiv((int) p.x, IslandSampler.SECTOR), sz = Math.floorDiv((int) p.z, IslandSampler.SECTOR);
                    Island best = null; double bd = Double.MAX_VALUE;
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        Island is = s.islandInSector(sx + dx, sz + dz);
                        if (is != null) { double dd = Math.hypot(is.cx - p.x, is.cz - p.z); if (dd < bd) { bd = dd; best = is; } }
                    }
                    if (best == null) { src.sendFailure(Component.literal("No island in the surrounding sectors")); return 0; }
                    String tp = "/tp @s " + best.cx + " 250 " + best.cz;
                    int dist = (int) bd;
                    int cx = best.cx, cz = best.cz;
                    int[] arch = best.greatArch(), cove = best.landingCove(), meadow = best.meadow();
                    String tarns = best.describeTarns();
                    StringBuilder an = new StringBuilder();
                    for (String k : new String[]{"plains_village", "plains_castle", "meadow_plateau", "main_peak"}) { int[] w = best.anchor(k); an.append(k).append(' ').append(w[0]).append(", ").append(w[1]).append("   "); }
                    String anchors = an.toString();
                    src.sendSuccess(() -> Component.literal("Nearest Ancient Highlands island: " + cx + ", " + cz + " (" + dist + " blocks) ")
                            .append(Component.literal("[teleport]").withStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp)).withUnderlined(true)))
                            .append(Component.literal("\nGreat arch: " + arch[0] + ", " + arch[1] + "   Landing beach: " + cove[0] + ", " + cove[1] + "   Meadow: " + meadow[0] + ", " + meadow[1] + "\nTarns: " + tarns + "\nStructure anchors: " + anchors)), false);
                    return 1;
                })));
    }
}
