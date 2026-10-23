package com.ronald.chroma.content;

import com.ronald.chroma.Chroma;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Everything Chroma adds to the game. */
public final class ChromaContent {
    private ChromaContent() {}

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Chroma.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Chroma.MODID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Chroma.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Chroma.MODID);

    public static final RegistryObject<Block> ANCHOR = BLOCKS.register("line_anchor", AnchorBlock::new);
    /** The invisible glow blocks: every dye colour at each shade (see GlowBlock for why), indexed dye * SHADES + shade. */
    public static final java.util.List<RegistryObject<Block>> GLOWS = new java.util.ArrayList<>();
    static {
        for (net.minecraft.world.item.DyeColor d : net.minecraft.world.item.DyeColor.values())
            for (int sh = 0; sh < Curve.SHADES.length; sh++) {
                int shade = sh;
                GLOWS.add(BLOCKS.register("glow_" + d.getName() + Curve.SHADE_SUFFIX[sh], () -> new GlowBlock(d, shade)));
            }
    }

    public static final RegistryObject<BlockEntityType<LineEntity>> LINE = ENTITIES.register("line",
            () -> BlockEntityType.Builder.of(LineEntity::new, ANCHOR.get()).build(null));

    public static final RegistryObject<Item> LED_STRIP = ITEMS.register("led_strip", () -> new LineItem(Curve.STRIP, new Item.Properties()));
    public static final RegistryObject<Item> STRING_LIGHTS = ITEMS.register("string_lights", () -> new LineItem(Curve.BULBS, new Item.Properties()));
    public static final RegistryObject<Item> LIGHT_SHAPER = ITEMS.register("light_shaper", () -> new ShaperItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.chroma"))
            .icon(() -> new ItemStack(LED_STRIP.get()))
            .displayItems((params, out) -> { out.accept(LED_STRIP.get()); out.accept(STRING_LIGHTS.get()); out.accept(LIGHT_SHAPER.get()); })
            .build());

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        TABS.register(bus);
    }
}
