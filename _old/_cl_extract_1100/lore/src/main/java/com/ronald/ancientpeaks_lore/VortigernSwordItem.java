package com.ronald.ancientpeaks_lore;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import java.util.List;

/** The usurper's longsword: long dark steel, a gilded guard and pommel. Netherite-strong, a little slower. */
public class VortigernSwordItem extends SwordItem {
    static final Tier TIER = new Tier() {
        @Override public int getUses() { return 2400; }
        @Override public float getSpeed() { return 9.0f; }
        @Override public float getAttackDamageBonus() { return 4.0f; }
        @Override public int getLevel() { return 4; }
        @Override public int getEnchantmentValue() { return 15; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(Items.NETHERITE_INGOT); }
    };

    public VortigernSwordItem() {
        super(TIER, 4, -2.6f, new Item.Properties().rarity(Rarity.RARE).fireResistant());
    }

    /** The usurper's sorcery is in the steel: whoever it cuts begins to wither (Vortigern himself only once he's roused). */
    @Override
    public boolean hurtEnemy(ItemStack stack, net.minecraft.world.entity.LivingEntity target, net.minecraft.world.entity.LivingEntity attacker) {
        if (!(attacker instanceof Vortigern v) || v.enraged()) wither(target);
        return super.hurtEnemy(stack, target, attacker);
    }

    static void wither(net.minecraft.world.entity.LivingEntity target) {
        target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WITHER, 100, 1));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("The usurper's blade, gilded with stolen gold.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tip.add(Component.literal("Its cuts wither (Wither II, 5 seconds).").withStyle(ChatFormatting.DARK_PURPLE));
    }
}
