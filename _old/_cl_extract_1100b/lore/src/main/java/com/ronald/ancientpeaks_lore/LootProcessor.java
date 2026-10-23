package com.ronald.ancientpeaks_lore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Fills a building's containers as it is placed:
 *   chests     the Logres loot for the place (books: its own group of lore books now and then) - ancientpeaks_lore:chests/logres_GROUP
 *   barrels    some of them (no more than a handful a building) the lighter stores table
 *   lecterns   a lore book of the place's group, open on the stand
 *   item frames left empty: a lore book in some of them
 * Whatever a template had in its containers is replaced.
 */
public class LootProcessor extends StructureProcessor {
    public static final Codec<LootProcessor> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("books", "village").forGetter(p -> p.books),
            Codec.FLOAT.optionalFieldOf("barrels", 0.35f).forGetter(p -> p.barrels)).apply(i, LootProcessor::new));
    static final String STORES = AncientPeaksLore.MODID + ":chests/logres_stores";
    static final String MERLIN_STORES = AncientPeaksLore.MODID + ":chests/logres_merlin_stores";
    final String books; final float barrels;
    /** How often a lectern or an empty item frame in our buildings holds a lore book: books are a find, not a given. */
    static final float LECTERN_BOOK = 0.15f, FRAME_BOOK = 0.06f;

    public LootProcessor(String books, float barrels) { this.books = books; this.barrels = barrels; }

    /** The book group for a template, by where it stands. */
    static String groupFor(String path) {
        if (path.startsWith("churches/church_merlin")) return "merlin";     // his cabin: no one's books but his own
        if (path.startsWith("churches/")) return "chapel";
        if (path.startsWith("castle/")) return "guard";
        if (path.startsWith("carts/")) return "cart";
        if (path.startsWith("wizard_tower/")) return "all";
        return "village";
    }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos,
                                                             StructureTemplate.StructureBlockInfo original,
                                                             StructureTemplate.StructureBlockInfo current, StructurePlaceSettings settings) {
        if (current == null) return null;
        var block = current.state().getBlock();
        RandomSource r = settings.getRandom(current.pos());
        if (block instanceof LecternBlock) {
            // a lectern the template set aside for one particular book (the King's desk, the guard rooms) always has it
            int preset = current.nbt() == null ? 0 : current.nbt().getInt("LoreBook");
            if (preset > 0 && preset <= LoreBooks.ALL.size()) {
                CompoundTag nbt = new CompoundTag();
                nbt.putString("id", "minecraft:lectern");
                nbt.put("Book", LoreBooks.stack(LoreBooks.byNumber(preset)).save(new CompoundTag()));
                nbt.putInt("Page", 0);
                return new StructureTemplate.StructureBlockInfo(current.pos(), current.state().setValue(LecternBlock.HAS_BOOK, true), nbt);
            }
            // a lore book on the stand now and then (always in Merlin's cabin: his journal); otherwise left bare
            if (!books.equals("merlin") && r.nextFloat() > LECTERN_BOOK)
                return new StructureTemplate.StructureBlockInfo(current.pos(), current.state().setValue(LecternBlock.HAS_BOOK, false), null);
            CompoundTag nbt = new CompoundTag();
            nbt.putString("id", "minecraft:lectern");
            CompoundTag book = LoreBooks.stack(LoreBooks.random(r, books.equals("cart") ? "raider" : books)).save(new CompoundTag());
            nbt.put("Book", book);
            nbt.putInt("Page", 0);
            return new StructureTemplate.StructureBlockInfo(current.pos(), current.state().setValue(LecternBlock.HAS_BOOK, true), nbt);
        }
        boolean chest = block instanceof ChestBlock, barrel = block instanceof BarrelBlock;
        if (!chest && !barrel) return current;
        CompoundTag nbt = current.nbt() == null ? new CompoundTag() : current.nbt().copy();
        // a room's own table (the castle's treasury, armouries, library, quarters) stays as the template set it
        String own = nbt.getString("LootTable");
        boolean keep = own.startsWith(AncientPeaksLore.MODID + ":chests/castle_");
        if (barrel && !keep && r.nextFloat() > barrels) return current;
        nbt.remove("Items");
        nbt.putString("id", chest ? "minecraft:chest" : "minecraft:barrel");
        nbt.putString("LootTable", keep ? own : chest ? AncientPeaksLore.MODID + ":chests/logres_" + books : books.equals("merlin") ? MERLIN_STORES : STORES);
        nbt.putLong("LootTableSeed", r.nextLong());
        return new StructureTemplate.StructureBlockInfo(current.pos(), current.state(), nbt);
    }

    @Override
    public StructureTemplate.StructureEntityInfo processEntity(LevelReader world, BlockPos seedPos, StructureTemplate.StructureEntityInfo raw,
                                                               StructureTemplate.StructureEntityInfo info, StructurePlaceSettings settings, StructureTemplate template) {
        if (info == null) return null;
        String id = info.nbt.getString("id");
        if (id.equals("minecraft:item_frame") || id.equals("minecraft:glow_item_frame") || id.equals("minecraft:painting")) {
            // a hanging entity reads its block from TileX/Y/Z, which the template saved where it was built: point it at
            // where it stands now (otherwise the game logs "Hanging entity at invalid position" for every one)
            CompoundTag at = info.nbt.copy();
            at.putInt("TileX", info.blockPos.getX()); at.putInt("TileY", info.blockPos.getY()); at.putInt("TileZ", info.blockPos.getZ());
            info = new StructureTemplate.StructureEntityInfo(info.pos, info.blockPos, at);
        }
        if (!id.equals("minecraft:item_frame") && !id.equals("minecraft:glow_item_frame")) return info;
        CompoundTag item = info.nbt.getCompound("Item");
        boolean empty = item.isEmpty() || item.getString("id").equals("minecraft:air");
        boolean placeholder = item.getString("id").equals("minecraft:written_book") && !item.getCompound("tag").contains("pages");
        if (!empty && !placeholder) return info;
        RandomSource r = settings.getRandom(info.blockPos);
        if (r.nextFloat() > (books.equals("merlin") ? 1.0f : books.equals("cart") ? 0.35f : FRAME_BOOK)) {
            if (!placeholder) return info;
            CompoundTag bare = info.nbt.copy(); bare.remove("Item");               // a template's placeholder book: an empty frame
            return new StructureTemplate.StructureEntityInfo(info.pos, info.blockPos, bare);
        }
        CompoundTag nbt = info.nbt.copy();
        nbt.put("Item", LoreBooks.stack(LoreBooks.random(r, books)).save(new CompoundTag()));
        return new StructureTemplate.StructureEntityInfo(info.pos, info.blockPos, nbt);
    }

    @Override protected StructureProcessorType<?> getType() { return AncientPeaksLore.LOOT.get(); }
}
