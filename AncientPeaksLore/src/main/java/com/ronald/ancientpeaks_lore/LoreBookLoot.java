package com.ronald.ancientpeaks_lore;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

import java.util.function.Supplier;

/**
 * Lore books in other structures' chests: any chest loot table (vanilla's or any mod's - its id has "chests/" in it)
 * now and then holds one of the Logres books. Our own chests roll their books from their own tables instead.
 * data/ancientpeaks_lore/loot_modifiers/lore_books.json sets the chance.
 */
public class LoreBookLoot extends LootModifier {
    public static final Supplier<Codec<LoreBookLoot>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.create(i -> codecStart(i)
            .and(Codec.FLOAT.optionalFieldOf("chance", 0.1f).forGetter(m -> m.chance)).apply(i, LoreBookLoot::new)));
    private final float chance;

    public LoreBookLoot(LootItemCondition[] conditions, float chance) { super(conditions); this.chance = chance; }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) {
        var id = ctx.getQueriedLootTableId();
        if (id == null || id.getNamespace().equals(AncientPeaksLore.MODID) || !id.getPath().contains("chests/")) return loot;
        if (ctx.getRandom().nextFloat() >= chance) return loot;
        loot.add(LoreBooks.stack(LoreBooks.random(ctx.getRandom(), "all")));
        return loot;
    }

    @Override public Codec<? extends IGlobalLootModifier> codec() { return CODEC.get(); }
}
