package com.ronald.rivergence.world;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.Tags;

import java.util.concurrent.ConcurrentHashMap;

/**
 * What the water looks like in each biome: the kind of banks and beds (sandy, rocky, muddy...), the stone or soil of
 * that particular land worked into its banks (basalt by volcanoes, calcite under white cliffs, terracotta in painted
 * canyons), the flowers that grow down to the water, and how lush the water's edge may get. Worked out from the
 * biome's tags, then refined by name for vanilla's biomes and Terralith's.
 */
public final class BiomeProfile {
    final BankDecor.Kind kind;
    /** How much grows in and round the water: 0.25 bare to 1.6 teeming. */
    final double lush;
    /** The land's own stone or soil, mixed into banks and beds (share: how much of the bank it takes). */
    final Block[] accent;
    final double accentShare;
    /** Flowers that grow down to the water here (empty: just grass and ferns). */
    final Block[] flowers;

    private BiomeProfile(BankDecor.Kind kind, double lush, Block[] accent, double accentShare, Block[] flowers) {
        this.kind = kind; this.lush = lush; this.accent = accent; this.accentShare = accentShare; this.flowers = flowers;
    }

    private static final ConcurrentHashMap<ResourceKey<Biome>, BiomeProfile> CACHE = new ConcurrentHashMap<>();
    private static final Block[] NONE = new Block[0];

    public static BiomeProfile of(Holder<Biome> b) {
        var key = b.unwrapKey().orElse(null);
        if (key == null) return make(b, "");
        return CACHE.computeIfAbsent(key, k -> make(b, k.location().getPath()));
    }

    private static BankDecor.Kind kindFromTags(Holder<Biome> b) {
        if (b.is(BiomeTags.IS_BADLANDS)) return BankDecor.Kind.BADLANDS;
        if (b.is(Tags.Biomes.IS_DESERT) || b.is(Tags.Biomes.IS_SANDY) && b.is(Tags.Biomes.IS_HOT)) return BankDecor.Kind.SANDY;
        if (b.is(Tags.Biomes.IS_SNOWY) || b.is(Tags.Biomes.IS_COLD) || b.is(Tags.Biomes.IS_PEAK)) return BankDecor.Kind.COLD;
        if (b.is(Tags.Biomes.IS_SWAMP) || b.is(BiomeTags.IS_JUNGLE) || b.is(Tags.Biomes.IS_LUSH) || b.is(Tags.Biomes.IS_WET)) return BankDecor.Kind.WET;
        if (b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA) || b.is(Tags.Biomes.IS_CONIFEROUS) || b.is(Tags.Biomes.IS_DENSE)) return BankDecor.Kind.FOREST;
        return BankDecor.Kind.OPEN;
    }

    private static boolean has(String p, String... words) { for (String w : words) if (p.contains(w)) return true; return false; }

    private static BiomeProfile make(Holder<Biome> b, String p) {
        BankDecor.Kind k = kindFromTags(b);
        // names first where the tags can't tell (Terralith's especially)
        if (has(p, "ice_marsh", "glacial", "frozen", "wintry", "siberian", "snowy", "alpine", "frost")) k = BankDecor.Kind.COLD;
        else if (has(p, "swamp", "marsh", "bog", "fen", "mangrove", "tropical_jungle", "cloud_forest", "rocky_jungle", "lush_valley", "orchid")) k = BankDecor.Kind.WET;
        else if (has(p, "badlands", "mesa", "bryce", "painted", "scarlet", "red_oasis")) k = BankDecor.Kind.BADLANDS;
        else if (has(p, "desert", "dune", "mirage", "ancient_sands", "sandstone_valley", "gravel_desert")) k = BankDecor.Kind.SANDY;

        double lush = switch (k) { case WET, DELTA -> 1.5; case FOREST -> 1.1; case OPEN -> 1.0; case COLD -> 0.3; case SANDY -> 0.35; case BADLANDS -> 0.25; };
        if (b.is(BiomeTags.IS_SAVANNA) || has(p, "savanna", "shrubland", "steppe", "brushland")) lush = Math.min(lush, 0.6);
        if (has(p, "lush", "tropical", "cloud_forest", "jungle", "blooming", "oasis")) lush = Math.max(lush, 1.4);
        if (has(p, "mountain", "peaks", "spires", "cliffs", "highlands", "haze")) lush *= 0.8;

        Block[] accent = NONE; double share = 0;
        if (has(p, "volcanic", "caldera", "basalt", "ashen")) { accent = new Block[]{Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.BLACKSTONE, Blocks.TUFF}; share = 0.45; }
        else if (has(p, "yellowstone")) { accent = new Block[]{Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.ORANGE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA}; share = 0.4; }
        else if (has(p, "white_cliffs", "white_mesa")) { accent = new Block[]{Blocks.CALCITE, Blocks.DIORITE, Blocks.SMOOTH_SANDSTONE}; share = 0.4; }
        else if (has(p, "amethyst")) { accent = new Block[]{Blocks.CALCITE, Blocks.SMOOTH_BASALT, Blocks.TUFF}; share = 0.3; }
        else if (has(p, "granite")) { accent = new Block[]{Blocks.GRANITE, Blocks.POLISHED_GRANITE, Blocks.COARSE_DIRT}; share = 0.4; }
        else if (has(p, "painted", "scarlet", "bryce", "warped_mesa", "red_oasis")) { accent = new Block[]{Blocks.TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.WHITE_TERRACOTTA}; share = 0.4; }
        else if (has(p, "stony", "rocky", "shield", "windswept", "jagged", "stony_peaks", "emerald_peaks")) { accent = new Block[]{Blocks.STONE, Blocks.ANDESITE, Blocks.TUFF, Blocks.MOSSY_COBBLESTONE}; share = 0.35; }
        else if (has(p, "dripstone", "yosemite")) { accent = new Block[]{Blocks.GRANITE, Blocks.DIORITE, Blocks.STONE}; share = 0.3; }
        else if (has(p, "mushroom")) { accent = new Block[]{Blocks.MYCELIUM, Blocks.PODZOL}; share = 0.4; }
        else if (has(p, "dark_forest", "old_growth", "moonlight")) { accent = new Block[]{Blocks.PODZOL, Blocks.MOSS_BLOCK, Blocks.ROOTED_DIRT}; share = 0.3; }

        Block[] flowers = NONE;
        if (has(p, "cherry", "sakura")) flowers = new Block[]{Blocks.PINK_PETALS, Blocks.PINK_PETALS, Blocks.ALLIUM};
        else if (has(p, "lavender")) flowers = new Block[]{Blocks.ALLIUM, Blocks.LILAC};
        else if (has(p, "flower", "blooming", "meadow")) flowers = new Block[]{Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.POPPY};
        else if (has(p, "orchid", "swamp")) flowers = new Block[]{Blocks.BLUE_ORCHID};
        else if (has(p, "birch")) flowers = new Block[]{Blocks.LILY_OF_THE_VALLEY, Blocks.OXEYE_DAISY};
        else if (has(p, "moonlight")) flowers = new Block[]{Blocks.CORNFLOWER, Blocks.AZURE_BLUET};
        else if (has(p, "mushroom", "dark_forest")) flowers = new Block[]{Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM};
        else if (has(p, "plains", "valley", "clearing", "lowlands")) flowers = new Block[]{Blocks.OXEYE_DAISY, Blocks.AZURE_BLUET, Blocks.DANDELION};
        else if (k == BankDecor.Kind.COLD) flowers = NONE;
        return new BiomeProfile(k, lush, accent, share, flowers);
    }

    /** Cherry groves, Terralith's sakura groves and valleys, snowy cherry groves. */
    public static boolean cherry(Holder<Biome> b) {
        return b.unwrapKey().map(key -> has(key.location().getPath(), "cherry", "sakura")).orElse(false);
    }
}
