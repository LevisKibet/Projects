package com.ronald.rivergence.dev;

import com.ronald.rivergence.plan.Drainage;
import com.ronald.rivergence.plan.DrainageMap;
import com.ronald.rivergence.plan.FastHeights;
import com.ronald.rivergence.plan.Planner;
import com.ronald.rivergence.plan.RegionPlan;
import com.ronald.rivergence.world.RiverAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;

/**
 * Development only (-Drivergence.probe=... on a dedicated server; never in a real game).
 *   map:   draws drainage plans of the land round spawn, then stops.
 *   water: picks spots from the plan round spawn (big river, streams, steep streams, a river mouth, lakes), generates
 *          them, lets the water run, then draws each in 3D with a map, counts what went wrong, and stops.
 */
public final class DevProbe {
    private DevProbe() {}

    record Spot(String name, int x, int z) {}

    private static final String MODE = System.getProperty("rivergence.probe", "");
    private static final int HALF = 40, WAIT = Integer.getInteger("rivergence.probeWait", 300);
    private static final String AT = System.getProperty("rivergence.probeAt", "");
    private static int t = -1;
    private static final List<Spot> spots = new ArrayList<>();

    public static void init() {
        if (MODE.isEmpty()) return;
        MinecraftForge.EVENT_BUS.addListener(DevProbe::tick);
    }

    private static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer srv = e.getServer();
        ServerLevel lvl = srv.overworld();
        t++;
        try {
            if (MODE.equals("map")) { if (t == 3) { maps(lvl); srv.halt(false); } return; }
            if (t == 3) setUp(lvl);
            if (t == 3 + WAIT) { report(lvl); srv.halt(false); }
        } catch (Throwable ex) {
            ex.printStackTrace();
            srv.halt(false);
        }
    }

    private static void maps(ServerLevel lvl) throws Exception {
        var rs = lvl.getChunkSource().randomState();
        FastHeights fast = new FastHeights(rs, lvl.getMinBuildHeight(), lvl.getMaxBuildHeight());
        File dir = new File("probe");
        dir.mkdirs();
        int cx = lvl.getSharedSpawnPos().getX(), cz = lvl.getSharedSpawnPos().getZ();
        int[][] views = {{4096, 16, 3, 24}, {1024, 4, 3, 40}};
        for (int[] v : views) {
            int size = v[0], cell = v[1], n = size / cell;
            Drainage p = new Drainage(cx - size / 2, cz - size / 2, cell, n, fast);
            ImageIO.write(DrainageMap.draw(p, v[2], v[3]), "png", new File(dir, "drainage_" + size + ".png"));
        }
    }

    /** Forced chunks keep their scheduled ticks waiting without a player nearby; in the probe they run regardless. */
    private static void letTicksRun(Object ticks) {
        try {
            for (java.lang.reflect.Field f : net.minecraft.world.ticks.LevelTicks.class.getDeclaredFields()) {
                if (f.getType() != java.util.function.LongPredicate.class) continue;
                f.setAccessible(true);
                f.set(ticks, (java.util.function.LongPredicate) l -> true);
            }
        } catch (Throwable e) { System.out.println("[probe] could not open the ticks: " + e); }
    }

    private static void setUp(ServerLevel lvl) throws Exception {
        Planner pl = ((RiverAccess) (Object) lvl.getChunkSource().randomState()).rivergence$planner();
        int sx = lvl.getSharedSpawnPos().getX(), sz = lvl.getSharedSpawnPos().getZ();
        long t0 = System.currentTimeMillis();
        RegionPlan p = pl.region(sx, sz, true);
        System.out.println(String.format("[probe] region %d,%d planned in %d ms (sampling %d ms): %d channel segments, %d lakes",
                p.rx, p.rz, p.nanos / 1000000, p.sampleNanos / 1000000, p.segments.size(), p.lakes.size()));
        int sea = 0, ch = 0; float mx = 0, hmin = 999, hmax = -999;
        for (int k = 0; k < p.d.h.length; k++) { if (p.d.sea(k)) sea++; if (p.channel(k)) ch++; mx = Math.max(mx, p.d.acc[k]); hmin = Math.min(hmin, p.d.h[k]); hmax = Math.max(hmax, p.d.h[k]); }
        System.out.println("[probe] cells " + p.d.h.length + " sea " + sea + " channel " + ch + " max acc " + mx + " h " + hmin + ".." + hmax);
        overview(lvl, pl, sx, sz);
        if (!AT.isEmpty()) {
            for (String s : AT.split(";")) { String[] q = s.split(","); spots.add(new Spot(q[0], Integer.parseInt(q[1]), Integer.parseInt(q[2]))); }
        } else pick(p, sx, sz);
        if (AT.isEmpty()) {
            BlockPos v = lvl.findNearestMapStructure(net.minecraft.tags.StructureTags.VILLAGE, new BlockPos(sx, 64, sz), 40, false);
            if (v != null) spots.add(new Spot("village", v.getX(), v.getZ()));
            System.out.println("[probe] nearest village " + v);
        }
        float wmax = 0; int mouths = 0;
        for (RegionPlan.Segment s : p.segments) { wmax = Math.max(wmax, s.hw); if (s.level[s.level.length - 1] <= 62 && s.level[0] > 62) mouths++; }
        System.out.println("[probe] widest half-width " + wmax + ", " + mouths + " segments reach the sea");
        Set<Long> chunks = new LinkedHashSet<>();
        for (Spot sp : spots)
            for (int cx = (sp.x - HALF - 16) >> 4; cx <= (sp.x + HALF + 16) >> 4; cx++)
                for (int cz = (sp.z - HALF - 16) >> 4; cz <= (sp.z + HALF + 16) >> 4; cz++) chunks.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
        System.out.println("[probe] " + spots.size() + " spots, " + chunks.size() + " chunks: " + spots);
        letTicksRun(lvl.getFluidTicks());
        letTicksRun(lvl.getBlockTicks());
        int done = 0;
        long t1 = System.currentTimeMillis();
        for (long c : chunks) {
            int cx = net.minecraft.world.level.ChunkPos.getX(c), cz = net.minecraft.world.level.ChunkPos.getZ(c);
            lvl.setChunkForced(cx, cz, true);
            lvl.getChunk(cx, cz, ChunkStatus.FULL, true);
            if (++done % 100 == 0) System.out.println("[probe] generated " + done + " in " + (System.currentTimeMillis() - t1) / 1000 + "s");
        }
        System.out.println("[probe] generated " + done + " chunks in " + (System.currentTimeMillis() - t1) / 1000 + "s");
    }

    /** 4096 blocks round spawn, 4 blocks a pixel: land shaded by height, sea dark, planned water bright blue. */
    private static void overview(ServerLevel lvl, Planner pl, int sx, int sz) throws Exception {
        int R = 2048, step = 4, W = 2 * R / step;
        BufferedImage img = new BufferedImage(W, W, BufferedImage.TYPE_INT_RGB);
        Planner.Column c = new Planner.Column();
        for (int b = 0; b < W; b++) for (int a = 0; a < W; a++) {
            int x = sx - R + a * step, z = sz - R + b * step;
            pl.column(x, z, c, true);
            RegionPlan p = c.plan;
            int ci = (x - p.d.x0) / RegionPlan.CELL, cj = (z - p.d.z0) / RegionPlan.CELL;
            int k = ci + cj * p.d.n;
            int rgb;
            if (c.wet()) rgb = c.lake ? 0x3A6FE0 : 0x1E90FF;
            else if (p.d.sea(k)) rgb = 0x1B2F55;
            else { int g = (int) Math.max(40, Math.min(230, 60 + (p.d.h[k] - 62) * 1.3)); rgb = (g / 2) << 16 | g << 8 | (g / 2); }
            img.setRGB(a, b, rgb);
        }
        File dir = new File("probe");
        dir.mkdirs();
        ImageIO.write(img, "png", new File(dir, "overview.png"));
        System.out.println("[probe] overview drawn");
    }

    /** Spots worth looking at near spawn: the biggest river, a mouth, steep and gentle streams, lakes. */
    private static void pick(RegionPlan p, int sx, int sz) {
        double R = 900;
        List<RegionPlan.Segment> near = new ArrayList<>();
        for (RegionPlan.Segment s : p.segments) {
            int m = s.x.length / 2;
            if (Math.hypot(s.x[m] - sx, s.z[m] - sz) < R) near.add(s);
        }
        near.sort((a, b) -> Float.compare(b.acc, a.acc));
        List<Spot> c = new ArrayList<>();
        if (!near.isEmpty()) c.add(at("river", near.get(0)));
        for (RegionPlan.Segment s : near) if (s.level[s.level.length - 1] <= 62 && s.level[0] > 62) { c.add(at("mouth", s)); break; }
        List<RegionPlan.Segment> steep = new ArrayList<>(near), small = new ArrayList<>();
        steep.sort((a, b) -> Float.compare(b.level[0] - b.level[b.level.length - 1], a.level[0] - a.level[a.level.length - 1]));
        for (int i = 0; i < Math.min(2, steep.size()); i++) c.add(at("steep" + i, steep.get(i)));
        for (RegionPlan.Segment s : near) if (s.acc < RegionPlan.STREAM_CELLS * 2 && s.level[0] - s.level[s.level.length - 1] < 4) small.add(s);
        for (int i = 0; i < Math.min(2, small.size()); i++) c.add(at("stream" + i, small.get(small.size() * (i + 1) / 3)));
        List<RegionPlan.Spring> sps = new ArrayList<>(p.springs);
        sps.sort(Comparator.comparingDouble(q -> Math.hypot(q.x - sx, q.z - sz)));
        System.out.println("[probe] " + p.springs.size() + " springs");
        for (int i = 0; i < Math.min(3, sps.size()); i++) c.add(0, new Spot("spring" + i, (int) sps.get(i).x, (int) sps.get(i).z));
        List<RegionPlan.Lake> lakes = new ArrayList<>(p.lakes);
        lakes.sort(Comparator.comparingDouble(l -> Math.hypot(l.gx0 + l.w * l.step / 2.0 - sx, l.gz0 + l.h * l.step / 2.0 - sz)));
        for (int i = 0; i < Math.min(2, lakes.size()); i++) {
            RegionPlan.Lake l = lakes.get(i);
            // its shore: a grid point just inside it
            int best = -1;
            for (int k = 0; k < l.sd.length; k++) if (l.sd[k] > 0 && (best < 0 || Math.abs(l.sd[k] - 12) < Math.abs(l.sd[best] - 12))) best = k;
            if (best >= 0) c.add(new Spot("lake" + i, l.gx0 + (best % l.w) * l.step, l.gz0 + (best / l.w) * l.step));
        }
        for (Spot sp : c) {
            boolean close = false;
            for (Spot k : spots) if (Math.abs(k.x - sp.x) < HALF * 2 && Math.abs(k.z - sp.z) < HALF * 2) close = true;
            if (!close) spots.add(sp);
        }
    }

    private static Spot at(String name, RegionPlan.Segment s) {
        int m = s.x.length / 2;
        return new Spot(name, (int) s.x[m], (int) s.z[m]);
    }

    // ------------------------------------------------------------------ measuring and drawing

    private static void report(ServerLevel lvl) throws Exception {
        File dir = new File("probe");
        dir.mkdirs();
        try (PrintWriter out = new PrintWriter(new File(dir, "report.txt"))) {
            for (Spot sp : spots) {
                int[] m = measure(lvl, sp);
                String line = String.format("%-8s %6d %6d  floating %4d  outside the plan %4d  flowing %4d  sources %4d", sp.name, sp.x, sp.z, m[0], m[1], m[2], m[3]);
                System.out.println("[probe] " + line);
                out.println(line);
                ImageIO.write(render(lvl, sp), "png", new File(dir, sp.name + ".png"));
            }
        }
    }

    /** Water above sea level: with air under it, outside any planned water column, flowing, source. */
    private static int[] measure(ServerLevel lvl, Spot sp) {
        Planner pl = ((RiverAccess) (Object) lvl.getChunkSource().randomState()).rivergence$planner();
        Planner.Column c = new Planner.Column();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int[] r = new int[4];
        for (int x = sp.x - HALF; x < sp.x + HALF; x++) for (int z = sp.z - HALF; z < sp.z + HALF; z++) {
            pl.column(x, z, c, true);
            int top = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            for (int y = 63; y <= top; y++) {
                FluidState f = lvl.getFluidState(m.set(x, y, z));
                if (!f.is(FluidTags.WATER)) continue;
                if (lvl.getBlockState(m.set(x, y - 1, z)).isAir()) r[0]++;
                if (!c.wet()) r[1]++;
                if (f.isSource()) r[3]++; else r[2]++;
            }
        }
        return r;
    }

    private static final int S = 6;

    private static BufferedImage render(ServerLevel lvl, Spot sp) {
        int W = HALF * 2;
        int lo = 400, hi = -100;
        for (int x = sp.x - HALF; x < sp.x + HALF; x++) for (int z = sp.z - HALF; z < sp.z + HALF; z++) {
            int h = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            lo = Math.min(lo, h); hi = Math.max(hi, h);
        }
        lo = Math.max(lvl.getMinBuildHeight(), lo - 6);
        int range = hi - lo + 2;
        int iw = 2 * W * S, ih = W * S + range * S + 20;
        int MS = 8;
        ih = Math.max(ih, W * MS + 560);
        BufferedImage img = new BufferedImage(iw * 2 + 20 + W * MS, Math.max(ih, W * MS + 30), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xBFD8F0));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        for (int view = 0; view < 2; view++) {
            int ox = view * (iw + 10) + W * S, oy = range * S + 10 - lo * 0;
            draw(lvl, sp, g, view == 1, ox, oy, lo, hi + 1);
        }
        map(lvl, sp, g, iw * 2 + 20, 24, MS);
        g.setColor(Color.BLACK);
        g.drawString(sp.name + " @ " + sp.x + "," + sp.z + "  (left: from south-east, right: from north-west)", 6, 14);
        g.dispose();
        return img;
    }

    /** Top-down: ground shaded by height (contours every 4), water blue (light = flowing), height printed every 6 blocks. */
    private static void map(ServerLevel lvl, Spot sp, Graphics2D g, int ox, int oy, int ms) {
        int W = HALF * 2;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int[][] gh = new int[W][W];
        for (int a = 0; a < W; a++) for (int b = 0; b < W; b++) {
            int x = sp.x - HALF + a, z = sp.z - HALF + b;
            int y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            while (y > 0) { BlockState st = lvl.getBlockState(m.set(x, y, z)); if (!st.isAir() && !hidden(st)) break; y--; }
            gh[a][b] = y;
            BlockState st = lvl.getBlockState(m.set(x, y, z));
            FluidState f = st.getFluidState();
            int g0 = 60 + Math.floorMod(y, 32) * 5;
            Color c = f.is(FluidTags.WATER) ? (f.isSource() ? new Color(0x2F5FD0) : new Color(0x7FB8FF))
                    : new Color(g0, Math.min(255, g0 + 20), g0);
            g.setColor(c);
            g.fillRect(ox + a * ms, oy + b * ms, ms, ms);
        }
        g.setColor(new Color(0, 0, 0, 90));
        for (int a = 0; a < W; a++) for (int b = 0; b < W; b++) {
            if (a + 1 < W && gh[a][b] / 4 != gh[a + 1][b] / 4) g.drawLine(ox + (a + 1) * ms, oy + b * ms, ox + (a + 1) * ms, oy + (b + 1) * ms);
            if (b + 1 < W && gh[a][b] / 4 != gh[a][b + 1] / 4) g.drawLine(ox + a * ms, oy + (b + 1) * ms, ox + (a + 1) * ms, oy + (b + 1) * ms);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
        g.setColor(Color.BLACK);
        for (int a = 0; a < W; a += 6) for (int b = 0; b < W; b += 6) g.drawString(String.valueOf(gh[a][b]), ox + a * ms, oy + b * ms + 8);
        g.drawString("N up, x right; red: planned water's edge", ox, oy - 4);
        // the plan: columns planned wet, outlined
        Planner pl = ((RiverAccess) (Object) lvl.getChunkSource().randomState()).rivergence$planner();
        Planner.Column c = new Planner.Column();
        boolean[][] wet = new boolean[W][W];
        for (int a = 0; a < W; a++) for (int b = 0; b < W; b++) { pl.column(sp.x - HALF + a, sp.z - HALF + b, c, true); wet[a][b] = c.wet(); }
        g.setColor(Color.RED);
        for (int a = 0; a < W; a++) for (int b = 0; b < W; b++) {
            if (a + 1 < W && wet[a][b] != wet[a + 1][b]) g.drawLine(ox + (a + 1) * ms, oy + b * ms, ox + (a + 1) * ms, oy + (b + 1) * ms);
            if (b + 1 < W && wet[a][b] != wet[a][b + 1]) g.drawLine(ox + a * ms, oy + (b + 1) * ms, ox + (a + 1) * ms, oy + (b + 1) * ms);
        }
    }

    private static void draw(ServerLevel lvl, Spot sp, Graphics2D g, boolean flip, int ox, int oy, int lo, int hi) {
        int W = HALF * 2;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // local coordinates a, b in 0..W-1, toward the viewer as they grow
        for (int sum = 0; sum <= 2 * (W - 1); sum++) {
            for (int a = Math.max(0, sum - (W - 1)); a <= Math.min(W - 1, sum); a++) {
                int b = sum - a;
                int x = flip ? sp.x + HALF - 1 - a : sp.x - HALF + a;
                int z = flip ? sp.z + HALF - 1 - b : sp.z - HALF + b;
                for (int y = lo; y <= hi; y++) {
                    BlockState st = lvl.getBlockState(m.set(x, y, z));
                    FluidState f = st.getFluidState();
                    boolean water = f.is(FluidTags.WATER);
                    if (st.isAir() || hidden(st)) continue;
                    // faces toward the viewer: top, +a side, +b side
                    int ax = flip ? -1 : 1, bz = flip ? -1 : 1;
                    boolean showTop = see(lvl, m.set(x, y + 1, z), water);
                    boolean showA = a == W - 1 || see(lvl, m.set(x + ax, y, z), water);
                    boolean showB = b == W - 1 || see(lvl, m.set(x, y, z + bz), water);
                    if (!showTop && !showA && !showB) continue;
                    m.set(x, y, z);
                    Color col;
                    int alpha = 255;
                    if (water) { col = f.isSource() ? new Color(0x2F5FD0) : new Color(0x6FA8FF); alpha = 170; }
                    else {
                        int rgb = st.getMapColor(lvl, m).col;
                        if (rgb == 0) rgb = 0x808080;
                        col = new Color(rgb);
                    }
                    double h = y - lo;
                    if (showTop) poly(g, ox, oy, a, b, h, 0, col, 1.0, alpha);
                    if (showA) poly(g, ox, oy, a, b, h, 1, col, 0.78, alpha);
                    if (showB) poly(g, ox, oy, a, b, h, 2, col, 0.62, alpha);
                }
            }
        }
    }

    /** Trees and plants are left out of the drawing so the ground and the water show. */
    private static boolean hidden(BlockState st) {
        if (st.getFluidState().is(FluidTags.WATER)) return false;
        return st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS) || !st.canOcclude();
    }

    private static boolean see(ServerLevel lvl, BlockPos p, boolean selfWater) {
        BlockState st = lvl.getBlockState(p);
        if (st.isAir() || hidden(st)) return true;
        boolean w = st.getFluidState().is(FluidTags.WATER);
        if (selfWater) return !w && !st.canOcclude();
        return w || !st.canOcclude();
    }

    private static void poly(Graphics2D g, int ox, int oy, int a, int b, double y, int face, Color c, double shade, int alpha) {
        double[][] p;
        if (face == 0) p = new double[][]{{a, y + 1, b}, {a + 1, y + 1, b}, {a + 1, y + 1, b + 1}, {a, y + 1, b + 1}};
        else if (face == 1) p = new double[][]{{a + 1, y, b}, {a + 1, y + 1, b}, {a + 1, y + 1, b + 1}, {a + 1, y, b + 1}};
        else p = new double[][]{{a, y, b + 1}, {a + 1, y, b + 1}, {a + 1, y + 1, b + 1}, {a, y + 1, b + 1}};
        int[] xs = new int[4], ys = new int[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = ox + (int) Math.round((p[i][0] - p[i][2]) * S);
            ys[i] = oy + (int) Math.round((p[i][0] + p[i][2]) * S / 2.0 - p[i][1] * S);
        }
        g.setColor(new Color((int) (c.getRed() * shade), (int) (c.getGreen() * shade), (int) (c.getBlue() * shade), alpha));
        g.fillPolygon(xs, ys, 4);
    }
}
