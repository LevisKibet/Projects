package com.ronald.ancientpeaks_lore;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Everything the quest adds: Merlin, Vortigern, Excalibur and its stone, and the crown. */
public final class LoreContent {
    private LoreContent() {}
    static final String MODID = AncientPeaksLore.MODID;

    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MODID);
    static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, MODID);
    static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);

    /** The cutscene music: assets/ancientpeaks_lore/sounds/music/merlin.ogg and vortigern.ogg (or music/*.ogg in the dev folder). */
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> MUSIC_MERLIN = SOUNDS.register("music.merlin",
            () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(new net.minecraft.resources.ResourceLocation(MODID, "music.merlin")));
    public static final RegistryObject<net.minecraft.sounds.SoundEvent> MUSIC_VORTIGERN = SOUNDS.register("music.vortigern",
            () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(new net.minecraft.resources.ResourceLocation(MODID, "music.vortigern")));

    public static final RegistryObject<EntityType<Merlin>> MERLIN = ENTITIES.register("merlin", () ->
            EntityType.Builder.of(Merlin::new, MobCategory.MISC).sized(0.6f, 1.95f).clientTrackingRange(10).build(MODID + ":merlin"));
    public static final RegistryObject<EntityType<Vortigern>> VORTIGERN = ENTITIES.register("vortigern", () ->
            EntityType.Builder.of(Vortigern::new, MobCategory.MONSTER).sized(0.8f, 2.4f).fireImmune().clientTrackingRange(12).build(MODID + ":vortigern"));
    public static final RegistryObject<EntityType<VortigernStatue>> VORTIGERN_STATUE = ENTITIES.register("vortigern_statue", () ->
            EntityType.Builder.of(VortigernStatue::new, MobCategory.MISC).sized(0.8f, 2.4f).fireImmune().clientTrackingRange(12).build(MODID + ":vortigern_statue"));
    public static final RegistryObject<EntityType<SaxonShade>> SAXON_SHADE = ENTITIES.register("saxon_shade", () ->
            EntityType.Builder.<SaxonShade>of(SaxonShade::new, MobCategory.MONSTER).sized(0.4f, 0.8f).fireImmune().clientTrackingRange(8).build(MODID + ":saxon_shade"));

    public static final RegistryObject<EntityType<SaxonRaider>> SAXON_RAIDER = ENTITIES.register("saxon_raider", () ->
            EntityType.Builder.of(SaxonRaider::new, MobCategory.MONSTER).sized(0.6f, 1.95f).clientTrackingRange(10).build(MODID + ":saxon_raider"));

    public static final RegistryObject<Block> SWORD_IN_STONE = BLOCKS.register("sword_in_stone", () -> new SwordInStoneBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(-1.0f, 3600000.0f).noLootTable()
                    .sound(SoundType.STONE).pushReaction(PushReaction.BLOCK).noOcclusion()
                    .lightLevel(st -> st.getValue(SwordInStoneBlock.PULLED) ? 9 : st.getValue(SwordInStoneBlock.BEAM) ? 15 : 13)));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<net.minecraft.world.level.block.entity.BlockEntityType<PedestalBlockEntity>> PEDESTAL = BLOCK_ENTITIES.register("sword_in_stone",
            () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(PedestalBlockEntity::new, SWORD_IN_STONE.get()).build(null));

    public static final RegistryObject<Item> EXCALIBUR = ITEMS.register("excalibur", ExcaliburItem::new);
    public static final RegistryObject<Item> VORTIGERN_SWORD = ITEMS.register("vortigern_sword", VortigernSwordItem::new);
    public static final RegistryObject<Item> CROWN = ITEMS.register("crown_of_vortigern", CrownItem::new);
    public static final RegistryObject<Item> COMPENDIUM = ITEMS.register("compendium", CompendiumItem::new);
    public static final RegistryObject<Item> SWORD_IN_STONE_ITEM = ITEMS.register("sword_in_stone",
            () -> new BlockItem(SWORD_IN_STONE.get(), new Item.Properties().rarity(Rarity.EPIC)));
    public static final RegistryObject<Item> MERLIN_EGG = ITEMS.register("merlin_spawn_egg",
            () -> new ForgeSpawnEggItem(MERLIN, 0x2E3F8F, 0xC9C9D9, new Item.Properties()));
    public static final RegistryObject<Item> RAIDER_EGG = ITEMS.register("saxon_raider_spawn_egg",
            () -> new ForgeSpawnEggItem(SAXON_RAIDER, 0x5A4A3A, 0x8A8F96, new Item.Properties()));
    public static final RegistryObject<Item> VORTIGERN_EGG = ITEMS.register("vortigern_spawn_egg",
            () -> new ForgeSpawnEggItem(VORTIGERN, 0x3A1D1D, 0xC9A227, new Item.Properties()));

    static void register(IEventBus bus) {
        ENTITIES.register(bus); BLOCKS.register(bus); ITEMS.register(bus); SOUNDS.register(bus); BLOCK_ENTITIES.register(bus);
        bus.addListener(LoreContent::attributes);
        bus.addListener(LoreContent::tabs);
        bus.addListener((net.minecraftforge.event.entity.SpawnPlacementRegisterEvent e) -> e.register(SAXON_RAIDER.get(),
                net.minecraft.world.entity.SpawnPlacements.Type.ON_GROUND, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                net.minecraft.world.entity.monster.Monster::checkMonsterSpawnRules, net.minecraftforge.event.entity.SpawnPlacementRegisterEvent.Operation.REPLACE));
    }

    private static void attributes(EntityAttributeCreationEvent e) {
        e.put(MERLIN.get(), Merlin.createAttributes().build());
        e.put(VORTIGERN.get(), Vortigern.createAttributes().build());
        e.put(VORTIGERN_STATUE.get(), VortigernStatue.createAttributes().build());
        e.put(SAXON_SHADE.get(), net.minecraft.world.entity.monster.Vex.createAttributes().build());
        e.put(SAXON_RAIDER.get(), SaxonRaider.createAttributes().build());
    }

    private static void tabs(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.COMBAT) { e.accept(EXCALIBUR); e.accept(VORTIGERN_SWORD); e.accept(CROWN); }
        if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(COMPENDIUM);
        else if (e.getTabKey() == CreativeModeTabs.SPAWN_EGGS) { e.accept(MERLIN_EGG); e.accept(VORTIGERN_EGG); e.accept(RAIDER_EGG); }
        else if (e.getTabKey() == CreativeModeTabs.OP_BLOCKS) { e.accept(SWORD_IN_STONE_ITEM); }
    }
}
