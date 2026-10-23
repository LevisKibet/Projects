package com.ronald.ancientpeaks_lore;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;

/**
 * A map to Merlin's cabin, very rarely for sale: as the wandering trader's rare offer now and then, and now and then
 * as one of a master cartographer's last trades. Marked with an X on the cabin; only offered in a world that has it.
 */
final class LoreTrades {
    private LoreTrades() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener((WandererTradesEvent e) -> e.getRareTrades().add(new MerlinMap(false)));
        MinecraftForge.EVENT_BUS.addListener((VillagerTradesEvent e) -> {
            if (e.getType() == VillagerProfession.CARTOGRAPHER) e.getTrades().get(5).add(new MerlinMap(true));
        });
    }

    /** The offer, its chance and price read from the config each time a trader stocks up. */
    record MerlinMap(boolean cartographer) implements VillagerTrades.ItemListing {
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource r) {
            boolean on = LoreConfig.on(cartographer ? LoreConfig.CARTOGRAPHER : LoreConfig.TRADER, true);
            double chance = LoreConfig.num(cartographer ? LoreConfig.CARTOGRAPHER_CHANCE : LoreConfig.TRADER_CHANCE, cartographer ? 0.15 : 0.3);
            int emeralds = LoreConfig.num(cartographer ? LoreConfig.CARTOGRAPHER_PRICE : LoreConfig.TRADER_PRICE, cartographer ? 18 : 14);
            int uses = LoreConfig.num(LoreConfig.USES, 1), xp = cartographer ? 30 : 4;
            if (!on || r.nextFloat() >= chance || !(trader.level() instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return null;
            BlockPos cabin = cabin(sl);
            if (cabin == null) return null;
            ItemStack map = MapItem.create(sl, cabin.getX(), cabin.getZ(), (byte) 2, true, true);
            MapItem.renderBiomePreviewMap(sl, map);
            MapItemSavedData.addTargetDecoration(map, cabin, "merlin", MapDecoration.Type.RED_X);
            map.setHoverName(Component.literal("Map to Merlin's Cabin").withStyle(ChatFormatting.LIGHT_PURPLE).withStyle(s -> s.withItalic(false)));
            CompoundTag display = map.getOrCreateTagElement("display");
            ListTag lore = new ListTag();
            lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("Where the bell rings in the mist.").withStyle(ChatFormatting.GRAY))));
            display.put("Lore", lore);
            return new MerchantOffer(new ItemStack(Items.EMERALD, emeralds), new ItemStack(Items.COMPASS), map, uses, xp, 0.2f);
        }
    }

    /** Merlin's cabin in this world (the first of the church spots), or null if the world has none. */
    static BlockPos cabin(ServerLevel sl) {
        boolean has = LoreSite.templates(sl.getStructureManager(), "churches").stream().anyMatch(rl -> rl.getPath().contains("merlin"));
        if (!has) return null;
        for (int[] s : LoreCommands.chapelSpots(sl)) if (s[2] == 0) return new BlockPos(s[0], 64, s[1]);
        return null;
    }
}
