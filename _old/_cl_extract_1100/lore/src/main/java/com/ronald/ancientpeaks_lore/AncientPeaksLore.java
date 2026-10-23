package com.ronald.ancientpeaks_lore;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(AncientPeaksLore.MODID)
public class AncientPeaksLore {
    public static final String MODID = "ancientpeaks_lore";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final net.minecraftforge.registries.DeferredRegister<net.minecraft.world.level.levelgen.structure.StructureType<?>> STRUCTURE_TYPES =
            net.minecraftforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.STRUCTURE_TYPE, MODID);
    private static final net.minecraftforge.registries.DeferredRegister<net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType> PIECES =
            net.minecraftforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.STRUCTURE_PIECE, MODID);
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.world.level.levelgen.structure.StructureType<LoreSite>> SITE =
            STRUCTURE_TYPES.register("site", () -> () -> LoreSite.CODEC);
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType> PIECE =
            PIECES.register("piece", () -> (net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType) LorePiece::new);
    private static final net.minecraftforge.registries.DeferredRegister<net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType<?>> PROCESSORS =
            net.minecraftforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.STRUCTURE_PROCESSOR, MODID);
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType<OutsideAirProcessor>> OUTSIDE_AIR =
            PROCESSORS.register("outside_air", () -> () -> OutsideAirProcessor.CODEC);
    public static final net.minecraftforge.registries.RegistryObject<net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType<LootProcessor>> LOOT =
            PROCESSORS.register("logres_loot", () -> () -> LootProcessor.CODEC);

    private static final net.minecraftforge.registries.DeferredRegister<com.mojang.serialization.Codec<? extends net.minecraftforge.common.loot.IGlobalLootModifier>> LOOT_MODIFIERS =
            net.minecraftforge.registries.DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MODID);
    static { LOOT_MODIFIERS.register("lore_books", LoreBookLoot.CODEC); }

    /** The oldest Ruins of Logres this version works with. */
    static final String RUINS_MIN = "1.5.0";

    /**
     * Forge's own dependency check names the missing mod by its internal id ("ancientpeaks"), which nobody would
     * recognise, so the check is done here instead and names it properly.
     */
    private static void needRuins() {
        var ruins = net.minecraftforge.fml.ModList.get().getModContainerById("ancientpeaks");
        if (ruins.isEmpty())
            throw new IllegalStateException("Chronicles of Logres needs Ruins of Logres " + RUINS_MIN + " or newer, and it is not installed. Add Ruins of Logres to your mods folder.");
        var have = ruins.get().getModInfo().getVersion();
        if (have.compareTo(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(RUINS_MIN)) < 0)
            throw new IllegalStateException("Chronicles of Logres needs Ruins of Logres " + RUINS_MIN + " or newer, but you have " + have + ". Swap in the newer Ruins of Logres jar.");
    }

    public AncientPeaksLore() {
        needRuins();
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        STRUCTURE_TYPES.register(bus); PIECES.register(bus); PROCESSORS.register(bus); LOOT_MODIFIERS.register(bus);
        LoreContent.register(bus);
        LoreSkills.register(bus);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, LoreConfig.SPEC);
        LoreTrades.register();
        LoreNet.register();
        LoreEvents.register();
        Compendium.register();
        SummitWatch.register();
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            com.ronald.ancientpeaks_lore.client.LoreClient.init(bus);
            com.ronald.ancientpeaks_lore.light.ChromaLight.init(bus);                   // coloured light, built in
        }
        bus.addListener(this::addPacks);
        com.ronald.ancientpeaks.AncientPeaks.DECORATION_HOOKS.add(Trails::decorate);
        com.ronald.ancientpeaks.AncientPeaks.DECORATION_HOOKS.add(Harbour::decorate);
        com.ronald.ancientpeaks.AncientPeaks.DECORATION_HOOKS.add(Boardwalk::decorate);
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> LoreCommands.register(e.getDispatcher()));
    }

    /** Dev mode: an outside folder of .nbt files is served as structure templates, re-read on every /reload. */
    private void addPacks(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.CLIENT_RESOURCES) {
            var folder = DevStructures.folder();
            var music = DevStructures.musicFolder();
            if (folder != null) LOGGER.info("[{}] dev characters and item textures from {}", MODID, folder);
            event.addRepositorySource(add -> {
                Pack pack = Pack.readMetaAndCreate("ancientpeaks_lore_dev_assets", Component.literal("Ancient Peaks music and dev assets"), true,
                        id -> new DevAssets(id, folder, music), PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
                if (pack != null) add.accept(pack);
            });
            return;
        }
        if (event.getPackType() != PackType.SERVER_DATA) return;
        var folder = DevStructures.folder();
        if (folder == null) { LOGGER.info("[{}] no dev structure folder; using the structures inside the jar", MODID); return; }
        LOGGER.info("[{}] dev structures from {}", MODID, folder);
        event.addRepositorySource(add -> {
            Pack pack = Pack.readMetaAndCreate("ancientpeaks_lore_dev", Component.literal("Ancient Peaks dev structures"), true,
                    id -> new DevStructures(id, folder), PackType.SERVER_DATA, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack != null) add.accept(pack);
        });
    }
}
