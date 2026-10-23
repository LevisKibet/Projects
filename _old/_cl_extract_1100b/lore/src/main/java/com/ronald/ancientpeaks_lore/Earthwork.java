package com.ronald.ancientpeaks_lore;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Seats a building, field or church on the land the way a builder would: the plot is cut level into the hillside
 * (nothing of the hill is left over the field or the floor), made up with earth where the land falls away (no exposed
 * footing), and the edges are banked with turf so the plot blends into the slope round it.
 * Template layer 1 is the ground of all our buildings and fields (layer 0 is the footing under it).
 */
final class Earthwork {
    private Earthwork() {}
    /** The template layer that lies at the level of the land round it (layer 0 is footing under it). */
    static final int GROUND = 1;

    static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CLAY)
                || s.is(BlockTags.LEAVES) || (s.canBeReplaced() && s.getFluidState().isEmpty() && !s.isAir());
    }

    private static boolean soft(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || (s.canBeReplaced()) || s.is(BlockTags.LEAVES);
    }

    /** Before the template goes down: cut the hill above the ground layer, make up the land below it. */
    static void level(WorldGenLevel level, BoundingBox box, StructureTemplate t, StructurePlaceSettings ps, BlockPos origin, int maxCut) {
        var size = t.getSize();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < size.getX(); lx++) for (int lz = 0; lz < size.getZ(); lz++) {
            BlockPos g = StructureTemplate.calculateRelativePosition(ps, new BlockPos(lx, GROUND, lz)).offset(origin);
            if (!box.isInside(g.getX(), box.minY(), g.getZ())) continue;
            int gy = g.getY();
            // cut: everything natural above the ground layer, up to maxCut blocks
            for (int y = gy + 1; y <= gy + maxCut + 16; y++) {
                m.set(g.getX(), y, g.getZ());
                BlockState s = level.getBlockState(m);
                if (s.isAir()) continue;
                boolean tree = s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || (s.canBeReplaced() && s.getFluidState().isEmpty());
                if (tree || (y <= gy + maxCut && natural(s))) level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);   // trees on the plot go too
            }
            // make up: earth under the ground layer where the land falls away
            long h = g.asLong() * 0x9E3779B97F4A7C15L;
            for (int y = gy, n = 0; n < 24 && y > level.getMinBuildHeight(); y--, n++) {
                m.set(g.getX(), y, g.getZ());
                BlockState s = level.getBlockState(m);
                if (!soft(s)) break;
                int k = (int) Math.floorMod(h + y * 7919L, 10L);
                BlockState put = y == gy ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : (gy - y < 4 || k < 3) ? Blocks.DIRT.defaultBlockState() : (k < 8 ? Blocks.STONE : Blocks.ANDESITE).defaultBlockState();
                level.setBlock(m, put, 2);
            }
        }
    }

    /** After: turf over freshly cut ground, and banks of earth sloping down from the plot's edge where the land is lower. */
    static void bank(WorldGenLevel level, BoundingBox box, StructureTemplate t, StructurePlaceSettings ps, BlockPos origin) {
        var size = t.getSize();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        java.util.Map<Long, Integer> edge = new java.util.HashMap<>();   // column -> ground y of the plot
        for (int lx = 0; lx < size.getX(); lx++) for (int lz = 0; lz < size.getZ(); lz++) {
            BlockPos g = StructureTemplate.calculateRelativePosition(ps, new BlockPos(lx, GROUND, lz)).offset(origin);
            edge.put(BlockPos.asLong(g.getX(), 0, g.getZ()), g.getY());
            if (!box.isInside(g.getX(), box.minY(), g.getZ())) continue;
            m.set(g.getX(), g.getY(), g.getZ());
            BlockState s = level.getBlockState(m);
            if ((s.is(Blocks.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD)) && level.getBlockState(m.above()).isAir())
                level.setBlock(m, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        }
        // banks: up to 12 blocks out from the plot, falling about a block and a half per block out (unevenly, so the
        // mound under a plot on a rise or a cliff edge spreads out like a natural spur instead of a square pillar)
        for (int dist = 1; dist <= 12; dist++) {
            java.util.Map<Long, Integer> ring = new java.util.HashMap<>();
            for (var e : edge.entrySet()) {
                int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey());
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long k = BlockPos.asLong(x + o[0], 0, z + o[1]);
                    if (edge.containsKey(k)) continue;
                    ring.merge(k, e.getValue() - 1, Math::max);
                }
            }
            for (var e : ring.entrySet()) {
                int x = BlockPos.getX(e.getKey()), z = BlockPos.getZ(e.getKey()), top = e.getValue();
                long h = e.getKey() * 0x9E3779B97F4A7C15L;
                if (dist > 1 && Math.floorMod(h >>> 3, 20L) < 9) top--;       // a less even bank (the same in every chunk)
                e.setValue(top);
                if (!box.isInside(x, box.minY(), z)) continue;
                // uphill: batter the cut face back, a block up per block out
                int cutFrom = top + 2 * dist + (Math.floorMod(h >> 7, 2L) == 0 ? 0 : 1);
                boolean cut = false;
                if (dist <= 6)
                for (int y = cutFrom + 10; y >= cutFrom; y--) {
                    m.set(x, y, z);
                    BlockState s = level.getBlockState(m);
                    if (!s.isAir() && natural(s)) { level.setBlock(m, Blocks.AIR.defaultBlockState(), 2); cut = true; }
                }
                if (cut) {
                    m.set(x, cutFrom - 1, z);
                    BlockState s = level.getBlockState(m);
                    if (s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD)) level.setBlock(m, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
                for (int y = top, n = 0; n < 24; y--, n++) {
                    m.set(x, y, z);
                    BlockState s = level.getBlockState(m);
                    if (!soft(s)) break;
                    level.setBlock(m, y == top ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2);
                    if (y == top) {
                        BlockState up = level.getBlockState(m.above());
                        if (!up.isAir() && up.canBeReplaced()) level.setBlock(m.above(), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            edge.putAll(ring);
        }
    }

    /**
     * Plants and tree trunks left hanging once a plot was cut away under them (a neighbouring chunk planted them on the
     * old ground): within the piece and a few blocks round it, anything with no footing goes - flowers, grass, saplings,
     * and natural trunks standing on air (with their logs above). Leaves left behind decay on their own.
     */
    static void sweep(WorldGenLevel level, BoundingBox chunkBox, BoundingBox piece) {
        int x0 = Math.max(chunkBox.minX(), piece.minX() - 4), x1 = Math.min(chunkBox.maxX(), piece.maxX() + 4);
        int z0 = Math.max(chunkBox.minZ(), piece.minZ() - 4), z1 = Math.min(chunkBox.maxZ(), piece.maxZ() + 4);
        int y0 = Math.max(level.getMinBuildHeight() + 1, piece.minY() - 2), y1 = Math.min(level.getMaxBuildHeight() - 2, piece.maxY() + 24);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                m.set(x, y, z);
                BlockState s = level.getBlockState(m);
                if (s.isAir()) continue;
                BlockState below = level.getBlockState(m.below());
                if (!below.isAir()) continue;
                boolean plant = s.getFluidState().isEmpty() && (s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(Blocks.GRASS) || s.is(Blocks.FERN)
                        || s.is(Blocks.TALL_GRASS) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.DEAD_BUSH)
                        || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.PINK_PETALS) || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM));
                if (plant) {
                    level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
                    BlockState up = level.getBlockState(m.above());
                    if (up.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) level.setBlock(m.above(), Blocks.AIR.defaultBlockState(), 2);
                    continue;
                }
                // a trunk standing on nothing: a natural (upright, unstripped) log with more log or leaves above
                if (s.is(BlockTags.LOGS) && !s.getBlock().getDescriptionId().contains("stripped")
                        && s.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                        && s.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == net.minecraft.core.Direction.Axis.Y) {
                    BlockState up = level.getBlockState(m.above());
                    if (!(up.is(BlockTags.LOGS) || up.is(BlockTags.LEAVES))) continue;
                    if (!naturalTrunkAt(level, m)) continue;
                    for (int yy = y; yy <= y1 + 8; yy++) {
                        m.set(x, yy, z);
                        if (!level.getBlockState(m).is(BlockTags.LOGS)) break;
                        level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** A tree's trunk rather than a beam of a building: leaves within three blocks of its top. */
    private static boolean naturalTrunkAt(WorldGenLevel level, BlockPos start) {
        BlockPos.MutableBlockPos q = start.mutable();
        int n = 0;
        while (level.getBlockState(q).is(BlockTags.LOGS) && n++ < 30) q.move(0, 1, 0);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 1; dy++) {
            BlockState b = level.getBlockState(q.offset(dx, dy, dz));
            if (b.is(BlockTags.LEAVES) && b.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    && !b.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) return true;
        }
        return false;
    }
}
