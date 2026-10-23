package com.ronald.ancientpeaks;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
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
 * A chart of the island, rare and dear: now and then the wandering trader's rare offer, now and then one of a master
 * cartographer's last trades. It shows the whole island with an X on the landing beach.
 */
final class IslandMapTrades {
    private IslandMapTrades() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener((WandererTradesEvent e) -> e.getRareTrades().add(new IslandMap(false)));
        MinecraftForge.EVENT_BUS.addListener((VillagerTradesEvent e) -> {
            if (e.getType() == VillagerProfession.CARTOGRAPHER) e.getTrades().get(5).add(new IslandMap(true));
        });
    }

    /** The offer, its chance and price read from the config each time a trader stocks up. */
    record IslandMap(boolean cartographer) implements VillagerTrades.ItemListing {
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource r) {
            boolean on = RuinsConfig.on(cartographer ? RuinsConfig.CARTOGRAPHER : RuinsConfig.TRADER, true);
            double chance = RuinsConfig.num(cartographer ? RuinsConfig.CARTOGRAPHER_CHANCE : RuinsConfig.TRADER_CHANCE, cartographer ? 0.15 : 0.25);
            int emeralds = RuinsConfig.num(cartographer ? RuinsConfig.CARTOGRAPHER_PRICE : RuinsConfig.TRADER_PRICE, cartographer ? 32 : 40);
            int uses = RuinsConfig.num(RuinsConfig.USES, 1), xp = cartographer ? 30 : 5;
            if (!on || r.nextFloat() >= chance || !(trader.level() instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return null;
            IslandSampler s = ((HighlandsAccess) (Object) sl.getChunkSource().randomState()).ancientpeaks$sampler();
            Island is = s == null ? null : s.island();
            if (is == null) return null;
            int[] land = is.landingCove();
            // the whole island on one sheet (a map of 1024 blocks), the landing beach marked
            ItemStack map = MapItem.create(sl, is.cx, is.cz, (byte) 3, true, true);
            MapItem.renderBiomePreviewMap(sl, map);
            MapItemSavedData.addTargetDecoration(map, new BlockPos(land[0], 64, land[1]), "landing", MapDecoration.Type.RED_X);
            map.setHoverName(Component.literal("Chart of the Ancient Highlands").withStyle(ChatFormatting.GOLD).withStyle(st -> st.withItalic(false)));
            CompoundTag display = map.getOrCreateTagElement("display");
            ListTag lore = new ListTag();
            lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("An island far out to sea, where the old ruins stand.").withStyle(ChatFormatting.GRAY))));
            display.put("Lore", lore);
            return new MerchantOffer(new ItemStack(Items.EMERALD, emeralds), new ItemStack(Items.COMPASS), map, uses, xp, 0.2f);
        }
    }
}
