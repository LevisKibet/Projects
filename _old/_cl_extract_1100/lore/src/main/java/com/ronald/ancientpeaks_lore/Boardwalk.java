package com.ronald.ancientpeaks_lore;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The old boardwalk along the plains coast: a walkway three planks wide laid at the waterline, following the shore
 * round about two thirds of the plains' coastline (in long runs with gaps where it has rotted away), held up on the
 * seaward side by log piles driven down into the water. It leaves off at the harbour and picks up again past it.
 * Painted with the island, like the harbour; weathered to match it (kept, overgrown or near rubble).
 */
public final class Boardwalk {
    private Boardwalk() {}
    static final int DECK = Harbour.DECK;

    /** pts: path points (x, z); nx, nz: unit normal out to sea at each point; cum: distance along. */
    record Run(double[][] pts, double[][] nrm, double[] cum) {}
    record Plan(List<Run> runs, Map<Long, List<int[]>> buckets, int variant) {}
    private static final Plan NONE = new Plan(List.of(), Map.of(), 0);
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();

    static Plan plan(long seed, IslandSampler s, Island is, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager mgr) {
        return PLANS.computeIfAbsent(seed + "/" + is.cx + "/" + is.cz, k -> build(seed, s, is, mgr));
    }

    private static Plan build(long seed, IslandSampler s, Island is, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager mgr) {
        long t0 = System.currentTimeMillis();
        IslandSampler.Column col = new IslandSampler.Column();
        Harbour.Plan hp = Harbour.plan(seed, s, is, mgr);
        int n = (int) Math.max(720, 2 * Math.PI * is.boundR / 2.0);
        double[][] shore = new double[n][];
        boolean[] ok = new boolean[n];
        double prev = -1;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n, dx = Math.cos(a), dz = Math.sin(a);
            double r = prev < 0 ? 0 : Math.max(0, prev - 60);
            // back inland until on land, then out to the first sea
            int guard = 0;
            while (r > 0 && sea(s, col, is.cx + dx * r, is.cz + dz * r) && guard++ < 40) r = Math.max(0, r - 12);
            double found = -1;
            for (double rr = r; rr < is.boundR + 200; rr += 2) {
                if (sea(s, col, is.cx + dx * rr, is.cz + dz * rr)) { found = rr; break; }
            }
            if (found < 0) { prev = -1; continue; }
            prev = found;
            double sx = is.cx + dx * (found - 1), sz = is.cz + dz * (found - 1);
            shore[i] = new double[]{sx, sz};
            // the plains' low shore: plains a little inland, and no cliff at the water
            s.fill(col, (int) (sx - dx * 6), (int) (sz - dz * 6));
            if (col.is == null || col.h > 69) continue;
            boolean plains = false;
            for (double in : new double[]{14, 26}) {
                s.fill(col, (int) (sx - dx * in), (int) (sz - dz * in));
                if (col.is != null && s.zone(col, (int) col.h) == IslandSampler.Zone.PLAINS) plains = true;
            }
            if (!plains) continue;
            if (hp != null && hp.uMax() >= hp.uMin()) {                     // leave the harbour's stretch to the harbour
                int u = hp.u((int) sx, (int) sz), v = hp.v((int) sx, (int) sz);
                if (u >= hp.uMin() - 8 && u <= hp.uMax() + 8 && v >= -24 && v <= 30) continue;
            }
            ok[i] = true;
        }
        // contiguous runs of plains shore, cut into lengths; about two in three are kept
        List<Run> runs = new ArrayList<>();
        Random rnd = new Random(seed ^ is.cx * 9127L ^ is.cz * 3301L ^ 0xB0A2DL);
        int start = 0;
        while (start < n && ok[start]) start++;                           // begin at a gap so runs don't wrap
        if (start == n) start = 0;
        List<double[]> cur = new ArrayList<>();
        for (int k = 1; k <= n; k++) {
            int i = (start + k) % n;
            if (ok[i] && shore[i] != null) {
                if (!cur.isEmpty()) {
                    double[] last = cur.get(cur.size() - 1);
                    if (Math.hypot(last[0] - shore[i][0], last[1] - shore[i][1]) > 9) { flush(cur, runs, is, rnd); cur = new ArrayList<>(); }
                }
                cur.add(shore[i]);
            } else if (!cur.isEmpty()) { flush(cur, runs, is, rnd); cur = new ArrayList<>(); }
        }
        if (!cur.isEmpty()) flush(cur, runs, is, rnd);
        Map<Long, List<int[]>> buckets = new HashMap<>();
        for (int ri = 0; ri < runs.size(); ri++) {
            double[][] p = runs.get(ri).pts;
            for (int j = 0; j + 1 < p.length; j++) {
                int x0 = (int) Math.floor(Math.min(p[j][0], p[j + 1][0]) - 4), x1 = (int) Math.floor(Math.max(p[j][0], p[j + 1][0]) + 4);
                int z0 = (int) Math.floor(Math.min(p[j][1], p[j + 1][1]) - 4), z1 = (int) Math.floor(Math.max(p[j][1], p[j + 1][1]) + 4);
                for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++)
                    buckets.computeIfAbsent(ChunkPos.asLong(cx, cz), q -> new ArrayList<>()).add(new int[]{ri, j});
            }
        }
        double total = runs.stream().mapToDouble(r -> r.cum[r.cum.length - 1]).sum();
        AncientPeaksLore.LOGGER.info("[ancientpeaks_lore] boardwalk for island {},{}: {} runs, {} blocks long ({} ms)", is.cx, is.cz, runs.size(), (int) total,
                System.currentTimeMillis() - t0);
        return new Plan(runs, buckets, hp == null ? 0 : hp.variant());
    }

    private static boolean sea(IslandSampler s, IslandSampler.Column col, double x, double z) {
        s.fill(col, (int) Math.floor(x), (int) Math.floor(z));
        return col.is == null || col.h < 62;
    }

    /** Smooth a run of shore points, set the walk a block out from the land, and keep it in kept/lost lengths. */
    private static void flush(List<double[]> raw, List<Run> runs, Island is, Random rnd) {
        if (raw.size() < 8) return;
        int m = raw.size();
        double[][] sm = new double[m][2];
        for (int i = 0; i < m; i++) {
            double sx = 0, sz = 0; int c = 0;
            for (int j = Math.max(0, i - 4); j <= Math.min(m - 1, i + 4); j++) { sx += raw.get(j)[0]; sz += raw.get(j)[1]; c++; }
            sm[i][0] = sx / c; sm[i][1] = sz / c;
        }
        // cut into lengths of 40-90 blocks; ~2/3 of them kept
        List<double[]> piece = new ArrayList<>(); double len = 0, want = 40 + rnd.nextInt(50); boolean keep = rnd.nextDouble() < 0.72;
        for (int i = 0; i < m; i++) {
            if (i > 0) len += Math.hypot(sm[i][0] - sm[i - 1][0], sm[i][1] - sm[i - 1][1]);
            piece.add(sm[i]);
            if (len >= want || i == m - 1) {
                if (keep && piece.size() >= 4) runs.add(makeRun(piece, is));
                piece = new ArrayList<>(); piece.add(sm[i]); len = 0; want = 40 + rnd.nextInt(50);
                keep = !keep ? true : rnd.nextDouble() < 0.6;               // never two gaps in a row
            }
        }
    }

    private static Run makeRun(List<double[]> pts, Island is) {
        int m = pts.size();
        double[][] p = new double[m][2], nr = new double[m][2]; double[] cum = new double[m];
        for (int i = 0; i < m; i++) {
            double[] a = pts.get(Math.max(0, i - 1)), b = pts.get(Math.min(m - 1, i + 1));
            double tx = b[0] - a[0], tz = b[1] - a[1], tl = Math.hypot(tx, tz);
            double nx = tl == 0 ? 0 : -tz / tl, nz = tl == 0 ? 0 : tx / tl;
            // the normal that points away from the island's middle is out to sea
            double ox = pts.get(i)[0] - is.cx, oz = pts.get(i)[1] - is.cz;
            if (nx * ox + nz * oz < 0) { nx = -nx; nz = -nz; }
            nr[i][0] = nx; nr[i][1] = nz;
            p[i][0] = pts.get(i)[0] + nx * 1.5; p[i][1] = pts.get(i)[1] + nz * 1.5;
            if (i > 0) cum[i] = cum[i - 1] + Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
        }
        return new Run(p, nr, cum);
    }

    // ------------------------------------------------------------------ painting
    public static void decorate(WorldGenLevel level, ChunkPos cp) {
        if (!(level instanceof WorldGenRegion region)) return;
        IslandSampler s = ((HighlandsAccess) (Object) region.getLevel().getChunkSource().randomState()).ancientpeaks$sampler();
        if (s == null) return;
        Island is = s.islandAt(cp.getMiddleBlockX(), cp.getMiddleBlockZ());
        if (is == null) return;
        Plan p = plan(level.getSeed(), s, is, region.getLevel().getStructureManager());
        List<int[]> segs = p.buckets.get(cp.toLong());
        if (segs == null) return;
        long seed = level.getSeed() ^ 0x2545F4914F6CDD1DL;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        List<BlockPos> fences = new ArrayList<>();
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                double best = 1e9, along = 0, side = 0; Run br = null;
                for (int[] sg : segs) {
                    Run r = p.runs.get(sg[0]);
                    double[] a = r.pts[sg[1]], b = r.pts[sg[1] + 1];
                    double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
                    double u = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x + 0.5 - a[0]) * dx + (z + 0.5 - a[1]) * dz) / l2));
                    double qx = a[0] + u * dx, qz = a[1] + u * dz, d = Math.hypot(x + 0.5 - qx, z + 0.5 - qz);
                    if (d < best) {
                        best = d; br = r; along = r.cum[sg[1]] + u * Math.sqrt(l2);
                        double[] nn = r.nrm[sg[1]];
                        side = (x + 0.5 - qx) * nn[0] + (z + 0.5 - qz) * nn[1];
                    }
                }
                if (br == null || best > 3.2) continue;
                double runLen = br.cum[br.cum.length - 1];
                if (along < 0.5 || along > runLen - 0.5) continue;              // square ends
                if (best <= 1.55) deck(level, m, x, z, p.variant, seed);
                else if (side > 0 && best <= 2.7 && post(along, 4)) pile(level, m, x, z, along, p.variant, seed, fences);
                else if (side < 0 && best <= 2.7 && post(along, 12)) pile(level, m, x, z, along, p.variant, seed, fences);
            }
        }
        for (BlockPos f : fences) {
            BlockState st = level.getBlockState(f);
            if (st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock) level.setBlock(f, Block.updateFromNeighbourShapes(st, level, f), 2);
        }
    }

    private static boolean post(double along, double every) { double k = along % every; return k < 1.0; }

    private static boolean clearable(BlockState s) {
        return Earthwork.natural(s) || s.is(BlockTags.LOGS) || !s.getFluidState().isEmpty();
    }

    /** Ground (or sea bed) top under the deck; MIN_VALUE where the land rises too high for a walk at the waterline. */
    private static int top(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z) {
        for (int y = DECK + 6; y > DECK - 30; y--) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || s.is(BlockTags.LEAVES)) continue;
            if (!Earthwork.natural(s) && !s.is(Blocks.SAND) && !s.is(Blocks.GRAVEL)) return Integer.MIN_VALUE;   // something built
            return y;
        }
        return DECK - 30;
    }

    private static void deck(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, int variant, long seed) {
        int t = top(level, m, x, z);
        if (t == Integer.MIN_VALUE || t > DECK + 3) return;
        for (int y = DECK + 1; y <= DECK + 6; y++) {                   // a low bank in the way is cut back
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir()) continue;
            if (clearable(s)) {
                if (s.getBlock() instanceof DoublePlantBlock) level.setBlock(m.above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        double h = Harbour.hash(x, z, seed);
        double gap = variant == Harbour.KEPT ? 0.04 : variant == Harbour.OVERGROWN ? 0.12 : 0.38;
        if (h < gap) return;                                               // a rotten plank gone
        Block plank = h < gap + 0.12 ? Blocks.DARK_OAK_PLANKS : h < gap + 0.2 ? Blocks.OAK_PLANKS : Blocks.SPRUCE_PLANKS;
        level.setBlock(m.set(x, DECK, z), plank.defaultBlockState(), 2);
        if (variant == Harbour.OVERGROWN && Harbour.hash(z, x, seed) < 0.15 && level.getBlockState(m.set(x, DECK + 1, z)).isAir())
            level.setBlock(m, Blocks.MOSS_CARPET.defaultBlockState(), 2);
    }

    private static void pile(WorldGenLevel level, BlockPos.MutableBlockPos m, int x, int z, double along, int variant, long seed, List<BlockPos> fences) {
        int t = top(level, m, x, z);
        if (t == Integer.MIN_VALUE || t > DECK + 1) return;
        if (variant == Harbour.RUBBLE && Harbour.hash(x, z, seed ^ 3) < 0.3) return;
        for (int y = DECK + 1; y <= DECK + 6; y++) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (!s.isAir() && clearable(s)) level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
        }
        // the log driven down to the bed, standing a block proud of the walk
        for (int y = Math.max(t + 1, DECK - 30); y <= DECK + 1; y++)
            level.setBlock(m.set(x, y, z), (y < DECK ? Blocks.SPRUCE_LOG : Blocks.STRIPPED_SPRUCE_LOG).defaultBlockState(), 2);
        boolean lamp = variant == Harbour.KEPT && ((int) Math.floor(along / 4)) % 6 == 0;
        if (lamp) {
            level.setBlock(m.set(x, DECK + 2, z), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
            fences.add(m.immutable());
            level.setBlock(m.set(x, DECK + 3, z), Blocks.LANTERN.defaultBlockState(), 2);
        } else if (variant == Harbour.OVERGROWN && Harbour.hash(x, z, seed ^ 5) < 0.35) {
            level.setBlock(m.set(x, DECK + 2, z), Blocks.MOSS_CARPET.defaultBlockState(), 2);
        }
    }
}
