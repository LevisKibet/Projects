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

/** The sword of kings: a little better than netherite, never burns, and the only blade that bites Vortigern fully. */
public class ExcaliburItem extends SwordItem {
    static final Tier TIER = new Tier() {
        @Override public int getUses() { return 3000; }
        @Override public float getSpeed() { return 9.0f; }
        @Override public float getAttackDamageBonus() { return 5.0f; }
        @Override public int getLevel() { return 4; }
        @Override public int getEnchantmentValue() { return 22; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(Items.NETHERITE_INGOT); }
    };

    public ExcaliburItem() {
        super(TIER, 4, -2.4f, new Item.Properties().rarity(Rarity.EPIC).fireResistant());
    }

    @Override public boolean isFoil(ItemStack stack) { return true; }

    /** How long the blade's core burns blue after Judgement of Avalon is called (ticks). */
    static final int JUDGEMENT_GLOW = 60;
    static final int WHITE = 0xEAF4FF, BLUE = 0x3DA5FF;

    /** A holy blade: it always carries Smite IV. */
    static ItemStack blessed(ItemStack st) {
        if (net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(net.minecraft.world.item.enchantment.Enchantments.SMITE, st) < 4) {
            var ench = net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(st);
            ench.put(net.minecraft.world.item.enchantment.Enchantments.SMITE, 4);
            net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(ench, st);
        }
        st.getOrCreateTag().putBoolean("Blessed", true);
        return st;
    }

    @Override
    public ItemStack getDefaultInstance() { return blessed(super.getDefaultInstance()); }

    /** The core burns blue for a moment: Judgement of Avalon has been called. */
    static void judge(ItemStack st, long now) {
        st.getOrCreateTag().putLong("JudgementUntil", now + JUDGEMENT_GLOW);
        light(st, BLUE, 15);
    }

    /** Is the core lit right now? (Read by the item model and by the light it gives.) */
    public static boolean judging(ItemStack st, @org.jetbrains.annotations.Nullable Level level) {
        if (level == null || !st.hasTag()) return false;
        return st.getTag().getLong("JudgementUntil") > level.getGameTime();
    }

    /** The light the blade gives (read by Chroma Light: an item tag any mod can set). */
    private static void light(ItemStack st, int colour, int level) {
        var t = st.getOrCreateTagElement("ChromaLight");
        if (t.getInt("color") == colour && t.getInt("level") == level) return;
        t.putInt("color", colour); t.putInt("level", level);
    }

    /** Changing the light tag mustn't make the sword bob in the hand as if re-drawn. */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack from, ItemStack to, boolean slotChanged) {
        return slotChanged || from.getItem() != to.getItem();
    }

    /** Held, the blade sheds a faint light: now and then a mote of white fire drifts off it. */
    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity holder, int slot, boolean selected) {
        if (!level.isClientSide) {
            if (!stack.hasTag() || !stack.getTag().getBoolean("Blessed")) blessed(stack);
            if (judging(stack, level)) light(stack, BLUE, 15); else light(stack, WHITE, 11);
            return;
        }
        if (!(holder instanceof net.minecraft.world.entity.LivingEntity le)) return;
        boolean held = le.getMainHandItem() == stack || le.getOffhandItem() == stack;
        if (!held || level.random.nextInt(3) != 0) return;
        float yaw = le.yBodyRot * ((float) Math.PI / 180f);
        double side = le.getMainHandItem() == stack ? -0.38 : 0.38;
        double x = le.getX() + Math.cos(yaw) * side * (le.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1);
        double z = le.getZ() + Math.sin(yaw) * side * (le.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1);
        double y = le.getY() + le.getBbHeight() * 0.45 + level.random.nextDouble() * 0.8;
        if (judging(stack, level)) {
            level.addParticle(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 0, 0.02, 0);
            return;
        }
        level.addParticle(level.random.nextInt(4) == 0 ? net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK : net.minecraft.core.particles.ParticleTypes.END_ROD,
                x + (level.random.nextDouble() - 0.5) * 0.3, y, z + (level.random.nextDouble() - 0.5) * 0.3, 0, 0.01, 0);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Drawn from the stone on the high meadow.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tip.add(Component.literal("Full damage against the usurper.").withStyle(ChatFormatting.GOLD));
        tip.add(Component.literal("A holy blade: its light keeps the dark at bay.").withStyle(ChatFormatting.WHITE));
        tip.add(Component.literal("Judgement of Avalon: lightning strikes twice every foe you wounded in the last minute.").withStyle(ChatFormatting.AQUA));
    }
}
