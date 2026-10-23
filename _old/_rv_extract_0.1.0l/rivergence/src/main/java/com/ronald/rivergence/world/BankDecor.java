package com.ronald.rivergence.world;

import com.ronald.rivergence.plan.Planner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.Tags;

/**
 * Banks, shores and beds that suit the land they run through: sandy and sandstone in deserts, red sand and
 * terracotta in badlands, gravel and bare stone where it's cold, mud, clay and roots where it's wet, moss and coarse
 * dirt under forest, and in open country grass broken by patches of sand, gravel and clay. Thickest at the
 * waterline, thinning out up the bank. Only natural ground is touched.
 */
final class BankDecor {
    private BankDecor() {}

    enum Kind { OPEN, FOREST, WET, COLD, SANDY, BADLANDS, DELTA }

    static Kind kind(Holder<Biome> b) {
        if (b.is(BiomeTags.IS_BADLANDS)) return Kind.BADLANDS;
        if (b.is(Tags.Biomes.IS_DESERT) || b.is(Tags.Biomes.IS_SANDY) && b.is(Tags.Biomes.IS_HOT)) return Kind.SANDY;
        if (b.is(Tags.Biomes.IS_SNOWY) || b.is(Tags.Biomes.IS_COLD) || b.is(Tags.Biomes.IS_PEAK)) return Kind.COLD;
        if (b.is(Tags.Biomes.IS_SWAMP) || b.is(BiomeTags.IS_JUNGLE) || b.is(Tags.Biomes.IS_LUSH) || b.is(Tags.Biomes.IS_WET)) return Kind.WET;
        if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA) || b.is(Tags.Biomes.IS_CONIFEROUS) || b.is(Tags.Biomes.IS_DENSE)) return Kind.FOREST;
        return Kind.OPEN;
    }

    private static Block pick(Kind k, double r, boolean under) {
        switch (k) {
            case SANDY:    return r < 0.55 ? Blocks.SAND : r < 0.72 ? Blocks.SANDSTONE : r < 0.8 ? Blocks.GRAVEL : under && r < 0.86 ? Blocks.CLAY : null;
            case BADLANDS: return r < 0.45 ? Blocks.RED_SAND : r < 0.62 ? Blocks.TERRACOTTA : r < 0.72 ? Blocks.COARSE_DIRT : r < 0.78 ? Blocks.GRAVEL : null;
            case COLD:     return r < 0.38 ? Blocks.GRAVEL : r < 0.55 ? Blocks.STONE : r < 0.65 ? Blocks.COBBLESTONE : r < 0.75 ? Blocks.COARSE_DIRT : null;
            case DELTA:    return r < 0.32 ? Blocks.SAND : r < 0.5 ? Blocks.MUD : r < 0.58 ? Blocks.CLAY : r < 0.64 ? Blocks.GRAVEL : null;
            case WET:      return r < 0.35 ? Blocks.MUD : r < 0.48 ? Blocks.CLAY : r < 0.6 ? Blocks.MOSS_BLOCK : r < 0.7 ? Blocks.ROOTED_DIRT : r < 0.76 ? Blocks.PACKED_MUD : null;
            case FOREST:   return r < 0.18 ? Blocks.COARSE_DIRT : r < 0.32 ? Blocks.MOSS_BLOCK : r < 0.44 ? Blocks.ROOTED_DIRT : r < 0.55 ? Blocks.GRAVEL : under && r < 0.62 ? Blocks.MUD : r < 0.64 ? Blocks.MOSSY_COBBLESTONE : null;
            default:       return r < 0.18 ? Blocks.SAND : r < 0.34 ? Blocks.GRAVEL : under && r < 0.42 ? Blocks.CLAY : r < 0.5 ? Blocks.COARSE_DIRT : r < 0.55 ? Blocks.DIRT : null;
        }
    }

    /** What lines the bed under the water. */
    static BlockState bed(WorldGenLevel level, Planner.Column c, int x, int y, int z) {
        Kind k = kind(level.getBiome(new BlockPos(x, y, z)));
        double r = hash(x, y, z, 5);
        Block b = pick(k, r * 0.8, true);                 // under water: mostly the waterline mix, never bare grass
        if (b == null || b == Blocks.SANDSTONE || b == Blocks.TERRACOTTA || b == Blocks.STONE || b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE)
            b = k == Kind.WET ? Blocks.MUD : k == Kind.SANDY ? Blocks.SAND : k == Kind.BADLANDS ? Blocks.RED_SAND : Blocks.GRAVEL;
        if (c.lake && k == Kind.OPEN && r > 0.6) b = Blocks.DIRT;
        if (c.wetland) b = k == Kind.COLD ? (r < 0.5 ? Blocks.MUD : Blocks.MOSS_BLOCK) : (r < 0.6 ? Blocks.MUD : r < 0.85 ? Blocks.CLAY : Blocks.DIRT);
        return b.defaultBlockState();
    }

    static void run(WorldGenLevel level, Planner.Column[] cols, BlockPos.MutableBlockPos m) {
        for (int a = 1; a <= 16; a++) for (int b = 1; b <= 16; b++) {
            Planner.Column c = cols[a + b * 18];
            if (c.nearLevel == Planner.Column.DRY || c.edge < 0) continue;
            double reach = c.delta ? 9 : 2 + Math.min(4, c.nearWidth * 0.35);           // wider water, wider bank; deltas all sand and mud
            if (c.edge > reach || c.springQ < 1.9 || c.pool != null) continue;
            // the ground's top, near the water's level
            int y = c.nearLevel + 3;
            BlockState st = null;
            for (; y >= c.nearLevel - 1; y--) {
                st = level.getBlockState(m.set(c.x, y, c.z));
                if (!st.isAir() && st.getFluidState().isEmpty() && !st.canBeReplaced()) break;
            }
            if (y < c.nearLevel - 1 || st == null || !natural(st)) continue;
            // thick at the waterline, patchy up the bank
            double f = c.edge / reach, keep = 0.95 - 0.8 * f;
            double r = 0.7 * (Planner.valueNoise(c.x / 3.5, c.z / 3.5, 77) + 1) / 2 + 0.3 * hash(c.x, 0, c.z, 9);
            if (r > keep) continue;
            Kind k = kind(level.getBiome(m));
            if (c.delta && k != Kind.COLD && k != Kind.BADLANDS) k = Kind.DELTA;
            double pr = 0.6 * (Planner.valueNoise(c.x / 2.6, c.z / 2.6, 78) + 1) / 2 + 0.4 * hash(c.x, y, c.z, 3);   // in patches, not speckles
            Block pick = pick(k, pr, false);
            if (pick == null) continue;
            level.setBlock(m, pick.defaultBlockState(), 2);
            // what grew on grass can't stay on stone or sand
            BlockState above = level.getBlockState(m.set(c.x, y + 1, c.z));
            if (!above.isAir() && above.canBeReplaced() && above.getFluidState().isEmpty() && !above.canSurvive(level, m))
                level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static boolean natural(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.PODZOL) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.SAND)
                || st.is(Blocks.RED_SAND) || st.is(Blocks.GRAVEL) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.MYCELIUM) || st.is(BlockTags.TERRACOTTA)
                || st.is(Blocks.STONE) || st.is(Blocks.MUD);
    }

    static double hash(int x, int y, int z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ y * 0x165667B19E3779F9L ^ salt * 0x27D4EB2F165667C5L;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }
}
