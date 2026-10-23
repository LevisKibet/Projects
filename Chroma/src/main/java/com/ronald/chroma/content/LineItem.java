package com.ronald.chroma.content;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * An LED strip or a string of lights. Laying one is handled by the client sending what was clicked (so clicks that land
 * on an existing line can mean something else); the server side does nothing here.
 */
public class LineItem extends Item {
    public final int kind;

    public LineItem(int kind, Properties props) { super(props); this.kind = kind; }

    @Override public InteractionResult useOn(UseOnContext ctx) { return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide); }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable(kind == Curve.BULBS ? "tooltip.chroma.string_lights" : "tooltip.chroma.led_strip").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("tooltip.chroma.dye").withStyle(ChatFormatting.DARK_GRAY));
    }
}
