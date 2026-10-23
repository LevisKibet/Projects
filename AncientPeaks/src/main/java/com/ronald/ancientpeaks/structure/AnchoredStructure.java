package com.ronald.ancientpeaks.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ronald.ancientpeaks.AncientPeaks;
import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.Optional;

/**
 * A structure from the project's structures/ folder, placed at a named spot rather than scattered at random:
 * an island anchor (plains_village, plains_castle, meadow_plateau, ...) or beside every Highland Gateway ("gateway").
 * The pieces themselves are an ordinary template pool, so a structure can be a single .nbt or a whole jigsaw set.
 */
public class AnchoredStructure extends Structure {
    public static final Codec<AnchoredStructure> CODEC = RecordCodecBuilder.create(i -> i.group(
            settingsCodec(i),
            StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s -> s.startPool),
            Codec.intRange(1, 20).optionalFieldOf("size", 1).forGetter(s -> s.size),
            Codec.STRING.fieldOf("anchor").forGetter(s -> s.anchor),
            BlockPos.CODEC.optionalFieldOf("offset", BlockPos.ZERO).forGetter(s -> s.offset),
            Codec.intRange(1, 128).optionalFieldOf("max_distance_from_center", 80).forGetter(s -> s.maxDist),
            Codec.BOOL.optionalFieldOf("yield_to_lore", false).forGetter(s -> s.yieldToLore)
    ).apply(i, AnchoredStructure::new));

    /** Set while a structure is placed by hand (the island side of a gateway): skips the overworld checks. */
    static final ThreadLocal<Boolean> MANUAL = ThreadLocal.withInitial(() -> false);

    final Holder<StructureTemplatePool> startPool;
    final int size, maxDist;
    final String anchor;
    final BlockPos offset;
    final boolean yieldToLore;

    public AnchoredStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool, int size, String anchor, BlockPos offset, int maxDist, boolean yieldToLore) {
        super(settings);
        this.startPool = startPool; this.size = size; this.anchor = anchor; this.offset = offset; this.maxDist = maxDist; this.yieldToLore = yieldToLore;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        if (yieldToLore && AncientPeaks.loreLoaded()) return Optional.empty();     // the lore mod places its own
        ChunkPos cp = ctx.chunkPos();
        int x, z;
        if (anchor.equals("gateway") || anchor.equals("chunk")) {
            if (!MANUAL.get() && !com.ronald.ancientpeaks.Gateways.enabled()) return Optional.empty();   // gateways switched off / not installed
            // shares the Highland Gateways' placement, so it lands in the same chunk; offset from the chunk corner
            x = cp.getMinBlockX() + offset.getX(); z = cp.getMinBlockZ() + offset.getZ();
            if (!MANUAL.get() && anchor.equals("gateway")) {
                int y = ctx.chunkGenerator().getFirstOccupiedHeight(cp.getMinBlockX(), cp.getMinBlockZ(), Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState());
                Holder<Biome> b = ctx.biomeSource().getNoiseBiome(cp.getMinBlockX() >> 2, y >> 2, cp.getMinBlockZ() >> 2, ctx.randomState().sampler());
                if (!biomes().contains(b)) return Optional.empty();       // no gateway here, so no companion either
            }
        } else {
            IslandSampler s = ((HighlandsAccess) (Object) ctx.randomState()).ancientpeaks$sampler();
            if (s == null) return Optional.empty();
            Island is = s.islandInSector(Math.floorDiv(cp.getMiddleBlockX(), IslandSampler.SECTOR), Math.floorDiv(cp.getMiddleBlockZ(), IslandSampler.SECTOR));
            if (is == null) return Optional.empty();
            int[] a = is.anchor(anchor);
            if (a == null) return Optional.empty();
            x = a[0] + offset.getX(); z = a[1] + offset.getZ();
            if (SectionPos.blockToSectionCoord(x) != cp.x || SectionPos.blockToSectionCoord(z) != cp.z) return Optional.empty();
        }
        return JigsawPlacement.addPieces(ctx, startPool, Optional.empty(), size, new BlockPos(x, offset.getY(), z), false,
                Optional.of(Heightmap.Types.WORLD_SURFACE_WG), maxDist);
    }

    @Override
    public StructureType<?> type() { return AncientPeaks.ANCHORED.get(); }

    /** Places a structure by hand right now (used for the companion beside the island's gateway). */
    public static void placeNow(ServerLevel level, ResourceLocation id, BlockPos near) {
        Structure st = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id);
        if (st == null) return;                                                    // not part of this build
        var gen = level.getChunkSource().getGenerator();
        StructureStart start;
        MANUAL.set(true);
        try {
            start = st.generate(level.registryAccess(), gen, gen.getBiomeSource(), level.getChunkSource().randomState(),
                    level.getStructureManager(), level.getSeed(), new ChunkPos(near), 0, level, b -> true);
        } finally { MANUAL.set(false); }
        if (!start.isValid()) { AncientPeaks.LOGGER.warn("Structure {} found no spot near {}", id, near); return; }
        BoundingBox bb = start.getBoundingBox();
        ChunkPos a = new ChunkPos(SectionPos.blockToSectionCoord(bb.minX()), SectionPos.blockToSectionCoord(bb.minZ()));
        ChunkPos b = new ChunkPos(SectionPos.blockToSectionCoord(bb.maxX()), SectionPos.blockToSectionCoord(bb.maxZ()));
        ChunkPos.rangeClosed(a, b).forEach(c -> start.placeInChunk(level, level.structureManager(), gen, level.getRandom(),
                new BoundingBox(c.getMinBlockX(), level.getMinBuildHeight(), c.getMinBlockZ(), c.getMaxBlockX(), level.getMaxBuildHeight(), c.getMaxBlockZ()), c));
        AncientPeaks.LOGGER.info("Placed {} at {}", id, bb.getCenter());
    }
}
