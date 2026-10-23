package com.ronald.ancientpeaks_lore;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Air in a template only clears ground INSIDE the building: under its roof, walls or floor in that column. The rest of
 * the template's box (the air round the outside and above a ruin's open rooms) is skipped, so the hill isn't cut away.
 */
public class OutsideAirProcessor extends StructureProcessor {
    public static final Codec<OutsideAirProcessor> CODEC = Codec.unit(() -> new OutsideAirProcessor(Shape.EMPTY));

    /** Per template column (packed x/z): the highest and lowest non-air layer. */
    public record Shape(Map<Long, int[]> columns) {
        static final Shape EMPTY = new Shape(Map.of());
        int[] at(int x, int z) { return columns.get(((long) x << 32) ^ (z & 0xffffffffL)); }
    }

    private static final Map<StructureTemplate, Shape> SHAPES = Collections.synchronizedMap(new WeakHashMap<>());

    static Shape shape(StructureTemplate t) {
        if (t == null) return Shape.EMPTY;
        return SHAPES.computeIfAbsent(t, k -> {
            CompoundTag tag = k.save(new CompoundTag());
            ListTag palette = tag.contains("palette") ? tag.getList("palette", Tag.TAG_COMPOUND)
                    : tag.getList("palettes", Tag.TAG_LIST).isEmpty() ? new ListTag() : tag.getList("palettes", Tag.TAG_LIST).getList(0);
            boolean[] air = new boolean[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                String n = palette.getCompound(i).getString("Name");
                air[i] = n.equals("minecraft:air") || n.equals("minecraft:cave_air") || n.equals("minecraft:void_air") || n.equals("minecraft:structure_void");
            }
            Map<Long, int[]> cols = new HashMap<>();
            ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag b = blocks.getCompound(i);
                int s = b.getInt("state");
                if (s < 0 || s >= air.length || air[s]) continue;
                ListTag p = b.getList("pos", Tag.TAG_INT);
                int x = p.getInt(0), y = p.getInt(1), z = p.getInt(2);
                cols.merge(((long) x << 32) ^ (z & 0xffffffffL), new int[]{y, y}, (a, c) -> new int[]{Math.max(a[0], c[0]), Math.min(a[1], c[1])});
            }
            return new Shape(cols);
        });
    }

    private final Shape shape;
    public OutsideAirProcessor(Shape shape) { this.shape = shape; }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos,
                                                             StructureTemplate.StructureBlockInfo original,
                                                             StructureTemplate.StructureBlockInfo placed, StructurePlaceSettings settings) {
        if (placed == null || !original.state().isAir()) return placed;
        BlockPos lp = original.pos();
        int[] c = shape.at(lp.getX(), lp.getZ());
        return c != null && lp.getY() <= c[0] ? placed : null;
    }

    @Override
    protected StructureProcessorType<?> getType() { return AncientPeaksLore.OUTSIDE_AIR.get(); }
}
