package com.ronald.ancientpeaks.world;

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
 * Development only (-Dancientpeaks.probe=true on a dedicated server): generates the island round its streams and
 * tarns, lets the water run for a while, then draws each spot in 3D and counts what went wrong. Never runs in a real game.
 */
public final class DevProbe {
    private DevProbe() {}

    public static void init() {
        if (!Boolean.getBoolean("ancientpeaks.probe")) return;
        MinecraftForge.EVENT_BUS.addListener(DevProbe::tick);
    }

    record Spot(String name, int x, int z) {}

    private static int t = -1;
    private static final List<Spot> spots = new ArrayList<>();
    private static final int HALF = 30, WAIT = Integer.getInteger("ancientpeaks.probeWait", 500);
    private static final String ONLY = System.getProperty("ancientpeaks.probeOnly", "");

    private static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer srv = e.getServer();
        ServerLevel lvl = srv.overworld();
        t++;
        try {
            if (t == 3) setUp(lvl);
            String pt = System.getProperty("ancientpeaks.probeTick", "");
            if (!pt.isEmpty() && (t == 4 || t == 40 || t == 200)) {
                String[] q = pt.split(",");
                BlockPos bp = new BlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
                System.out.println("[probe] tick check t" + t + " " + bp + " " + lvl.getBlockState(bp) + " scheduled " + lvl.getFluidTicks().hasScheduledTick(bp, net.minecraft.world.level.material.Fluids.WATER)
                         + " ticking " + lvl.isPositionEntityTicking(bp) + " withEntities " + lvl.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(bp)) + "/" + lvl.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.asLong(bp))
                        + " game " + lvl.getGameTime() + " peek " + describe(lvl.getChunkAt(bp).getFluidTicks()) + " next " + lvl.getBlockState(bp.west()) + " / " + lvl.getBlockState(bp.north()));
            }
            if (t == 3 + WAIT) { report(lvl); srv.halt(false); }
        } catch (Throwable ex) {
            ex.printStackTrace();
            srv.halt(false);
        }
    }

    /** Forced chunks keep their scheduled ticks waiting without a player nearby; in the probe they run regardless. */
    private static void letTicksRun(Object ticks) {
        try {
            for (java.lang.reflect.Field f : net.minecraft.world.ticks.LevelTicks.class.getDeclaredFields()) {
                if (f.getType() != java.util.function.LongPredicate.class) continue;
                f.setAccessible(true);
                f.set(ticks, (java.util.function.LongPredicate) l -> true);
                System.out.println("[probe] ticks run everywhere");
            }
        } catch (Throwable e) { System.out.println("[probe] could not open the ticks: " + e); }
    }

    private static String describe(Object ticks) {
        if (ticks instanceof net.minecraft.world.ticks.LevelChunkTicks<?> t) { var p = t.peek(); return p == null ? "none" : p.triggerTick() + " n=" + t.count(); }
        return String.valueOf(ticks);
    }

    private static void setUp(ServerLevel lvl) {
        IslandSampler s = ((HighlandsAccess) (Object) lvl.getChunkSource().randomState()).ancientpeaks$sampler();
        Island is = s.island();
        System.out.println("[probe] island at " + is.cx + "," + is.cz + " streams " + is.rivers.size() + " tarns " + is.tarns.size());
        for (int k = 0; k < is.tarns.size(); k++) {
            double[] w = is.tarnWorld(k);
            spots.add(new Spot("tarn" + k, (int) w[0], (int) w[1]));
        }
        for (int i = 0; i < is.rivers.size(); i++) {
            Island.Path p = is.rivers.get(i);
            if (p.wid == null) continue;
            int n = p.u.length;
            for (int f = 0; f <= 8; f++) {
                int k = Math.min(n - 1, (int) Math.round(f / 8.0 * (n - 1)));
                int[] w = is.toWorld(p.u[k], p.v[k]);
                spots.add(new Spot("s" + i + "_" + f, w[0], w[1]));
            }
        }
        // drop spots too close to one taken already (tarns first)
        List<Spot> keep = new ArrayList<>();
        for (Spot sp : spots) {
            if (!ONLY.isEmpty() && !Arrays.asList(ONLY.split(",")).contains(sp.name)) continue;
            boolean near = false;
            for (Spot k : keep) if (Math.abs(k.x - sp.x) < 34 && Math.abs(k.z - sp.z) < 34) near = true;
            if (!near) keep.add(sp);
        }
        spots.clear();
        spots.addAll(keep);
        Set<Long> chunks = new LinkedHashSet<>();
        for (Spot sp : spots)
            for (int cx = (sp.x - HALF - 16) >> 4; cx <= (sp.x + HALF + 16) >> 4; cx++)
                for (int cz = (sp.z - HALF - 16) >> 4; cz <= (sp.z + HALF + 16) >> 4; cz++) chunks.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
        System.out.println("[probe] " + spots.size() + " spots, " + chunks.size() + " chunks");
        // spawn chunks always tick (forced ones don't run their scheduled ticks without a player near), so the
        // water can run: with one spot, the world's spawn is moved onto it
        letTicksRun(lvl.getFluidTicks());
        letTicksRun(lvl.getBlockTicks());
        int done = 0;
        long t0 = System.currentTimeMillis();
        for (long c : chunks) {
            int cx = net.minecraft.world.level.ChunkPos.getX(c), cz = net.minecraft.world.level.ChunkPos.getZ(c);
            lvl.setChunkForced(cx, cz, true);
            lvl.getChunk(cx, cz, ChunkStatus.FULL, true);
            if (++done % 100 == 0) System.out.println("[probe] generated " + done + " in " + (System.currentTimeMillis() - t0) / 1000 + "s");
        }
    }

    // ------------------------------------------------------------------ measuring and drawing

    private static void report(ServerLevel lvl) throws Exception {
        IslandSampler s = ((HighlandsAccess) (Object) lvl.getChunkSource().randomState()).ancientpeaks$sampler();
        File dir = new File("probe");
        dir.mkdirs();
        try (PrintWriter out = new PrintWriter(new File(dir, "report.txt"))) {
            for (Spot sp : spots) {
                int[] m = measure(lvl, s, sp);
                String line = String.format("%-8s %6d %6d  float %4d  spill %4d  flowing %4d  sources %4d  lowTarn %4d", sp.name, sp.x, sp.z, m[0], m[1], m[2], m[3], m[4]);
                System.out.println("[probe] " + line);
                out.println(line);
                ImageIO.write(render(lvl, sp), "png", new File(dir, sp.name + ".png"));
            }
        }
    }

    /** floating water, water outside any planned water, flowing blocks, source blocks, water in tarn columns below the bowl. */
    private static int[] measure(ServerLevel lvl, IslandSampler s, Spot sp) {
        int[] r = new int[5];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        IslandSampler.Column c = new IslandSampler.Column();
        for (int x = sp.x - HALF; x < sp.x + HALF; x++) for (int z = sp.z - HALF; z < sp.z + HALF; z++) {
            int top = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            s.fill(c, x, z);
            int planned = s.waterTop(c);
            for (int y = 64; y <= top; y++) {
                FluidState f = lvl.getFluidState(m.set(x, y, z));
                if (!f.is(FluidTags.WATER)) continue;
                if (lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) > y + 1 && !lvl.getBlockState(m.set(x, y + 1, z)).isAir() && !lvl.getFluidState(m).is(FluidTags.WATER)) continue;
                m.set(x, y, z);
                if (f.isSource()) r[3]++; else r[2]++;
                if (lvl.getBlockState(m.set(x, y - 1, z)).isAir()) r[0]++;
                m.set(x, y, z);
                if (planned <= 62 || y > planned + 1) { if (!nearPlanned(s, x, y, z)) r[1]++; }
                if (c.tarn >= 0 && c.td < c.is.tarns.get(c.tarn)[2] && y < c.is.tarns.get(c.tarn)[3] - 9) r[4]++;
            }
        }
        // where water gets out: a still block next to running water on ground that isn't meant to be wet
        int shown = 0;
        IslandSampler.Column cn = new IslandSampler.Column();
        for (int x = sp.x - HALF; x < sp.x + HALF && shown < 8; x++) for (int z = sp.z - HALF; z < sp.z + HALF && shown < 8; z++) {
            int top = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            for (int y = 64; y <= top && shown < 8; y++) {
                if (!lvl.getFluidState(m.set(x, y, z)).isSource()) continue;
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    BlockPos nb = new BlockPos(x + d.getStepX(), y, z + d.getStepZ());
                    FluidState f = lvl.getFluidState(nb);
                    if (!f.is(FluidTags.WATER) || f.isSource()) continue;
                    s.fill(cn, nb.getX(), nb.getZ());
                    boolean channel = false;
                    for (int i = 0; i < s.streamCount(cn); i++) if (s.isStream(cn, i) && s.streamDist(cn, i) < s.streamWidth(cn, i) + 0.5) channel = true;
                    if (channel || s.waterTop(cn) >= y) continue;
                    s.fill(c, x, z);
                    int best = -1; double bd = 1e9;
                    for (int i = 0; i < s.streamCount(c); i++) if (c.rd[i] < bd) { bd = c.rd[i]; best = i; }
                    System.out.println(String.format("[probe]   leak %s %d,%d,%d -> %s: src col h %.1f hL %.1f rd %.2f rw %.2f rl %.1f tarn %d td %.1f pool %d/%.2f pond %d/%.2f; nb h %.1f",
                            sp.name, x, y, z, d, c.h, c.hL, best < 0 ? -1 : c.rd[best], best < 0 ? -1 : c.rw[best], best < 0 ? -1 : c.rl[best], c.tarn, c.td, c.pool, c.plq, c.pond, c.pq, cn.h));
                    shown++;
                    break;
                }
            }
        }
        return r;
    }

    private static boolean nearPlanned(IslandSampler s, int x, int y, int z) {
        IslandSampler.Column c = new IslandSampler.Column();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            s.fill(c, x + dx, z + dz);
            int top = s.waterTop(c);
            if (top > 62 && y <= top + 1) return true;
        }
        return false;
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
        if (sp.name.startsWith("s")) profile(lvl, sp, g, iw * 2 + 20, W * MS + 40, img.getHeight() - W * MS - 50);
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
        g.drawString("N up, x right", ox, oy - 4);
    }

    /** Side view along the stream's own line: the blocks under its centre, its water level in red. */
    private static void profile(ServerLevel lvl, Spot sp, Graphics2D g, int ox, int oy, int hgt) {
        IslandSampler s = ((HighlandsAccess) (Object) lvl.getChunkSource().randomState()).ancientpeaks$sampler();
        Island is = s.island();
        int si = Integer.parseInt(sp.name.substring(1, sp.name.indexOf('_')));
        Island.Path p = is.rivers.get(si);
        // the stretch of the path inside the window
        double a0 = -1, a1 = -1;
        for (int k = 0; k < p.fine.length; k++) {
            double[] q = Island.pointAlong(p, k);
            int[] w = is.toWorld(q[0], q[1]);
            if (Math.abs(w[0] - sp.x) < HALF && Math.abs(w[1] - sp.z) < HALF) { if (a0 < 0) a0 = k; a1 = k; }
        }
        if (a0 < 0) return;
        int n = (int) (a1 - a0) + 1, cw = Math.max(2, Math.min(8, 470 / Math.max(1, n)));
        double lo = 1e9, hi = -1e9;
        for (int k = (int) a0; k <= a1; k++) { lo = Math.min(lo, p.fine[k]); hi = Math.max(hi, p.fine[k]); }
        int ylo = (int) lo - 12, yhi = (int) hi + 12;
        int ch = Math.max(2, Math.min(8, hgt / Math.max(1, yhi - ylo)));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = (int) a0; k <= a1; k++) {
            double[] q = Island.pointAlong(p, k);
            int[] w = is.toWorld(q[0], q[1]);
            int px = ox + (int) (k - a0) * cw;
            for (int y = ylo; y <= yhi; y++) {
                BlockState st = lvl.getBlockState(m.set(w[0], y, w[1]));
                FluidState f = st.getFluidState();
                Color c = f.is(FluidTags.WATER) ? (f.isSource() ? new Color(0x2F5FD0) : new Color(0x7FB8FF))
                        : st.isAir() || hidden(st) ? new Color(0xDDE8F4) : new Color(st.getMapColor(lvl, m).col == 0 ? 0x808080 : st.getMapColor(lvl, m).col);
                g.setColor(c);
                g.fillRect(px, oy + (yhi - y) * ch, cw, ch);
            }
            g.setColor(Color.RED);
            g.fillRect(px, oy + (yhi - (int) Math.floor(p.fine[k])) * ch, cw, 1);
            int wy = (int) Math.floor(p.fine[k]);
            if (k > a1 - 25 && Boolean.getBoolean("ancientpeaks.probeEnd")) {
                StringBuilder sb = new StringBuilder("[probe]   end k" + k + " " + w[0] + "," + w[1] + ":");
                for (int y = wy - 2; y <= wy + 2; y++) { BlockState st = lvl.getBlockState(m.set(w[0], y, w[1])); sb.append(' ').append(y).append('=').append(st.getFluidState().isEmpty() ? st.getBlock().getName().getString() : (st.getFluidState().isSource() ? "SRC" : "flow" + st.getFluidState().getAmount())); }
                System.out.println(sb);
            }
            if (!lvl.getFluidState(m.set(w[0], wy, w[1])).is(FluidTags.WATER)) {
                IslandSampler.Column c = new IslandSampler.Column();
                s.fill(c, w[0], w[1]);
                System.out.println(String.format("[probe]   dry %s k%d at %d,%d,%d: %s dens %.2f h %.1f hL %.1f rd %.2f rw %.2f rl %.1f tarn %d pond %d/%.2f spring %d/%.2f pool %d/%.2f water? %s",
                        sp.name, k, w[0], wy, w[1], lvl.getBlockState(m).getBlock().getName().getString(), s.density(c, w[0], wy, w[1]), c.h, c.hL,
                        c.rd[si], c.rw[si], c.rl[si], c.tarn, c.pond, c.pq, c.spring, c.sq, c.pool, c.plq, s.isWater(c, w[0], wy, w[1])));
            }
        }
        g.setColor(Color.BLACK);
        g.drawString("along " + (int) a0 + ".." + (int) a1 + " of " + p.fine.length + ", y " + ylo + ".." + yhi, ox, oy - 3);
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
