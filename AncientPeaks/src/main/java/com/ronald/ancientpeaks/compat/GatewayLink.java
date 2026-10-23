package com.ronald.ancientpeaks.compat;

import com.ronald.ancientpeaks.AncientPeaks;
import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.conczin.immersive_gateways.data.PortalDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Links Immersive Gateways portals standing in our Highland Gateway ruins to the coast of the nearest
 * Ancient Highlands island, building Immersive Gateways' own return gateway there.
 */
public final class GatewayLink {
    public static final ResourceKey<Structure> HIGHLAND_GATEWAY = ResourceKey.create(Registries.STRUCTURE, new ResourceLocation(AncientPeaks.MODID, "highland_gateway"));

    static <T> T onServer(ServerLevel level, Supplier<T> f) {
        return level.getServer().isSameThread() ? f.get() : level.getServer().submit(f).join();
    }

    /** Called when Immersive Gateways looks for (or creates) the partner of the gateway at pos. */
    public static PortalDataManager.PortalPair link(ServerLevel level, BlockPos pos) {
        boolean ours = onServer(level, () -> level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(HIGHLAND_GATEWAY)
                .map(h -> level.structureManager().getStructureWithPieceAt(pos, h.value()).isValid()
                        || level.structureManager().getStructureAt(pos, h.value()).isValid()).orElse(false));
        AncientPeaks.LOGGER.info("Gateway at {} asks for a partner; inside a Highland Gateway ruin: {}", pos, ours);
        if (!ours) return null;
        IslandSampler s = ((HighlandsAccess) (Object) level.getChunkSource().randomState()).ancientpeaks$sampler();
        if (s == null) return null;
        Island best = null; double bd = Double.MAX_VALUE;
        int sx = Math.floorDiv(pos.getX(), IslandSampler.SECTOR), sz = Math.floorDiv(pos.getZ(), IslandSampler.SECTOR);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Island is = s.islandInSector(sx + dx, sz + dz);
            if (is == null) continue;
            double d = Math.hypot(is.cx - pos.getX(), is.cz - pos.getZ());
            if (d < bd) { bd = d; best = is; }
        }
        if (best == null) return null;
        BlockPos target = buildIslandGateway(level, s, best);
        if (target == null) return null;
        PortalDataManager.addManualConnection(level, pos, target);
        AncientPeaks.LOGGER.info("Highland gateway at {} linked to island gateway at {}", pos, target);
        return PortalDataManager.getState(level).search(pos);
    }

    static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> LANDING =
            net.minecraft.server.level.TicketType.create("ancientpeaks_landing", java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong), 20 * 60 * 3);

    /**
     * Gets the island's landing spots generated in the background as the server starts, so the first trip through a
     * gateway doesn't stall the server while a far-off shore is built from nothing. Chunks that already exist just
     * load (and unload again a few minutes later).
     */
    public static void warmUp(ServerLevel level) {
        try {
            IslandSampler s = ((HighlandsAccess) (Object) level.getChunkSource().randomState()).ancientpeaks$sampler();
            Island is = s == null ? null : s.island();
            if (is == null) return;
            java.util.List<int[]> spots = s.findLandings(is, 8);
            for (int[] p : spots) {
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(p[0] >> 4, p[2] >> 4);
                level.getChunkSource().addRegionTicket(LANDING, cp, 2, cp);
            }
            AncientPeaks.LOGGER.info("Preparing the island's {} gateway landings in the background (island at {}, {})", spots.size(), is.cx, is.cz);
        } catch (RuntimeException e) {
            AncientPeaks.LOGGER.warn("Could not prepare the island landings", e);
        }
    }

    /** Remembers how many gateways each island already has, across restarts. */
    static final class Used extends net.minecraft.world.level.saveddata.SavedData {
        final java.util.Map<String, Integer> count = new java.util.HashMap<>();
        static Used load(net.minecraft.nbt.CompoundTag tag) {
            Used u = new Used();
            for (String k : tag.getAllKeys()) u.count.put(k, tag.getInt(k));
            return u;
        }
        @Override public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag tag) { count.forEach(tag::putInt); return tag; }
    }
    static Used used(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Used::load, Used::new, "ancientpeaks_island_gateways");
    }

    /**
     * Places a fresh Immersive Gateways portal on the island's coast for one overworld gateway to pair with.
     * Each overworld ruin gets its own landing spot, spread along the shore.
     */
    public static BlockPos buildIslandGateway(ServerLevel level, IslandSampler s, Island is) {
        String key = is.cx + "," + is.cz;
        java.util.List<int[]> spots = s.findLandings(is, 8);
        int k = onServer(level, () -> {
            Used u = used(level);
            int n = u.count.getOrDefault(key, 0);
            u.count.put(key, n + 1); u.setDirty();
            return n;
        });
        int[] p = spots.get(k % spots.size());
        try {
            BlockPos t = onServer(level, () -> placeGatewayTemplate(level, p[0], p[2]));
            AncientPeaks.LOGGER.info("Island gateway #{} placed at {}", k, t);
            return t;
        } catch (Throwable e) {
            AncientPeaks.LOGGER.error("Could not place the island gateway at {},{}", p[0], p[2], e);
            return null;
        }
    }

    /** Stamps Immersive Gateways' own plains portal template into the ground and returns its lowest gateway block. */
    static BlockPos placeGatewayTemplate(ServerLevel level, int x, int z) {
        var manager = level.getStructureManager();
        // plains/0 is the one with the outpost-like hut beside it; the others are just the portal
        var template = manager.get(new ResourceLocation("immersive_gateways", "plains/" + (1 + Math.floorMod(x ^ z, 2)))).orElse(null);
        if (template == null) return null;
        var size = template.getSize();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            level.getChunk((x >> 4) + dx * Math.max(1, size.getX() >> 4), (z >> 4) + dz * Math.max(1, size.getZ() >> 4));
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
        BlockPos origin = new BlockPos(x - size.getX() / 2, y - 9, z - size.getZ() / 2);
        var settings = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings();
        level.registryAccess().registryOrThrow(Registries.PROCESSOR_LIST)
                .getOptional(new ResourceLocation(AncientPeaks.MODID, "gateway_ground"))       // keeps the ground: no square pit
                .ifPresent(pl -> pl.list().forEach(settings::addProcessor));
        template.placeInWorld(level, origin, origin, settings, level.random, 2);
        BlockPos best = null;
        for (BlockPos q : BlockPos.betweenClosed(origin, origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
            if (level.getBlockState(q).getBlock() == net.conczin.immersive_gateways.Blocks.GATEWAY && (best == null || q.getY() < best.getY()))
                best = q.immutable();
        }
        if (best != null) {
            final BlockPos core = best;
            try { com.ronald.ancientpeaks.structure.AnchoredStructure.placeNow(level, new ResourceLocation(AncientPeaks.MODID, "modular/gateway_companion"), core); }
            catch (Throwable e) { AncientPeaks.LOGGER.error("Could not place the gateway companion", e); }
        }
        return best;
    }
}
