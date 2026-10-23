package com.ronald.chroma.content;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The Light Shaper. While it's held, every line nearby shows its points and curve handles; clicking them reshapes the
 * line (the clicks are picked up on the client, see client/LineInput).
 */
public class ShaperItem extends Item {
    public ShaperItem(Properties props) { super(props); }

    /** Never breaks blocks: left-click is for turning handles. */
    @Override public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) { return false; }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        for (int i = 1; i <= 3; i++) tip.add(Component.translatable("tooltip.chroma.shaper." + i).withStyle(ChatFormatting.GRAY));
    }
}
