package com.ronald.ancientpeaks_lore;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.*;

/**
 * A safety net for the summit. When a player comes near an island's meadow plateau, check the sword in the stone is
 * there (with Vortigern's statue beside it while the sword is still in it). If worldgen ever left it out - an old
 * chunk, a clash with another mod - the pedestal, the three great swords and the statue are put there now. Each island
 * is checked once.
 */
final class SummitWatch {
    private SummitWatch() {}

    static void register() { MinecraftForge.EVENT_BUS.addListener(SummitWatch::tick); }

    private static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.getServer().getTickCount() % 40 != 7) return;
        ServerLevel sl = e.getServer().getLevel(Level.OVERWORLD);
        if (sl == null) return;
        IslandSampler s = ((HighlandsAccess) (Object) sl.getChunkSource().randomState()).ancientpeaks$sampler();
        if (s == null) return;
        for (ServerPlayer p : sl.players()) {
            try { check(sl, s, p); } catch (RuntimeException ex) { AncientPeaksLore.LOGGER.error("[ancientpeaks_lore] summit check failed", ex); }
        }
    }

    private static void check(ServerLevel sl, IslandSampler s, ServerPlayer p) {
        Island is = s.islandAt(p.getBlockX(), p.getBlockZ());
        if (is == null) return;
        int[] a = is.anchor("meadow_plateau");
        if (Math.hypot(p.getX() - a[0], p.getZ() - a[1]) > 80) return;
        Done done = Done.get(sl);
        String key = is.cx + "," + is.cz;
        if (!done.has(key)) { if (ensure(sl, is) != null) done.add(key); return; }
        if (!Quest.has(p, Quest.SLAIN)) restock(sl, is, key);
    }

    /** Where each island's sword in the stone is, once found. */
    private static final java.util.Map<String, BlockPos> STONES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Each player meets Vortigern for themselves: when one who hasn't yet seen him fall comes up to the summit and the
     * fight isn't on, his statue stands beside the stone again (and the sword is back in it).
     */
    static void restock(ServerLevel sl, Island is, String key) {
        BlockPos sword = STONES.get(key);
        if (sword == null || !sl.getBlockState(sword).is(LoreContent.SWORD_IN_STONE.get())) {
            sword = findSword(sl, is);
            if (sword == null) return;
            STONES.put(key, sword);
        }
        AABB around = new AABB(sword).inflate(24);
        if (!sl.getEntitiesOfClass(Vortigern.class, around.inflate(72)).isEmpty()) return;      // the fight is on
        var statues = sl.getEntitiesOfClass(VortigernStatue.class, around);
        var st = sl.getBlockState(sword);
        if (st.getValue(SwordInStoneBlock.PULLED)) {
            if (statues.stream().anyMatch(VortigernStatue::awakening)) return;                    // he's waking right now
            sl.setBlock(sword, st.setValue(SwordInStoneBlock.PULLED, false), 3);
        }
        if (statues.isEmpty()) LorePiece.placeStatue(sl, sword);
    }

    private static BlockPos findSword(ServerLevel sl, Island is) {
        int[] a = is.anchor("meadow_plateau");
        if (!sl.hasChunkAt(new BlockPos(a[0], 0, a[1]))) return null;
        int top = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a[0], a[1]);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int y = top - 16; y <= top + 24; y++)
            if (sl.getBlockState(m.set(a[0] + dx, y, a[1] + dz)).is(LoreContent.SWORD_IN_STONE.get())) return m.immutable();
        return null;
    }

    /** Checks (and if need be raises) one island's summit. Returns what was found or done; null while its chunks aren't loaded. */
    static String ensure(ServerLevel sl, Island is) {
        int[] a = is.anchor("meadow_plateau");
        String key = is.cx + "," + is.cz;
        for (int[] o : new int[][]{{-24, -24}, {24, -24}, {-24, 24}, {24, 24}, {0, 0}})
            if (!sl.hasChunkAt(new BlockPos(a[0] + o[0], 0, a[1] + o[1]))
                    || !sl.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(new BlockPos(a[0] + o[0], 0, a[1] + o[1])))) return null;
        int top = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, a[0], a[1]);
        BlockPos sword = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        find:
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int y = top - 16; y <= top + 24; y++) {
            if (sl.getBlockState(m.set(a[0] + dx, y, a[1] + dz)).is(LoreContent.SWORD_IN_STONE.get())) { sword = m.immutable(); break find; }
        }
        if (sword == null) {
            sword = build(sl, is, a);
            if (sword == null) return "No pedestal template to build the summit from";
            AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] the summit of island {} was missing its sword; raised it at {}", key, sword.toShortString());
            return "The summit was missing: raised the pedestal, swords and Vortigern's statue at " + sword.toShortString();
        }
        if (!sl.getBlockState(sword).getValue(SwordInStoneBlock.PULLED)) {
            AABB around = new AABB(sword).inflate(24);
            if (sl.getEntitiesOfClass(VortigernStatue.class, around).isEmpty() && sl.getEntitiesOfClass(Vortigern.class, around.inflate(40)).isEmpty()) {
                LorePiece.placeStatue(sl, sword);
                AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] Vortigern's statue was missing by the sword at {}; set it up", sword.toShortString());
                return "Vortigern's statue was missing: set it up beside the sword at " + sword.toShortString();
            }
            return "The sword and Vortigern's statue are in place at " + sword.toShortString();
        }
        return "The sword has already been drawn at " + sword.toShortString();
    }

    /** The same pedestal and three swords worldgen would have put there. Returns the sword's stone. */
    static BlockPos build(ServerLevel sl, Island is, int[] a) {
        var mgr = sl.getStructureManager();
        var swords = new ArrayList<>(LoreSite.templates(mgr, "swords"));
        var pedestal = swords.stream().filter(rl -> rl.getPath().contains("pedestal")).findFirst();
        swords.removeIf(rl -> rl.getPath().contains("pedestal"));
        if (pedestal.isEmpty()) return null;
        Random r = new Random(sl.getSeed() ^ (is.cx * 341873128712L) ^ (is.cz * 132897987541L) ^ "summit".hashCode());
        BlockPos stone = null;
        StructureTemplate pt = mgr.get(pedestal.get()).orElse(null);
        if (pt == null) return null;
        Placement pp = placement(sl, pedestal.get(), pt, a[0], a[1], Rotation.NONE, 0);
        pt.placeInWorld(sl, pp.origin, pp.origin, pp.settings, sl.random, 2);
        stone = LorePiece.placeSword(sl, BoundingBox.infinite(), pt, pp.settings, pp.origin);   // (sets the statue up too)
        double a0 = r.nextDouble() * Math.PI * 2;
        for (int k = 0; k < Math.min(3, swords.size()); k++) {
            double ang = a0 + k * Math.PI * 2 / 3;
            int x = a[0] + (int) Math.round(Math.cos(ang) * 13), z = a[1] + (int) Math.round(Math.sin(ang) * 13);
            Rotation rot = Math.abs(Math.cos(ang)) > Math.abs(Math.sin(ang)) ? Rotation.CLOCKWISE_90 : Rotation.NONE;
            StructureTemplate st = mgr.get(swords.get(k)).orElse(null);
            if (st == null) continue;
            Placement sp = placement(sl, swords.get(k), st, x, z, rot, 11);
            st.placeInWorld(sl, sp.origin, sp.origin, sp.settings, sl.random, 2);
        }
        return stone;
    }

    private record Placement(BlockPos origin, StructurePlaceSettings settings) {}

    private static Placement placement(ServerLevel sl, ResourceLocation id, StructureTemplate t, int x, int z, Rotation rot, int sink) {
        int sx = t.getSize().getX(), sz = t.getSize().getZ();
        int g = sl.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;          // the top solid block: layer 0 takes its place
        BlockPos pivot = new BlockPos(sx / 2, 0, sz / 2);
        return new Placement(new BlockPos(x - sx / 2, g - sink + 1, z - sz / 2), LorePiece.settings(sl.getStructureManager(), id, rot, pivot));
    }

    /** Which islands' summits have been checked. */
    static final class Done extends SavedData {
        private final Set<String> keys = new HashSet<>();
        static Done get(ServerLevel sl) {
            return sl.getDataStorage().computeIfAbsent(Done::load, Done::new, "ancientpeaks_lore_summits");
        }
        static Done load(CompoundTag tag) {
            Done d = new Done();
            for (var t : tag.getList("checked", 8)) d.keys.add(t.getAsString());
            return d;
        }
        boolean has(String k) { return keys.contains(k); }
        void add(String k) { keys.add(k); setDirty(); }
        @Override public CompoundTag save(CompoundTag tag) {
            ListTag l = new ListTag();
            for (String k : keys) l.add(StringTag.valueOf(k));
            tag.put("checked", l);
            return tag;
        }
    }
}
