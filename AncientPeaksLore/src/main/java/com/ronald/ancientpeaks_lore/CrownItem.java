package com.ronald.ancientpeaks_lore;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

import java.util.List;

/** Vortigern's crown, dropped when he falls: a helmet a little tougher than iron that never burns. */
public class CrownItem extends ArmorItem {
    static final String OWN_TEXTURE = AncientPeaksLore.MODID + ":textures/models/armor/crown_layer_1.png";
    static final String FALLBACK = "minecraft:textures/models/armor/gold_layer_1.png";

    static final ArmorMaterial MATERIAL = new ArmorMaterial() {
        @Override public int getDurabilityForType(Type t) { return 600; }
        @Override public int getDefenseForType(Type t) { return 3; }
        @Override public int getEnchantmentValue() { return 25; }
        @Override public SoundEvent getEquipSound() { return SoundEvents.ARMOR_EQUIP_GOLD; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(Items.GOLD_INGOT); }
        @Override public String getName() { return AncientPeaksLore.MODID + ":crown"; }
        @Override public float getToughness() { return 2.0f; }
        @Override public float getKnockbackResistance() { return 0.1f; }
    };

    public CrownItem() {
        super(MATERIAL, Type.HELMET, new Item.Properties().rarity(Rarity.EPIC).fireResistant());
    }

    @Override public boolean isFoil(ItemStack stack) { return true; }

    /** Your own crown texture once there is one (items/crown_layer_1.png in the dev folder), gold armour until then. */
    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return com.ronald.ancientpeaks_lore.client.ClientRes.exists(OWN_TEXTURE) ? OWN_TEXTURE : FALLBACK;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Taken from the usurper Vortigern.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
