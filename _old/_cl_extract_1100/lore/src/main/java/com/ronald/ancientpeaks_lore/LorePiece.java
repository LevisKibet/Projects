package com.ronald.ancientpeaks_lore;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Map;

/**
 * One structure template placed whole, rotated about its own centre. Its air only clears ground inside the building
 * (see {@link OutsideAirProcessor}); with "fill" on, the ground floor gets a rough cobble footing down to the ground
 * wherever the land falls away beneath it, so nothing hangs in the air and no box is cut into the hill.
 */
public class LorePiece extends TemplateStructurePiece {
    private final boolean fill;

    static StructurePlaceSettings settings(StructureTemplateManager mgr, ResourceLocation id, Rotation rot, BlockPos pivot) {
        StructureTemplate t = mgr.get(id).orElse(null);
        return new StructurePlaceSettings().setRotation(rot).setRotationPivot(pivot)
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK)
                .addProcessor(new OutsideAirProcessor(OutsideAirProcessor.shape(t)))
                .addProcessor(new LootProcessor(LootProcessor.groupFor(id.getPath()), barrelChance(t)))
                .setFinalizeEntities(true);
    }

    private static final java.util.Map<StructureTemplate, Float> BARRELS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** About a third of the barrels are stocked, never more than two or three a building: the rest are decor (or building blocks). */
    static float barrelChance(StructureTemplate t) {
        if (t == null) return 0.3f;
        return BARRELS.computeIfAbsent(t, k -> {
            CompoundTag tag = k.save(new CompoundTag());
            var pal = tag.getList("palette", 10);
            java.util.Set<Integer> barrel = new java.util.HashSet<>();
            for (int i = 0; i < pal.size(); i++) if (pal.getCompound(i).getString("Name").equals("minecraft:barrel")) barrel.add(i);
            int n = 0;
            var blocks = tag.getList("blocks", 10);
            for (int i = 0; i < blocks.size(); i++) if (barrel.contains(blocks.getCompound(i).getInt("state"))) n++;
            return n == 0 ? 0.3f : Math.min(0.3f, 2.5f / n);
        });
    }

    public LorePiece(StructureTemplateManager mgr, ResourceLocation id, BlockPos origin, Rotation rot, BlockPos pivot, boolean fill) {
        super(AncientPeaksLore.PIECE.get(), 0, mgr, id, id.toString(), settings(mgr, id, rot, pivot), origin);
        this.fill = fill;
    }

    public LorePiece(StructurePieceSerializationContext ctx, CompoundTag tag) {
        super(AncientPeaksLore.PIECE.get(), tag, ctx.structureTemplateManager(),
                rl -> settings(ctx.structureTemplateManager(), rl, Rotation.valueOf(tag.getString("Rot")), new BlockPos(tag.getInt("PX"), 0, tag.getInt("PZ"))));
        this.fill = tag.getBoolean("Fill");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext ctx, CompoundTag tag) {
        super.addAdditionalSaveData(ctx, tag);
        tag.putString("Rot", placeSettings.getRotation().name());
        tag.putInt("PX", placeSettings.getRotationPivot().getX());
        tag.putInt("PZ", placeSettings.getRotationPivot().getZ());
        tag.putBoolean("Fill", fill);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager sm, ChunkGenerator gen, RandomSource random, BoundingBox box, ChunkPos cp, BlockPos pivot) {
        String name = templateName.toLowerCase(java.util.Locale.ROOT);
        boolean earth = fill && (name.contains(":village/") || name.contains(":churches/") || name.contains(":wizard_tower/") || name.contains(":carts/"));
        if (earth) Earthwork.level(level, box, template, placeSettings, templatePosition, name.contains(":churches/") ? 14 : 8);
        super.postProcess(level, sm, gen, random, box, cp, pivot);
        if (name.contains("churches/church_merlin")) { hideMerlin(level, box); cabinRaiders(level, box); }
        else if (name.contains("churches/") && name.contains("besieged")) placeMerlin(level, box);
        if (name.contains("churches/") && !name.contains("merlin")) churchLectern(level, box, name);   // his cabin keeps only his own journal
        if (name.contains("carts/")) cartRaiders(level, box);
        if (name.contains("swords/") && name.contains("pedestal")) placeSword(level, box, template, placeSettings, templatePosition);
        if (earth) {
            Earthwork.bank(level, box, template, placeSettings, templatePosition);
            if (name.contains(":village/farms/")) haystacks(level, box);
            Earthwork.sweep(level, box, getBoundingBox());
            return;
        }
        if (!fill) { Earthwork.sweep(level, box, getBoundingBox()); return; }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Map.Entry<Long, int[]> e : OutsideAirProcessor.shape(template).columns().entrySet()) {
            int lowest = e.getValue()[1];
            if (lowest > 1) continue;                                   // only the ground floor stands on the land
            int lx = (int) (e.getKey() >> 32), lz = (int) (long) e.getKey();
            BlockPos w = StructureTemplate.calculateRelativePosition(placeSettings, new BlockPos(lx, lowest, lz)).offset(templatePosition);
            if (!box.isInside(w)) continue;
            long h = w.asLong() * 0x9E3779B97F4A7C15L;
            for (int y = w.getY() - 1, n = 0; n < 14 && y > level.getMinBuildHeight(); y--, n++) {
                m.set(w.getX(), y, w.getZ());
                BlockState st = level.getBlockState(m);
                if (!(st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty())) break;
                int k = (int) Math.floorMod(h + y * 7919L, 10L);
                level.setBlock(m, (k < 4 ? Blocks.MOSSY_COBBLESTONE : k < 9 ? Blocks.COBBLESTONE : Blocks.DIRT).defaultBlockState(), 2);
            }
        }
        Earthwork.sweep(level, box, getBoundingBox());
    }

    /**
     * A lectern with the chapel's own book, near the middle of the floor: in Merlin's the sanctuary's guest ledger
     * (the old man in the grey cloak), in an overgrown ruin the last account of it, in a burnt-out one the last page
     * written in the bell loft.
     */
    private void churchLectern(WorldGenLevel level, BoundingBox box, String name) {
        var size = template.getSize();
        BlockPos mid = StructureTemplate.calculateRelativePosition(placeSettings, new BlockPos(size.getX() / 2 + 2, 1, size.getZ() / 2 + 2)).offset(templatePosition);
        if (!box.isInside(mid)) return;
        long h = templatePosition.asLong() * 0x9E3779B97F4A7C15L;
        int book = name.contains("merlin") ? (Math.floorMod(h, 3L) == 0 ? 21 : 22)
                : name.contains("rubble") ? (Math.floorMod(h, 2L) == 0 ? 19 : 17)
                : name.contains("overgrown") ? (Math.floorMod(h, 2L) == 0 ? 20 : 16) : 16 + (int) Math.floorMod(h, 7L);
        for (int r = 0; r <= 6; r++)
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int dy = 0; dy <= 5; dy++) {
                    BlockPos p = mid.offset(dx, dy, dz);
                    if (!box.isInside(p)) continue;
                    if (!level.getBlockState(p).isAir() || !free(level, p.above())) continue;
                    BlockState below = level.getBlockState(p.below());
                    if (below.getCollisionShape(level, p.below()).isEmpty() || !below.getFluidState().isEmpty()) continue;
                    net.minecraft.core.Direction face = net.minecraft.core.Direction.from2DDataValue((int) Math.floorMod(h >>> 5, 4L));
                    level.setBlock(p, Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, face)
                            .setValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK, true), 2);
                    if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.LecternBlockEntity le)
                        le.setBook(LoreBooks.stack(LoreBooks.byNumber(book)));
                    return;
                }
            }
    }

    /** Half the raided carts still have the raiders by them, picking through what's left. */
    private void cartRaiders(WorldGenLevel level, BoundingBox box) {
        var size = template.getSize();
        BlockPos mid = StructureTemplate.calculateRelativePosition(placeSettings, new BlockPos(size.getX() / 2, 2, size.getZ() / 2)).offset(templatePosition);
        if (!box.isInside(mid)) return;
        long h = templatePosition.asLong() * 0x9E3779B97F4A7C15L;
        if (Math.floorMod(h, 2L) == 0) return;
        int n = 2 + (int) Math.floorMod(h >>> 7, 2L);
        for (int i = 0; i < n; i++) {
            SaxonRaider m = LoreContent.SAXON_RAIDER.get().create(level.getLevel());
            if (m == null) return;
            int dx = (i % 2 == 0 ? 3 : -3) + i, dz = i - 1;
            BlockPos at = mid.offset(dx, 0, dz);
            for (int y = at.getY() + 3; y >= at.getY() - 3; y--) {
                BlockPos q = new BlockPos(at.getX(), y, at.getZ());
                if (free(level, q) && free(level, q.above()) && !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty()) { at = q; break; }
            }
            m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, (float) (h % 360), 0);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), net.minecraft.world.entity.MobSpawnType.STRUCTURE, null, null);
            m.setPersistenceRequired();
            level.addFreshEntity(m);
        }
    }

    /** A few hay bales stacked by the edge of a field, as if left from the harvest. */
    private void haystacks(WorldGenLevel level, BoundingBox box) {
        var size = template.getSize();
        long h = templatePosition.asLong() * 0x9E3779B97F4A7C15L;
        int n = 2 + (int) Math.floorMod(h >>> 9, 3L);
        for (int i = 0; i < n; i++) {
            long k = h ^ (i * 0xC2B2AE3D27D4EB4FL); k ^= k >>> 29;
            int side = (int) Math.floorMod(k, 4L), t = (int) Math.floorMod(k >>> 8, 1000L);
            int lx, lz;
            switch (side) {
                case 0 -> { lx = -2; lz = t % size.getZ(); }
                case 1 -> { lx = size.getX() + 1; lz = t % size.getZ(); }
                case 2 -> { lz = -2; lx = t % size.getX(); }
                default -> { lz = size.getZ() + 1; lx = t % size.getX(); }
            }
            BlockPos base = StructureTemplate.calculateRelativePosition(placeSettings, new BlockPos(lx, Earthwork.GROUND, lz)).offset(templatePosition);
            for (int[] o : new int[][]{{0, 0}, {1, 0}, {0, 1}}) {
                if (o[0] != 0 && Math.floorMod(k >>> 20, 2L) == 0) continue;
                if (o[1] != 0 && Math.floorMod(k >>> 21, 2L) == 0) continue;
                BlockPos c = base.offset(o[0], 0, o[1]);
                if (!box.isInside(c.getX(), box.minY(), c.getZ())) continue;
                for (int y = c.getY() + 3; y >= c.getY() - 3; y--) {
                    BlockPos at = new BlockPos(c.getX(), y, c.getZ());
                    BlockState below = level.getBlockState(at.below()), here = level.getBlockState(at);
                    if (!(here.isAir() || (here.canBeReplaced() && here.getFluidState().isEmpty()))) continue;
                    if (!below.isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP) || !below.getFluidState().isEmpty()) continue;
                    level.setBlock(at, Blocks.HAY_BLOCK.defaultBlockState(), 2);
                    if (o[0] == 0 && o[1] == 0 && Math.floorMod(k >>> 30, 3L) == 0 && level.getBlockState(at.above()).isAir())
                        level.setBlock(at.above(), Blocks.HAY_BLOCK.defaultBlockState(), 2);
                    break;
                }
            }
        }
    }

    // Merlin's cabin (churches/church_merlin, furnished by tools/furnish_merlin.py, which printed these spots):
    /** where he hides upstairs, his back to the wall (template x, y, z), and which way the wall is */
    static final int[] MERLIN_SPOT = {7, 8, 16};
    static final net.minecraft.core.Direction MERLIN_BACK = net.minecraft.core.Direction.WEST;
    /** the raiders who got in, on the ground floor (template x, z; standing at y 3) */
    static final int[][] CABIN_RAIDERS = {{13, 13}, {21, 12}, {8, 14}, {12, 9}};

    /** Merlin hides upstairs in his cabin, slumped against the far wall beside his journal. */
    private void hideMerlin(WorldGenLevel level, BoundingBox box) {
        BlockPos spot = StructureTemplate.calculateRelativePosition(placeSettings, new BlockPos(MERLIN_SPOT[0], MERLIN_SPOT[1], MERLIN_SPOT[2])).offset(templatePosition);
        if (!box.isInside(spot)) return;
        net.minecraft.core.Direction back = placeSettings.getRotation().rotate(MERLIN_BACK);
        Merlin m = LoreContent.MERLIN.get().create(level.getLevel());
        if (m == null) return;
        net.minecraft.core.Direction face = back.getOpposite();
        float yaw = (float) (Math.atan2(-face.getStepX(), face.getStepZ()) * 180 / Math.PI);
        var floor = level.getBlockState(spot.below()).getCollisionShape(level, spot.below());
        double fy = floor.isEmpty() ? spot.getY() : spot.getY() - 1 + floor.max(net.minecraft.core.Direction.Axis.Y);
        m.moveTo(spot.getX() + 0.5 + back.getStepX() * 0.18, fy, spot.getZ() + 0.5 + back.getStepZ() * 0.18, yaw, 0);
        m.setYHeadRot(yaw); m.setYBodyRot(yaw);
        m.setPersistenceRequired();
        level.addFreshEntity(m);
        AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] Merlin hides upstairs in his cabin at {}", spot.toShortString());
    }

    /** Saxon raiders have broken into the cabin's hall below and are turning it over - they haven't found the stair. */
    private void cabinRaiders(WorldGenLevel level, BoundingBox box) {
        long h = templatePosition.asLong() * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < CABIN_RAIDERS.length; i++) {
            BlockPos at = StructureTemplate.calculateRelativePosition(placeSettings, new BlockPos(CABIN_RAIDERS[i][0], 3, CABIN_RAIDERS[i][1])).offset(templatePosition);
            if (!box.isInside(at)) continue;
            SaxonRaider m = LoreContent.SAXON_RAIDER.get().create(level.getLevel());
            if (m == null) return;
            m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, (float) Math.floorMod(h >>> (i * 8), 360L), 0);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), net.minecraft.world.entity.MobSpawnType.STRUCTURE, null, null);
            m.setPersistenceRequired();
            level.addFreshEntity(m);
        }
    }

    /** Merlin lies wounded near the middle of his chapel floor (once: only the chunk holding the middle does it). */
    private void placeMerlin(WorldGenLevel level, BoundingBox box) {
        var size = template.getSize();
        BlockPos mid = StructureTemplate.calculateRelativePosition(placeSettings, new BlockPos(size.getX() / 2, 1, size.getZ() / 2)).offset(templatePosition);
        if (!box.isInside(mid)) return;
        // the nearest floor spot with a wall at his back (solid at body and head height), nearest the middle first
        BlockPos spot = null; net.minecraft.core.Direction back = null;
        search:
        for (int r = 0; r <= 9; r++)
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int dy = 0; dy <= 5; dy++) {
                    BlockPos p = mid.offset(dx, dy, dz);
                    if (!box.isInside(p.offset(-1, 0, -1)) || !box.isInside(p.offset(1, 0, 1))) continue;   // only what this chunk has built
                    if (!free(level, p) || !free(level, p.above())
                            || level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) continue;
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        if (solid(level, p.relative(d)) && solid(level, p.above().relative(d)) && free(level, p.relative(d.getOpposite()))) {
                            spot = p; back = d; break search;
                        }
                    }
                }
            }
        if (spot == null) { spot = mid; back = net.minecraft.core.Direction.NORTH; }
        Merlin m = LoreContent.MERLIN.get().create(level.getLevel());
        if (m == null) return;
        net.minecraft.core.Direction face = back.getOpposite();
        float yaw = (float) (Math.atan2(-face.getStepX(), face.getStepZ()) * 180 / Math.PI);
        // his back against the wall: a little off the block's middle toward it
        var floor = level.getBlockState(spot.below()).getCollisionShape(level, spot.below());
        double fy = floor.isEmpty() ? spot.getY() : spot.getY() - 1 + floor.max(net.minecraft.core.Direction.Axis.Y);   // on a slab floor, on the slab
        m.moveTo(spot.getX() + 0.5 + back.getStepX() * 0.18, fy, spot.getZ() + 0.5 + back.getStepZ() * 0.18, yaw, 0);
        m.setYHeadRot(yaw); m.setYBodyRot(yaw);
        m.setPersistenceRequired();
        level.addFreshEntity(m);
        AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] Merlin leans wounded against his chapel wall at {}", spot.toShortString());
    }

    static boolean free(WorldGenLevel level, BlockPos p) { return level.getBlockState(p).getCollisionShape(level, p).isEmpty(); }
    private static boolean solid(WorldGenLevel level, BlockPos p) { return level.getBlockState(p).isCollisionShapeFullBlock(level, p); }

    /** The plain stone at the top of the pedestal becomes the stone with Excalibur in it. */
    static BlockPos placeSword(WorldGenLevel level, BoundingBox box, StructureTemplate template, StructurePlaceSettings placeSettings, BlockPos templatePosition) {
        var size = template.getSize();
        BlockPos mid = StructureTemplate.calculateRelativePosition(placeSettings, new BlockPos(size.getX() / 2, 0, size.getZ() / 2)).offset(templatePosition);
        BlockPos best = null;
        for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(templatePosition, placeSettings, Blocks.STONE)) {
            BlockPos p = info.pos();
            if (Math.abs(p.getX() - mid.getX()) > 2 || Math.abs(p.getZ() - mid.getZ()) > 2) continue;
            if (best == null || p.getY() > best.getY()) best = p;
        }
        if (best == null || !box.isInside(best)) return null;
        level.setBlock(best, LoreContent.SWORD_IN_STONE.get().defaultBlockState(), 2);
        placeStatue(level, best);
        BlockPos above = best.above();
        if (!level.getBlockState(above).isAir() && level.getBlockState(above).canBeReplaced()) level.setBlock(above, Blocks.AIR.defaultBlockState(), 2);
        AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] Excalibur waits in the stone at {}", best.toShortString());
        return best;
    }

    /** Vortigern in stone, two blocks off the sword, reaching for it. */
    static boolean placeStatue(WorldGenLevel level, BlockPos stone) {
        net.minecraft.core.Direction[] dirs = {net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.NORTH};
        int start = Math.floorMod((int) (stone.asLong() * 31), 4);
        for (int k = 0; k < 4; k++) {
            net.minecraft.core.Direction d = dirs[(start + k) % 4];
            BlockPos col = stone.relative(d, 2);
            for (int y = stone.getY() + 1; y >= stone.getY() - 3; y--) {
                BlockPos feet = new BlockPos(col.getX(), y, col.getZ());
                if (!free(level, feet) || !free(level, feet.above()) || !free(level, feet.above(2))) continue;
                if (level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) continue;
                VortigernStatue st = LoreContent.VORTIGERN_STATUE.get().create(level.getLevel());
                if (st == null) return false;
                net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                float yaw = VortigernStatue.facing(at, net.minecraft.world.phys.Vec3.atCenterOf(stone));
                st.moveTo(at.x, at.y, at.z, yaw, 0);
                st.setYHeadRot(yaw); st.setYBodyRot(yaw);
                level.addFreshEntity(st);
                AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] Vortigern stands in stone beside the sword at {}", feet.toShortString());
                return true;
            }
        }
        return false;
    }

    @Override
    protected void handleDataMarker(String name, BlockPos pos, ServerLevelAccessor level, RandomSource random, BoundingBox box) { }
}
