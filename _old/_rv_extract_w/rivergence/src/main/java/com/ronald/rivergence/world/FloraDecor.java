package com.ronald.rivergence.world;

import com.ronald.rivergence.plan.Planner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * Life in and round the water, after the biome's own plants and trees are in.
 *
 * In the water: seagrass in meadows, tall seagrass where it's deeper, short kelp and the odd sea pickle in still deep
 * water, a sea lantern glowing in the bed here and there where water gathers (lakes, plunge pools, springs); lily pads
 * on calm water; leaves trailing in the water at the edges.
 * On the banks: moss and moss carpet, big and small dripleaf at the waterline, ferns and tall grass, azalea bushes and
 * now and then an azalea tree; sugar cane where it's warm; glow berries hanging from any overhang above the water.
 * How much grows follows the biome: lush where it's wet, sparse where it's cold or dry.
 */
final class FloraDecor {
    private FloraDecor() {}

    static void run(WorldGenLevel level, ChunkPos cp) {
        Planner planner = WaterPlacer.planner(level);
        if (planner == null) return;
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        Planner.Column c = new Planner.Column();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        TreeClear.run(level, planner, cp);
        if (!planner.mayHaveWater(cp.x, cp.z)) return;                                   // trees grown into the water (this chunk's and its neighbours')
        RandomSource r = new WorldgenRandom(new XoroshiroRandomSource(level.getSeed() ^ cp.toLong() * 0x5DEECE66DL ^ 0x2F7A1L));
        for (int a = 0; a < 16; a++) for (int b = 0; b < 16; b++) {
            int x = x0 + a, z = z0 + b;
            planner.column(x, z, c, true);
            if (c.plan == null || c.nearLevel == Planner.Column.DRY && !c.wet() && !c.wetland && c.oasis == null) continue;
            if (c.oasis != null && !c.wet()) { OasisDecor.ring(level, c, x, z, r, m); continue; }
            BiomeProfile prof = BiomeProfile.of(level.getBiome(m.set(x, Math.max(c.water, c.nearLevel), z)));
            BankDecor.Kind k = prof.kind;
            double lush = prof.lush;
            if (c.wet()) water(level, c, x, z, k, lush, r, m);
            else if (c.wetland) hummock(level, c, x, z, k, r, m);
            else bank(level, c, x, z, k, lush, prof, r, m);
            if (k != BankDecor.Kind.COLD && k != BankDecor.Kind.SANDY && k != BankDecor.Kind.BADLANDS && (c.wet() || c.edge < 4)) overhang(level, c, x, z, r, m);
        }
    }

    // ------------------------------------------------------------------ in the water

    private static void water(WorldGenLevel level, Planner.Column c, int x, int z, BankDecor.Kind k, double lush, RandomSource r, BlockPos.MutableBlockPos m) {
        int top = c.water;
        if (!level.getFluidState(m.set(x, top, z)).is(FluidTags.WATER)) return;
        int bed = top;
        while (bed > top - 16 && level.getFluidState(m.set(x, bed, z)).is(FluidTags.WATER)) bed--;
        int depth = top - bed;                                   // water blocks over the bed
        if (depth < 1) return;
        boolean still = c.lake || c.poolQ < 1 || c.springQ < 1 || c.wetland || c.oasis != null || c.bay;
        if (c.oasis != null) lush = 1.2;                                     // an oasis is green whatever the desert round it
        boolean wide = c.channel && c.hw >= 3.5;
        BlockState bedSt = level.getBlockState(m.set(x, bed, z));
        boolean soft = bedSt.is(BlockTags.DIRT) || bedSt.is(Blocks.GRAVEL) || bedSt.is(BlockTags.SAND) || bedSt.is(Blocks.CLAY) || bedSt.is(Blocks.MUD) || bedSt.is(Blocks.MOSS_BLOCK);
        double meadow = (Planner.valueNoise(x / 6.0, z / 6.0, 91) + 1) / 2;   // seagrass grows in patches
        double f = r.nextDouble();
        BlockPos above = new BlockPos(x, bed + 1, z);
        // a sea lantern in the bed, where water gathers
        if (still && f < (c.poolQ < 1 ? 0.025 : c.springQ < 1 ? 0.02 : 0.0035) && depth >= 2 && bedSt.isSolidRender(level, m)) {
            level.setBlock(m.set(x, bed, z), Blocks.SEA_LANTERN.defaultBlockState(), 2);
            return;
        }
        if (soft && level.getBlockState(above).is(Blocks.WATER)) {
            double grass = (still ? 0.14 + 0.45 * meadow : c.hw < 1.4 ? 0.0 : 0.04 + 0.2 * meadow) * lush;
            double kelp = (still ? 0.05 : wide ? 0.02 : 0) * lush;
            if (f < kelp && depth >= 3) {
                int h = 1 + r.nextInt(Math.min(3, depth - 1));                       // short kelp, never to the surface
                for (int i = 1; i <= h; i++)
                    level.setBlock(m.set(x, bed + i, z), i == h ? Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, 25) : Blocks.KELP_PLANT.defaultBlockState(), 2);
            } else if (f < kelp + grass) {
                if (depth >= 2 && r.nextFloat() < 0.3) {
                    level.setBlock(above, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.LOWER), 2);
                    level.setBlock(above.above(), Blocks.TALL_SEAGRASS.defaultBlockState().setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER), 2);
                } else level.setBlock(above, Blocks.SEAGRASS.defaultBlockState(), 2);
            } else if (still && depth >= 2 && f < kelp + grass + 0.012 * lush) {
                level.setBlock(above, Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 1 + r.nextInt(3)), 2);
            } else if (depth == 1 && (still || c.dist > c.hw * 0.6) && k != BankDecor.Kind.COLD && k != BankDecor.Kind.SANDY && f < kelp + grass + 0.05 * lush && wildRice(level, above, r)) {
                // Farmer's Delight's wild rice, standing in the shallows
            } else if ((still || c.hw < 2.5) && depth <= 2 && f < kelp + grass + 0.03 * lush && k != BankDecor.Kind.COLD) {
                smallDripleaf(level, above, r);                                         // dripleaf in the shallows
            }
        }
        // the surface
        BlockPos surf = new BlockPos(x, top, z), air = surf.above();
        if (!level.getBlockState(air).isAir() || !level.getFluidState(surf).isSource()) return;
        boolean calm = still || (wide && c.dist > c.hw * 0.7);
        double nearShore = c.lake ? Math.max(0, 1 - c.lakeSd / 10) : c.channel ? c.dist / Math.max(1, c.hw) : 0.5;
        float g = r.nextFloat();
        if ((still || (c.channel && c.dist < c.hw * 0.8 + 0.3)) && r.nextFloat() < 0.006 && nearCherry(level, x, top, z)) lantern(level, x, top, z, r);
        // lily pads in loose patches with open water between, fewer on small still pools
        double damp = c.beaver ? 0.35 : c.wetland ? 0.45 : c.poolQ < 1 || c.springQ < 1 ? 0.3 : 1;
        double patch = (Planner.valueNoise(x / 7.0, z / 7.0, 93) + 1) / 2;
        double lily = 0.07 * lush * (0.3 + nearShore) * damp * Math.max(0, patch * 2 - 0.5);
        if (calm && !c.bay && k != BankDecor.Kind.COLD && g < lily && padsBeside(level, air) < 2) {
            level.setBlock(air, Blocks.LILY_PAD.defaultBlockState(), 2);
        } else if (edgeOfWater(level, surf) && g < 0.09 * lush) {
            level.setBlock(surf, leaves(k, r).setValue(LeavesBlock.WATERLOGGED, true), 2);   // leaves trailing in the water
        }
    }

    /** Wild rice (with Farmer's Delight): its foot in a block of water, its head in the air above. */
    private static boolean wildRice(WorldGenLevel level, BlockPos at, RandomSource r) {
        Block rice = com.ronald.rivergence.Compat.wildRice();
        if (rice == null || !level.getBlockState(at.above()).isAir()) return false;
        BlockState lower = rice.defaultBlockState(), upper = rice.defaultBlockState();
        if (lower.hasProperty(DoublePlantBlock.HALF)) { lower = lower.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER); upper = upper.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER); }
        if (lower.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))
            lower = lower.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        level.setBlock(at, lower, 2);
        level.setBlock(at.above(), upper, 2);
        return true;
    }

    private static int padsBeside(WorldGenLevel level, BlockPos p) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(p.relative(d)).is(Blocks.LILY_PAD)) n++;
        return n;
    }

    /** In or beside a cherry grove, or where cherry trees grow near the water. */
    private static boolean nearCherry(WorldGenLevel level, int x, int y, int z) {
        for (int dx = -24; dx <= 24; dx += 12) for (int dz = -24; dz <= 24; dz += 12)
            if (BiomeProfile.cherry(level.getBiome(new BlockPos(x + dx, y, z + dz)))) return true;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -8; dx <= 8; dx += 2) for (int dz = -8; dz <= 8; dz += 2) for (int k = 1; k <= 10; k += 2)
            if (level.getBlockState(m.set(x + dx, y + k, z + dz)).is(Blocks.CHERRY_LEAVES)) return true;
        return false;
    }

    /** A paper lantern drifting over the river near the cherry groves: a block of pink or green wool, a lantern hung under it. */
    private static void lantern(WorldGenLevel level, int x, int top, int z, RandomSource r) {
        int y = top + 3 + r.nextInt(7);                                            // drifting at all heights over the water
        BlockPos glass = new BlockPos(x, y, z), lamp = glass.below();
        for (int i = top + 1; i <= y + 1; i++) if (!level.getBlockState(new BlockPos(x, i, z)).isAir()) return;
        float f = r.nextFloat();
        Block g = f < 0.5f ? Blocks.PINK_STAINED_GLASS : f < 0.8f ? Blocks.LIME_STAINED_GLASS : Blocks.GREEN_STAINED_GLASS;
        level.setBlock(glass, g.defaultBlockState(), 2);
        level.setBlock(lamp, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 2);
    }

    private static boolean edgeOfWater(WorldGenLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState st = level.getBlockState(p.relative(d));
            if (st.getFluidState().isEmpty() && st.isSolidRender(level, p.relative(d))) return true;
        }
        return false;
    }

    private static BlockState leaves(BankDecor.Kind k, RandomSource r) {
        Block b = switch (k) {
            case COLD -> Blocks.SPRUCE_LEAVES;
            case WET -> r.nextFloat() < 0.5 ? Blocks.AZALEA_LEAVES : r.nextFloat() < 0.5 ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.JUNGLE_LEAVES;
            case FOREST -> r.nextFloat() < 0.4 ? Blocks.OAK_LEAVES : r.nextFloat() < 0.5 ? Blocks.AZALEA_LEAVES : Blocks.BIRCH_LEAVES;
            default -> r.nextFloat() < 0.5 ? Blocks.AZALEA_LEAVES : r.nextFloat() < 0.6 ? Blocks.OAK_LEAVES : Blocks.FLOWERING_AZALEA_LEAVES;
        };
        return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    // ------------------------------------------------------------------ on the banks

    private static void bank(WorldGenLevel level, Planner.Column c, int x, int z, BankDecor.Kind k, double lush, BiomeProfile prof, RandomSource r, BlockPos.MutableBlockPos m) {
        double reach = 3 + Math.min(5, c.nearWidth * 0.4);
        if (c.edge > reach || c.springQ < 1.9 || (c.pool != null && c.poolQ < 1)) return;
        int y = c.nearLevel + 4;
        BlockState g = null;
        for (; y >= c.nearLevel - 1; y--) {
            g = level.getBlockState(m.set(x, y, z));
            if (!g.isAir() && !g.canBeReplaced()) break;
        }
        if (y < c.nearLevel - 1 || g == null || !g.getFluidState().isEmpty()) return;
        BlockPos ground = new BlockPos(x, y, z), at = ground.above();
        if (!level.getBlockState(at).isAir()) return;
        boolean soil = g.is(Blocks.GRASS_BLOCK) || g.is(BlockTags.DIRT) || g.is(Blocks.MOSS_BLOCK) || g.is(Blocks.MUD) || g.is(Blocks.CLAY);
        boolean sand = g.is(BlockTags.SAND);
        double near = 1 - Math.min(1, c.edge / reach);                    // 1 at the waterline
        boolean wetSide = waterBeside(level, ground) || waterBeside(level, at);
        double f = r.nextDouble();

        if (k == BankDecor.Kind.SANDY || k == BankDecor.Kind.BADLANDS) {
            if ((sand || soil) && wetSide && f < 0.35) cane(level, at, r);
            else if (sand && f < 0.04) level.setBlock(at, Blocks.DEAD_BUSH.defaultBlockState(), 2);
            return;
        }
        if (!soil) {
            if (sand && wetSide && k != BankDecor.Kind.COLD && f < 0.25) cane(level, at, r);
            return;
        }
        if (k == BankDecor.Kind.COLD) {
            if (f < 0.12 * near) level.setBlock(at, (r.nextBoolean() ? Blocks.FERN : Blocks.GRASS).defaultBlockState(), 2);
            return;
        }
        // the biome's own flowers, down to the water's edge
        if (prof.flowers.length > 0 && f < 0.07 * (0.4 + near) * Math.min(1.3, lush) && g.is(Blocks.GRASS_BLOCK)) {
            Block fl = prof.flowers[r.nextInt(prof.flowers.length)];
            if (fl == Blocks.LILAC) doublePlant(level, at, fl);
            else if (fl == Blocks.PINK_PETALS) level.setBlock(at, Blocks.PINK_PETALS.defaultBlockState().setValue(PinkPetalsBlock.AMOUNT, 1 + r.nextInt(4)).setValue(PinkPetalsBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r)), 2);
            else if (fl.defaultBlockState().canSurvive(level, at)) level.setBlock(at, fl.defaultBlockState(), 2);
            return;
        }
        // moss creeping down to the water, in patches
        double mossy = (Planner.valueNoise(x / 4.0, z / 4.0, 92) + 1) / 2;
        if (mossy > 0.62 - 0.15 * near * lush && g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT) && mossy > 0.5) {
            level.setBlock(ground, Blocks.MOSS_BLOCK.defaultBlockState(), 2);
            g = Blocks.MOSS_BLOCK.defaultBlockState();
        }
        double p = f / lush;
        if (wetSide && p < 0.10) bigDripleaf(level, at, r);
        else if (wetSide && p < 0.16) smallDripleaf(level, at, r);
        else if (wetSide && k != BankDecor.Kind.FOREST && p < 0.2) cane(level, at, r);
        else if (p < 0.004 * (1 + near)) azaleaTree(level, at, r);
        else if (p < 0.035 * (0.5 + near)) level.setBlock(at, (r.nextFloat() < 0.3 ? Blocks.FLOWERING_AZALEA : Blocks.AZALEA).defaultBlockState(), 2);
        else if (g.is(Blocks.MOSS_BLOCK) && p < 0.30) level.setBlock(at, Blocks.MOSS_CARPET.defaultBlockState(), 2);
        else if (p < 0.38 * (0.4 + near)) doublePlant(level, at, r.nextFloat() < 0.6 ? Blocks.TALL_GRASS : Blocks.LARGE_FERN);
        else if (p < 0.55 * (0.4 + near)) level.setBlock(at, (r.nextFloat() < 0.55 ? Blocks.FERN : Blocks.GRASS).defaultBlockState(), 2);
    }

    private static boolean waterBeside(WorldGenLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getFluidState(p.relative(d)).is(FluidTags.WATER)) return true;
        return false;
    }

    private static void cane(WorldGenLevel level, BlockPos at, RandomSource r) {
        if (!Blocks.SUGAR_CANE.defaultBlockState().canSurvive(level, at)) return;
        int h = 1 + r.nextInt(3);
        for (int i = 0; i < h && level.getBlockState(at.above(i)).isAir(); i++) level.setBlock(at.above(i), Blocks.SUGAR_CANE.defaultBlockState(), 2);
    }

    private static void doublePlant(WorldGenLevel level, BlockPos at, Block b) {
        if (!level.getBlockState(at.above()).isAir() || !b.defaultBlockState().canSurvive(level, at)) return;
        level.setBlock(at, b.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(at.above(), b.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 2);
    }

    private static void smallDripleaf(WorldGenLevel level, BlockPos at, RandomSource r) {
        if (!level.getBlockState(at.above()).isAir() && !level.getBlockState(at.above()).is(Blocks.WATER)) return;
        boolean wet = level.getFluidState(at).is(FluidTags.WATER);
        if (wet && !level.getBlockState(at.above()).isAir()) return;                 // the leaf must reach the air
        Direction f = Direction.Plane.HORIZONTAL.getRandomDirection(r);
        BlockState lower = Blocks.SMALL_DRIPLEAF.defaultBlockState().setValue(SmallDripleafBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, f).setValue(BlockStateProperties.WATERLOGGED, wet);
        if (!lower.canSurvive(level, at)) return;
        level.setBlock(at, lower, 2);
        level.setBlock(at.above(), lower.setValue(SmallDripleafBlock.HALF, DoubleBlockHalf.UPPER).setValue(BlockStateProperties.WATERLOGGED, false), 2);
    }

    private static void bigDripleaf(WorldGenLevel level, BlockPos at, RandomSource r) {
        Direction f = Direction.Plane.HORIZONTAL.getRandomDirection(r);
        if (!Blocks.BIG_DRIPLEAF.defaultBlockState().canSurvive(level, at)) return;
        int h = r.nextInt(3);
        for (int i = 0; i <= h; i++) if (!level.getBlockState(at.above(i)).isAir()) return;
        for (int i = 0; i < h; i++) level.setBlock(at.above(i), Blocks.BIG_DRIPLEAF_STEM.defaultBlockState().setValue(BigDripleafStemBlock.FACING, f), 2);
        level.setBlock(at.above(h), Blocks.BIG_DRIPLEAF.defaultBlockState().setValue(BigDripleafBlock.FACING, f), 2);
    }

    private static void azaleaTree(WorldGenLevel level, BlockPos at, RandomSource r) {
        if (!(level instanceof WorldGenRegion region)) return;
        var feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(TreeFeatures.AZALEA_TREE);
        if (feature.isEmpty()) return;
        BlockPos below = at.below();
        BlockState was = level.getBlockState(below);
        if (!was.is(BlockTags.DIRT) && !was.is(Blocks.GRASS_BLOCK) && !was.is(Blocks.MOSS_BLOCK)) return;
        // keep clear of the chunk's far edges, so the canopy stays inside what may be written
        feature.get().value().place(level, region.getLevel().getChunkSource().getGenerator(), r, at);
    }

    // ------------------------------------------------------------------ wetlands

    /**
     * The ground between a wetland's pools, by climate:
     *   marsh (temperate): grass and mud, tall grass and reeds of sugar cane, dripleaf, the odd blue orchid;
     *   swamp (warm and wet): mud and moss, mangroves here and there, blue orchids, dripleaf;
     *   bog (cold): moss, podzol and coarse dirt, ferns, sweet berry bushes and moss carpet.
     */
    private static void hummock(WorldGenLevel level, Planner.Column c, int x, int z, BankDecor.Kind k, RandomSource r, BlockPos.MutableBlockPos m) {
        int W = c.wetlandLevel;
        int y = W + 3;
        BlockState g = null;
        for (; y >= W - 1; y--) {
            g = level.getBlockState(m.set(x, y, z));
            if (!g.isAir() && !g.canBeReplaced()) break;
        }
        if (y < W - 1 || g == null || !g.getFluidState().isEmpty()) return;
        if (!(g.is(Blocks.GRASS_BLOCK) || g.is(BlockTags.DIRT) || g.is(BlockTags.SAND) || g.is(Blocks.GRAVEL) || g.is(Blocks.SNOW_BLOCK) || g.is(Blocks.MUD) || g.is(Blocks.CLAY)
                || g.is(BlockTags.BASE_STONE_OVERWORLD))) return;
        BlockPos ground = new BlockPos(x, y, z), at = ground.above();
        double h = BankDecor.hash(x, y, z, 81), patch = (Planner.valueNoise(x / 3.0, z / 3.0, 82) + 1) / 2;
        boolean bog = k == BankDecor.Kind.COLD, swamp = k == BankDecor.Kind.WET;
        Block top = bog ? (patch < 0.45 ? Blocks.MOSS_BLOCK : patch < 0.65 ? Blocks.PODZOL : patch < 0.8 ? Blocks.COARSE_DIRT : Blocks.MUD)
                : swamp ? (patch < 0.45 ? Blocks.MUD : patch < 0.75 ? Blocks.GRASS_BLOCK : patch < 0.88 ? Blocks.MOSS_BLOCK : Blocks.PACKED_MUD)
                : (patch < 0.55 ? Blocks.GRASS_BLOCK : patch < 0.78 ? Blocks.MUD : patch < 0.9 ? Blocks.ROOTED_DIRT : Blocks.CLAY);
        if (!level.getBlockState(at).isAir()) return;
        level.setBlock(ground, top.defaultBlockState(), 2);
        boolean wetSide = waterBeside(level, ground) || waterBeside(level, at);
        double f = r.nextDouble();
        if (bog) {
            if (f < 0.07) { if (Blocks.SWEET_BERRY_BUSH.defaultBlockState().canSurvive(level, at)) level.setBlock(at, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, r.nextInt(4)), 2); }
            else if (f < 0.32 && top == Blocks.MOSS_BLOCK) level.setBlock(at, Blocks.MOSS_CARPET.defaultBlockState(), 2);
            else if (f < 0.45) level.setBlock(at, (r.nextFloat() < 0.7 ? Blocks.FERN : Blocks.GRASS).defaultBlockState(), 2);
            else if (f < 0.5) doublePlant(level, at, Blocks.LARGE_FERN);
            return;
        }
        if (swamp && f < 0.006) { tree(level, at, r, TreeFeatures.MANGROVE); return; }
        if (wetSide && f < 0.3) { cane(level, at, r); return; }
        if (wetSide && f < 0.35) { bigDripleaf(level, at, r); return; }
        if (f < (swamp ? 0.27 : 0.24)) { if (Blocks.BLUE_ORCHID.defaultBlockState().canSurvive(level, at)) level.setBlock(at, Blocks.BLUE_ORCHID.defaultBlockState(), 2); return; }
        if (f < 0.55) { doublePlant(level, at, r.nextFloat() < 0.85 ? Blocks.TALL_GRASS : Blocks.LARGE_FERN); return; }
        if (f < 0.7) level.setBlock(at, Blocks.GRASS.defaultBlockState(), 2);
    }

    private static void tree(WorldGenLevel level, BlockPos at, RandomSource r, net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> key) {
        if (!(level instanceof WorldGenRegion region)) return;
        var feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolder(key);
        feature.ifPresent(f -> f.value().place(level, region.getLevel().getChunkSource().getGenerator(), r, at));
    }

    // ------------------------------------------------------------------ overhangs

    /** Glow berries hanging from rock or earth jutting out over the water or the bank. */
    private static void overhang(WorldGenLevel level, Planner.Column c, int x, int z, RandomSource r, BlockPos.MutableBlockPos m) {
        int base = c.wet() ? c.water : c.nearLevel;
        for (int y = base + 1; y < base + 12; y++) {
            BlockState st = level.getBlockState(m.set(x, y, z));
            if (!st.isAir()) return;
            BlockState up = level.getBlockState(m.set(x, y + 1, z));
            if (up.isAir()) continue;
            if (!(up.is(BlockTags.BASE_STONE_OVERWORLD) || up.is(BlockTags.DIRT) || up.is(Blocks.GRASS_BLOCK) || up.is(Blocks.MOSS_BLOCK))) return;
            if (r.nextFloat() > 0.35) return;
            int len = 1 + r.nextInt(Math.min(5, y - base));
            for (int i = 0; i < len; i++) {
                BlockPos p = new BlockPos(x, y - i, z);
                if (!level.getBlockState(p).isAir()) break;
                boolean end = i == len - 1 || !level.getBlockState(p.below()).isAir();
                boolean berries = r.nextFloat() < 0.45;
                level.setBlock(p, (end ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT).defaultBlockState().setValue(CaveVines.BERRIES, berries), 2);
                if (end) break;
            }
            return;
        }
    }
}
