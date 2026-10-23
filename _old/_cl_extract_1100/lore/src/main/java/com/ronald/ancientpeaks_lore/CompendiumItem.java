package com.ronald.ancientpeaks_lore;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;

/** An old leather-bound book: every chronicle of Logres you have read, with its picture. */
public class CompendiumItem extends Item {
    public CompendiumItem() { super(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> com.ronald.ancientpeaks_lore.client.CompendiumScreen::open);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Every chronicle of Logres you read is written in here.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        if (level != null && level.isClientSide)
            tip.add(Component.literal(com.ronald.ancientpeaks_lore.client.CompendiumScreen.knownCount() + " / " + Compendium.total() + " found").withStyle(ChatFormatting.GOLD));
    }
}
