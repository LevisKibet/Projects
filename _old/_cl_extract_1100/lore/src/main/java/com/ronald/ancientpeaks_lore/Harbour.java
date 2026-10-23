package com.ronald.ancientpeaks_lore;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The old harbour below the castle, laid down with the island itself (painted into each chunk as it's decorated, so
 * it follows the real coast): a paved stone quay along the waterline, backed by a tall retaining wall with dark arched
 * alcoves and red banners, stairs up through the wall to the land, crates and barrels along the wall, and wooden plank
 * piers on stone-footed timber posts running out to deep water. Rustic grey stone, never white.
 * Each island's harbour is one of three: kept (still in use), overgrown (moss, vines, turf on the quay) or near rubble
 * (the wall tumbled down, piers broken off, stones strewn over the quay).
 *
 * Frame: v runs out to sea from the waterline point (0 = the shore), u runs along the coast.
 */
public final class Harbour {
    private Harbour() {}
    static final int DECK = 63;                     // sea surface is y 62
    static final int WALL_V0 = -10, WALL_V1 = -8, QUAY_V0 = -7, QUAY_V1 = 2;
    static final int KEPT = 0, OVERGROWN = 1, RUBBLE = 2;

    record Pier(int u, int len) {}
    record Plan(int wx, int wz, int dx, int dz, int uMin, int uMax, List<Pier> piers, int variant, int stairTop) {
        int u(int x, int z) { return (x - wx) * -dz + (z - wz) * dx; }
        int v(int x, int z) { return (x - wx) * dx + (z - wz) * dz; }
    }
    private static final Plan NONE = new Plan(0, 0, 0, 0, 0, -1, List.of(), 0, 0);
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();

    public static String describe(Plan p) { return p == NONE ? "none" : (new String[]{"kept", "overgrown", "ruined"})[p.variant]; }

    static Plan plan(long seed, IslandSampler s, Island is, StructureTemplateManager mgr) {
        return PLANS.computeIfAbsent(seed + "/" + is.cx + "/" + is.cz, k -> {
            int[] d = LoreSite.dockSpot(s, is, mgr);
            if (d == null) return NONE;
            int wx = d[0], wz = d[1], dx = d[2], dz = d[3], lx = -dz, lz = dx;
            Random r = new Random(seed ^ is.cx * 7341L ^ is.cz * 1931L ^ 0x4A12B0L);
            IslandSampler.Column col = new IslandSampler.Column();
            // where the waterline lies along the coast, and how high the land behind the wall is
            int R = 40;
            int[] shore = new int[2 * R + 1]; double[] behind = new double[2 * R + 1];
            for (int u = -R; u <= R; u++) {
                shore[u + R] = 99;
                for (int t = -30; t <= 30; t++) {
                    s.fill(col, wx + dx * t + lx * u, wz + dz * t + lz * u);
                    if (col.is == null || col.h < 62) { shore[u + R] = t; break; }
                }
                s.fill(col, wx + dx * -12 + lx * u, wz + dz * -12 + lz * u);
                behind[u + R] = col.is == null ? 62 : col.h;
            }
            int uMin = 0, uMax = 0;
            while (uMin > -38 && Math.abs(shore[uMin - 1 + R]) <= 8 && behind[uMin - 1 + R] < 80) uMin--;
            while (uMax < 38 && Math.abs(shore[uMax + 1 + R]) <= 8 && behind[uMax + 1 + R] < 80) uMax++;
            if (uMax - uMin < 32) { int grow = (32 - (uMax - uMin) + 1) / 2; uMin -= grow; uMax += grow; }
            int span = uMax - uMin;
            int n = span >= 52 ? 3 : 2;
            List<Pier> piers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int pu = uMin + (int) Math.round((i + 0.5) * span / n);
                if (Math.abs(pu) < 5) pu += pu < 0 ? -5 : 5;                 // keep clear of the stairs
                pu = Math.max(uMin + 4, Math.min(uMax - 4, pu));
                int len = 12 + r.nextInt(11);
                // only as far as the water stays deep enough to moor
                for (int t = 4; t <= len; t++) {
                    s.fill(col, wx + dx * (QUAY_V1 + t) + lx * pu, wz + dz * (QUAY_V1 + t) + lz * pu);
                    if (col.is != null && col.h > 60) { len = Math.max(6, t - 1); break; }
                }
                piers.add(new Pier(pu, len));
            }
            int variant = r.nextInt(3);
            s.fill(col, wx + dx * -14, wz + dz * -14);
            int stairTop = col.is == null ? DECK + 3 : (int) Math.round(col.h);
            Plan p = new Plan(wx, wz, dx, dz, uMin, uMax, piers, variant, stairTop);
            AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] harbour for island {},{} at {},{} ({}), {} to {} along the coast, {} piers",
                    is.cx, is.cz, wx, wz, describe(p), uMin, uMax, piers.size());
            return p;
        });
    }

    public static Plan planFor(WorldGenLevel level, IslandSampler s, Island is, StructureTemplateManager mgr) { return plan(level.getSeed(), s, is, mgr); }

    // ------------------------------------------------------------------ painting
    private static final Block[] WALL = {Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
            Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.ANDESITE, Blocks.TUFF, Blocks.STONE};
    private static final Block[] WALL_OLD = {Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
            Blocks.CRACKED_STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.TUFF, Blocks.ANDESITE, Blocks.MOSS_BLOCK};
    private static final Block[] PAVE = {Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.ANDESITE, Blocks.STONE,
            Blocks.MOSSY_COBBLESTONE, Blocks.CRACKED_STONE_BRICKS, Blocks.GRAVEL, Blocks.TUFF};
    private static final Block[] PAVE_OLD = {Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.MOSS_BLOCK, Blocks.COBBLESTONE, Blocks.MOSSY_STONE_BRICKS,
            Blocks.STONE_BRICKS, Blocks.GRAVEL, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.ANDESITE};
    private static final Block[] PAVE_RUIN = {Blocks.GRAVEL, Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.CRACKED_STONE_BRICKS,
            Blocks.STONE, Blocks.ANDESITE, Blocks.COARSE_DIRT, Blocks.MOSSY_STONE_BRICKS, Blocks.TUFF};
    private static final Block[] DARK = {Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.COBBLED_DEEPSLATE};

    public static void decorate(WorldGenLevel level, ChunkPos cp) {
        if (!(level instanceof WorldGenRegion region)) return;
        IslandSampler s = ((HighlandsAccess) (Object) region.getLevel().getChunkSource().randomState()).ancientpeaks$sampler();
        if (s == null) return;
        Island is = s.islandAt(cp.getMiddleBlockX(), cp.getMiddleBlockZ());
        if (is == null) return;
        Plan p = plan(level.getSeed(), s, is, region.getLevel().getStructureManager());
        if (p == NONE) return;
        // quick reject: the chunk's corners all far from the harbour's box
        int reach = 2 + p.piers.stream().mapToInt(Pier::len).max().orElse(0) + QUAY_V1 + 4;
        boolean near = false;
        for (int[] c : new int[][]{{cp.getMinBlockX(), cp.getMinBlockZ()}, {cp.getMaxBlockX(), cp.getMinBlockZ()}, {cp.getMinBlockX(), cp.getMaxBlockZ()},
                {cp.getMaxBlockX(), cp.getMaxBlockZ()}, {cp.getMiddleBlockX(), cp.getMiddleBlockZ()}}) {
            int u = p.u(c[0], c[1]), v = p.v(c[0], c[1]);
            if (u >= p.uMin - 24 && u <= p.uMax + 24 && v >= -40 && v <= reach + 24) { near = true; break; }
        }
        if (!near) return;
        long seed = level.getSeed() ^ 0x6A09E667F3BCC909L;
        Painter pt = new Painter(level, p, seed);
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++)
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) pt.column(x, z);
        pt.finish();
        moorBoat(region, cp, p);
    }

    /**
     * A rowing boat left tied up beside the first pier: the way round by water to the cave in the gorge, as Mordred
     * went. Put in once, by the chunk it floats in.
     */
    private static void moorBoat(WorldGenRegion region, ChunkPos cp, Plan p) {
        if (p.piers.isEmpty()) return;
        Pier pr = p.piers.get(0);
        int u = pr.u + 4, v = QUAY_V1 + 3;
        int x = p.wx + v * p.dx - u * p.dz, z = p.wz + v * p.dz + u * p.dx;
        if (x >> 4 != cp.x || z >> 4 != cp.z) return;
        BlockPos at = new BlockPos(x, DECK - 1, z);
        if (!region.getFluidState(at).is(net.minecraft.tags.FluidTags.WATER) || !region.getBlockState(at.above()).isAir()) return;
        var boat = new net.minecraft.world.entity.vehicle.Boat(region.getLevel(), x + 0.5, DECK - 0.45, z + 0.5);
        boat.setVariant(net.minecraft.world.entity.vehicle.Boat.Type.SPRUCE);
        boat.setYRot((float) Math.toDegrees(Math.atan2(-p.dx, p.dz)));             // lying alongside the pier
        region.addFreshEntity(boat);
    }

    private static final class Painter {
        final WorldGenLevel level; final Plan p; final long seed; final Direction out, in, left;
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        final List<BlockPos> walls = new ArrayList<>();
        Painter(WorldGenLevel level, Plan p, long seed) {
            this.level = level; this.p = p; this.seed = seed;
            out = Direction.getNearest(p.dx, 0, p.dz); in = out.getOpposite();
            left = Direction.getNearest(-p.dz, 0, p.dx);
        }

        double h(int x, int z, long salt) { return hash(x, z, seed ^ salt); }
        double h(int x, int y, int z, long salt) { return hash(x * 31 + y * 1013, z, seed ^ salt); }
        Block pick(Block[] a, int x, int y, int z, long salt) { return a[(int) (h(x, y, z, salt) * a.length) % a.length]; }

        void set(int x, int y, int z, BlockState st) { level.setBlock(m.set(x, y, z), st, 2); }
        BlockState get(int x, int y, int z) { return level.getBlockState(m.set(x, y, z)); }

        boolean natural(BlockState s) { return Earthwork.natural(s) || s.is(BlockTags.LOGS) || !s.getFluidState().isEmpty(); }

        /** Down from y to the first solid natural (or our own) block; returns that y. */
        int bed(int x, int z, int from) {
            for (int y = from; y > from - 40; y--) {
                BlockState s = get(x, y, z);
                if (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || s.is(BlockTags.LEAVES)) continue;
                return y;
            }
            return from - 40;
        }

        /** Clear natural ground and water above y (to y+top), leaving air. */
        void clearAbove(int x, int z, int y0, int top) {
            for (int y = y0; y <= y0 + top; y++) {
                BlockState s = get(x, y, z);
                if (s.isAir()) continue;
                if (natural(s)) {
                    if (s.getBlock() instanceof DoublePlantBlock) set(x, y + 1, z, Blocks.AIR.defaultBlockState());
                    set(x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
        }

        int wallTop(int u) {
            if (p.variant != RUBBLE) return DECK + 7;
            double n = Math.sin(u * 0.31 + seed % 97) * 0.5 + 0.5;
            return DECK + 1 + (int) Math.floor(n * 4 + hash(u, 7, seed) * 3);
        }

        void column(int x, int z) {
            int u = p.u(x, z), v = p.v(x, z);
            boolean inSpan = u >= p.uMin && u <= p.uMax;
            if (inSpan && v < WALL_V0 && v >= WALL_V0 - 8) backfill(x, z, v);
            if (Math.abs(u) <= 2 && v < WALL_V0 && v >= WALL_V0 - 14) { stairs(x, z, u, v); return; }
            if (inSpan && v >= WALL_V0 && v <= WALL_V1) { wall(x, z, u, v); return; }
            if (inSpan && v >= QUAY_V0 && v <= QUAY_V1) { quay(x, z, u, v); return; }
            if ((u == p.uMin - 1 || u == p.uMax + 1) && v >= WALL_V0 && v <= QUAY_V1) { endWall(x, z, u, v); return; }
            for (Pier pr : p.piers) {
                int len = pr.len;
                if (p.variant == RUBBLE) len = Math.max(5, (int) (len * (0.45 + 0.4 * hash(pr.u, 3, seed))));
                int du = u - pr.u, end = QUAY_V1 + len;
                boolean head = v > end - 4 && v <= end && Math.abs(du) <= 3;
                if (v > QUAY_V1 && v <= end && (Math.abs(du) <= 2 || head || (Math.abs(du) <= 4 && v > end - 4))) { pier(x, z, du, v - QUAY_V1, len, head); return; }
            }
            if (inSpan && v > QUAY_V1 && v <= QUAY_V1 + 10) dredge(x, z);
        }

        /** Behind the wall: where the sea comes round behind it, made-up land, so the wall never stands in the water. */
        void backfill(int x, int z, int v) {
            int top = DECK + 1 + Math.max(0, (WALL_V0 - v) / 3 - 1);
            if (!get(x, DECK - 1, z).getFluidState().isEmpty() || !get(x, DECK - 2, z).getFluidState().isEmpty()) {
                for (int y = top; y > DECK - 30; y--) {
                    BlockState s = get(x, y, z);
                    if (!(s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced())) break;
                    set(x, y, z, (y == top ? Blocks.GRASS_BLOCK : y > top - 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
                }
            }
        }

        void dredge(int x, int z) {
            for (int y = DECK + 12; y >= DECK - 6; y--) {
                BlockState s = get(x, y, z);
                if (s.isAir() && y > DECK - 1) continue;
                if (!natural(s) && !s.isAir()) continue;
                set(x, y, z, y >= DECK ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        }

        void quay(int x, int z, int u, int v) {
            int b = bed(x, z, DECK + 2);
            // the quay body: solid stone from the bed up to just under the deck (the seaward face dressed)
            for (int y = Math.max(b + 1, DECK - 30); y <= DECK - 1; y++) {
                boolean face = v == QUAY_V1;
                set(x, y, z, (face ? pick(p.variant == KEPT ? WALL : WALL_OLD, x, y, z, 11) : (h(x, y, z, 12) < 0.6 ? Blocks.STONE : Blocks.COBBLESTONE)).defaultBlockState());
            }
            clearAbove(x, z, DECK + 1, 14);
            Block[] pave = p.variant == KEPT ? PAVE : p.variant == OVERGROWN ? PAVE_OLD : PAVE_RUIN;
            Block top = v == QUAY_V1 ? (p.variant == RUBBLE && h(x, z, 21) < 0.3 ? Blocks.MOSSY_COBBLESTONE : Blocks.STONE_BRICKS) : pick(pave, x, DECK, z, 14);
            set(x, DECK, z, top.defaultBlockState());
            if (v == QUAY_V1 && p.variant == RUBBLE && h(x, z, 22) < 0.12) {          // a bite out of the quay edge
                set(x, DECK, z, Blocks.AIR.defaultBlockState()); set(x, DECK - 1, z, Blocks.WATER.defaultBlockState());
                return;
            }
            above(x, z, u, v);
        }

        /** What stands on the quay: bollards and lamps along the edge, crates and barrels by the wall, moss and rubble. */
        void above(int x, int z, int u, int v) {
            int y = DECK + 1;
            double r = h(x, z, 31);
            if (v == QUAY_V1 - 1 && Math.floorMod(u - p.uMin, 9) == 4) {
                if (p.variant == RUBBLE && r < 0.5) return;
                set(x, y, z, Blocks.SPRUCE_FENCE.defaultBlockState());
                walls.add(new BlockPos(x, y, z));
                if (p.variant != RUBBLE && Math.floorMod(u - p.uMin, 18) == 4) {
                    set(x, y + 1, z, Blocks.SPRUCE_FENCE.defaultBlockState()); walls.add(new BlockPos(x, y + 1, z));
                    set(x, y + 2, z, Blocks.LANTERN.defaultBlockState());
                }
                return;
            }
            if (Math.abs(u) <= 1) return;                                   // the way up to the stairs stays clear
            if (v <= QUAY_V0 + 1) {
                // crates in clusters along the wall
                double cl = hash(Math.floorDiv(u, 5), 3, seed ^ 41);
                if (cl < (p.variant == RUBBLE ? 0.25 : 0.55) && r < 0.75) {
                    Block c = r < 0.3 ? Blocks.BARREL : r < 0.5 ? Blocks.SPRUCE_PLANKS : r < 0.62 ? Blocks.HAY_BLOCK : r < 0.7 ? Blocks.COMPOSTER : Blocks.BARREL;
                    BlockState st = c == Blocks.BARREL ? Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP)
                            : c == Blocks.HAY_BLOCK ? Blocks.HAY_BLOCK.defaultBlockState() : c.defaultBlockState();
                    set(x, y, z, st);
                    if (v == QUAY_V0 && h(x, z, 43) < 0.4) set(x, y + 1, z, h(x, z, 44) < 0.5 ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                            : Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
                    return;
                }
            }
            switch (p.variant) {
                case OVERGROWN -> {
                    if (r < 0.28) set(x, y, z, Blocks.MOSS_CARPET.defaultBlockState());
                    else if (r < 0.36) set(x, y, z, Blocks.GRASS.defaultBlockState());
                    else if (r < 0.40 && v <= QUAY_V0 + 2) set(x, y, z, Blocks.FERN.defaultBlockState());
                    else if (r < 0.415) set(x, y, z, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                }
                case RUBBLE -> {
                    if (v <= QUAY_V0 + 3 && r < 0.3) {                      // the fallen wall, strewn at its foot
                        set(x, y, z, pick(WALL_OLD, x, y, z, 45).defaultBlockState());
                        if (r < 0.1 && v == QUAY_V0) set(x, y + 1, z, Blocks.COBBLESTONE_SLAB.defaultBlockState());
                    } else if (r < 0.38) set(x, y, z, Blocks.COBBLESTONE_SLAB.defaultBlockState());
                    else if (r < 0.43) set(x, y, z, Blocks.MOSS_CARPET.defaultBlockState());
                    else if (r < 0.46) set(x, y, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.from2DDataValue((int) (r * 400) & 3)));
                }
                default -> { if (r < 0.03) set(x, y, z, Blocks.MOSS_CARPET.defaultBlockState()); }
            }
        }

        void wall(int x, int z, int u, int v) {
            int top = wallTop(u);
            int b = bed(x, z, DECK + 1);
            Block[] mat = p.variant == KEPT ? WALL : WALL_OLD;
            for (int y = Math.min(b + 1, DECK); y <= top; y++) set(x, y, z, pick(mat, x, y, z, 51).defaultBlockState());
            clearAbove(x, z, top + 1, 12);
            // the stairs pass through the middle
            if (Math.abs(u) <= 1) {
                int step = DECK + 1 + (WALL_V1 - v);
                for (int y = DECK + 1; y < step; y++) set(x, y, z, pick(mat, x, y, z, 52).defaultBlockState());
                set(x, step, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, in));
                for (int y = step + 1; y <= Math.max(top, step + 4); y++) {
                    boolean arch = Math.abs(u) == 1 && y >= DECK + 6 && p.variant != RUBBLE;
                    set(x, y, z, arch ? Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, u < 0 ? left.getOpposite() : left).setValue(StairBlock.HALF, Half.TOP)
                            : Blocks.AIR.defaultBlockState());
                    if (arch) { for (int yy = y + 1; yy <= top; yy++) set(x, yy, z, pick(mat, x, yy, z, 53).defaultBlockState()); break; }
                }
                return;
            }
            // arched alcoves in the face, dark at the back, between red banners
            int k = Math.floorMod(u - p.uMin, 7);
            boolean alcove = k >= 2 && k <= 4 && u > p.uMin + 1 && u < p.uMax - 1;
            if (alcove) {
                int archTop = k == 3 ? DECK + 4 : DECK + 3;
                for (int y = DECK + 1; y <= Math.min(archTop, top); y++) {
                    if (v == WALL_V0) set(x, y, z, pick(DARK, x, y, z, 54).defaultBlockState());
                    else set(x, y, z, Blocks.AIR.defaultBlockState());
                }
                if (v == WALL_V1 && k != 3 && archTop + 1 <= top)
                    set(x, archTop + 1, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, k == 2 ? left.getOpposite() : left).setValue(StairBlock.HALF, Half.TOP));
                if (v == WALL_V0 + 1 && k == 3 && Math.floorMod(Math.floorDiv(u - p.uMin, 7), 3) == 1) {
                    // the harbour master's stores: a chest in every third alcove (with the harbour's lore books now and then)
                    BlockPos cp = new BlockPos(x, DECK + 1, z);
                    level.setBlock(cp, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, out), 2);
                    net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.setLootTable(level, level.getRandom(), cp,
                            new net.minecraft.resources.ResourceLocation(AncientPeaksLore.MODID, "chests/logres_harbour"));
                } else if (v == WALL_V0 + 1 && k == 3 && h(x, z, 55) < (p.variant == KEPT ? 0.5 : 0.2)) {
                    set(x, DECK + 1, z, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
                } else if (v == WALL_V0 + 1 && k == 3 && p.variant == OVERGROWN) {
                    set(x, DECK + 1, z, Blocks.MOSS_CARPET.defaultBlockState());
                }
                if (p.variant == RUBBLE && v == WALL_V1 && h(x, z, 56) < 0.35) set(x, DECK + 1, z, Blocks.COBBLESTONE.defaultBlockState());   // half choked
            }
            // parapet along the top, with a coping course
            if (v == WALL_V1 && p.variant != RUBBLE) {
                set(x, top + 1, z, (Math.floorMod(u, 2) == 0 ? Blocks.COBBLESTONE_WALL : Blocks.STONE_BRICK_SLAB).defaultBlockState());
                if (Math.floorMod(u, 2) == 0) walls.add(new BlockPos(x, top + 1, z));
            }
            if (v == WALL_V1) {
                // banners hang between the alcoves
                if (k == 0 && top >= DECK + 6) {
                    boolean keep = p.variant == KEPT || (p.variant == OVERGROWN ? h(x, z, 57) < 0.6 : h(x, z, 57) < 0.25);
                    BlockPos front = new BlockPos(x, top - 1, z).relative(out);
                    if (keep && level.getBlockState(front).isAir())
                        level.setBlock(front, Blocks.RED_WALL_BANNER.defaultBlockState().setValue(WallBannerBlock.FACING, out), 2);
                }
                if (p.variant == OVERGROWN) vines(x, z, top);
            }
            if (p.variant == OVERGROWN && v == WALL_V1 && h(x, z, 58) < 0.3) {
                set(x, top + 1, z, Blocks.MOSS_BLOCK.defaultBlockState());
                if (h(x, z, 59) < 0.4) set(x, top + 2, z, Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
            }
        }

        void vines(int x, int z, int top) {
            if (h(x, z, 61) > 0.45) return;
            BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
            int len = 2 + (int) (h(x, z, 62) * 6);
            var prop = PipeBlock.PROPERTY_BY_DIRECTION.get(in);
            for (int y = top; y > top - len && y > DECK; y--) {
                q.set(x, y, z).move(out);
                if (!level.getBlockState(q).isAir()) break;
                if (!level.getBlockState(new BlockPos(x, y, z)).isCollisionShapeFullBlock(level, new BlockPos(x, y, z))) break;
                level.setBlock(q, Blocks.VINE.defaultBlockState().setValue(prop, true), 2);
            }
        }

        void endWall(int x, int z, int u, int v) {
            // the quay's ends: the wall turns and steps down to the water
            int top = v <= WALL_V1 ? wallTop(u) : DECK + 1 + (v <= QUAY_V0 + 3 ? 1 : 0);
            int b = bed(x, z, DECK + 1);
            Block[] mat = p.variant == KEPT ? WALL : WALL_OLD;
            for (int y = Math.min(b + 1, DECK); y <= top; y++) set(x, y, z, pick(mat, x, y, z, 71).defaultBlockState());
            for (int y = DECK - 1; y > DECK - 30; y--) {
                BlockState s = get(x, y, z);
                if (!(s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced())) break;
                set(x, y, z, pick(mat, x, y, z, 72).defaultBlockState());
            }
            clearAbove(x, z, top + 1, 10);
        }

        void stairs(int x, int z, int u, int v) {
            // up from the wall to the land behind, cut into the slope
            int step = DECK + 1 + (WALL_V1 - v);
            int land = groundTop(x, z);
            if (step > land + 1 || step > p.stairTop + 2) return;
            Block[] mat = p.variant == KEPT ? WALL : WALL_OLD;
            if (Math.abs(u) == 2) {                                          // side walls
                for (int y = Math.min(land, step) - 3; y <= step + 1; y++) set(x, y, z, pick(mat, x, y, z, 81).defaultBlockState());
                clearAbove(x, z, step + 2, 6);
                return;
            }
            for (int y = step - 1; y >= step - 6; y--) {
                BlockState s = get(x, y, z);
                if (!(s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced())) break;
                set(x, y, z, Blocks.STONE.defaultBlockState());
            }
            set(x, step, z, (p.variant == RUBBLE && h(x, z, 82) < 0.3 ? Blocks.COBBLESTONE_STAIRS : Blocks.STONE_BRICK_STAIRS).defaultBlockState().setValue(StairBlock.FACING, in));
            for (int y = step + 1; y <= step + 6; y++) {
                BlockState s = get(x, y, z);
                if (!s.isAir() && natural(s)) set(x, y, z, Blocks.AIR.defaultBlockState());
            }
        }

        int groundTop(int x, int z) {
            int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
            for (int y = top; y > top - 20; y--) {
                BlockState s = get(x, y, z);
                if (s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) continue;
                return y;
            }
            return top;
        }

        /** du across the pier (0 = the middle), dv out from the quay edge (1 = first plank). */
        void pier(int x, int z, int du, int dv, int len, boolean head) {
            int a = Math.abs(du);
            boolean deck = (a <= 1 || head) && dv <= len;
            boolean post = (a == 2 && dv % 4 == 1 && dv <= len) || (head && a == 4 && (dv == len || dv == len - 3));
            if (a == 2 && head && !post) deck = true;
            for (int y = DECK + 8; y >= DECK; y--) {                        // clear the way (and anything of the coast in it)
                BlockState s = get(x, y, z);
                if (!s.isAir() && natural(s)) set(x, y, z, y >= DECK ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
            if (post) {
                if (p.variant == RUBBLE && hash(x, z, seed ^ 91) < 0.25) post = false;
                if (post) {
                    int b = bed(x, z, DECK - 1);
                    for (int y = Math.max(b + 1, DECK - 30); y < DECK - 1; y++)
                        set(x, y, z, (h(x, y, z, 92) < 0.6 ? Blocks.COBBLESTONE : Blocks.MOSSY_COBBLESTONE).defaultBlockState());
                    set(x, DECK - 1, z, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                    set(x, DECK, z, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                    // a dressed stone pier-head capped with a sawn log, like the old harbours
                    set(x, DECK + 1, z, (p.variant == KEPT ? Blocks.STONE_BRICKS : h(x, z, 98) < 0.5 ? Blocks.MOSSY_STONE_BRICKS : Blocks.CRACKED_STONE_BRICKS).defaultBlockState());
                    if (p.variant != RUBBLE || h(x, z, 99) < 0.5) set(x, DECK + 2, z, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                    if (p.variant == KEPT && dv % 8 == 1) set(x, DECK + 3, z, Blocks.LANTERN.defaultBlockState());
                    else if (p.variant == OVERGROWN && h(x, z, 93) < 0.4) set(x, DECK + 3, z, Blocks.MOSS_CARPET.defaultBlockState());
                    return;
                }
            }
            if (!deck) return;
            double gap = p.variant == KEPT ? 0.03 : p.variant == OVERGROWN ? 0.1 : 0.35 + 0.4 * dv / (double) len;
            if (h(x, z, 94) < gap) return;                                  // a missing plank
            boolean beam = a == 1 && !head;
            BlockState plank = beam ? Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
                    : (h(x, z, 95) < 0.18 ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS).defaultBlockState();
            if (a == 1 && !head) plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
            set(x, DECK, z, plank);
            if (p.variant == OVERGROWN && h(x, z, 96) < 0.18) set(x, DECK + 1, z, Blocks.MOSS_CARPET.defaultBlockState());
            if (p.variant == KEPT && head && a <= 1 && dv == len - 1 && h(x, z, 97) < 0.5)
                set(x, DECK + 1, z, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        }

        void finish() {
            for (BlockPos w : walls) {
                BlockState st = level.getBlockState(w);
                if (st.getBlock() instanceof FenceBlock || st.getBlock() instanceof WallBlock)
                    level.setBlock(w, Block.updateFromNeighbourShapes(st, level, w), 2);
            }
        }
    }

    static double hash(int x, int z, long seed) {
        long v = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        v = (v ^ (v >>> 31)) * 0xBF58476D1CE4E5B9L; v ^= v >>> 29;
        return ((v >>> 11) & ((1L << 40) - 1)) / (double) (1L << 40);
    }
}
