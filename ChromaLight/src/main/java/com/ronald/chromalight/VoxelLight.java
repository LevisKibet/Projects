package com.ronald.chromalight;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coloured light that spreads like real light. Round the player, every light-giving block floods its colour outwards
 * one block at a time, losing a level per block just like vanilla light, stopped by solid blocks and filtered by
 * stained glass. Red and blue lights that meet make purple. Each block also remembers how much sky light reaches it,
 * so daylight wins outdoors and the colour only shows where block light is the stronger one.
 *
 * The flood runs on its own thread. The result is read by the chunk builder (see {@link ColorLight}), which colours
 * every vertex of the world's blocks by the light falling on it - the same way vanilla brightens them - so the world is
 * really lit in colour rather than filtered. When the light changes, only the chunk sections whose light changed
 * are rebuilt.
 */
public final class VoxelLight {
    private VoxelLight() {}

    /** The volume: 5 x 3 x 5 chunk sections, centred on the player's section. */
    static final int SX = 80, SY = 48, SZ = 80;
    static final int N = SX * SY * SZ;
    static final int UNIT = 16;                                // sub-steps per light level, so colours fade smoothly

    /** One finished volume: coloured light (r | g << 8 | b << 16, each 0..15*UNIT) and sky light per block. */
    record Field(int ox, int oy, int oz, int[] light, byte[] sky, int[] skyColour, byte[] extra, int skyDarken) {
        /** Sky light level (0..15) per channel at index i: coloured where it came through stained glass. */
        int skyRgb(int i) {
            if (skyColour != null) return skyColour[i];
            int s = sky[i] * UNIT;
            return s | s << 8 | s << 16;
        }
        int index(int x, int y, int z) {
            x -= ox; y -= oy; z -= oz;
            if (x < 0 || y < 0 || z < 0 || x >= SX || y >= SY || z >= SZ) return -1;
            return (y * SZ + z) * SX + x;
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Chroma Light volume");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile boolean dirty = true;
    private static volatile Field ready;
    /** What the chunk builder reads. */
    static volatile Field current;
    private static Long2LongOpenHashMap sectionHashes = new Long2LongOpenHashMap();
    private static int sinceBuild;
    private static int[] builtCentre;
    private static int lastDarken = -1;
    private static ClientLevel lastLevel;

    public static void markDirty() { dirty = true; }

    // ------------------------------------------------------------------ main thread
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || !Settings.enabled() || !Settings.blocks()) {
            // leaving a world: just forget everything (the renderer may already be gone, so redraw nothing)
            current = null; ready = null; builtCentre = null; sectionHashes = new Long2LongOpenHashMap();
            return;
        }
        if (level != lastLevel) { lastLevel = level; current = null; builtCentre = null; ready = null; sectionHashes = new Long2LongOpenHashMap(); }
        if (LightColors.resolvePending()) dirty = true;
        int darken = level.dimensionType().hasSkyLight() ? level.getSkyDarken() : 0;
        if (darken != lastDarken) { lastDarken = darken; dirty = true; }

        Field done = ready;
        if (done != null) { ready = null; publish(done); }

        sinceBuild++;
        BlockPos p = mc.player.blockPosition();
        int scx = p.getX() >> 4, scy = p.getY() >> 4, scz = p.getZ() >> 4;
        boolean moved = builtCentre == null || builtCentre[0] != scx || builtCentre[1] != scy || builtCentre[2] != scz;
        if (!(moved || (dirty && sinceBuild >= 2) || sinceBuild >= 100)) return;
        if (!BUSY.compareAndSet(false, true)) return;
        dirty = false;
        sinceBuild = 0;
        builtCentre = new int[]{scx, scy, scz};
        int ox = (scx - 2) << 4, oy = (scy - 1) << 4, oz = (scz - 2) << 4;
        boolean glass = Settings.glass();
        int dk = darken;
        WORKER.execute(() -> {
            try { ready = build(level, ox, oy, oz, glass, dk); }
            catch (Throwable t) { ChromaLight.LOGGER.debug("Chroma Light: volume build skipped", t); dirty = true; }
            finally { BUSY.set(false); }
        });
    }

    /** Swap in a new volume and rebuild only the sections whose light changed. */
    private static void publish(Field f) {
        Long2LongOpenHashMap hashes = new Long2LongOpenHashMap();
        for (int sy = 0; sy < SY / 16; sy++) for (int sz = 0; sz < SZ / 16; sz++) for (int sx = 0; sx < SX / 16; sx++) {
            long h = 0;
            boolean any = false;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int i = ((sy * 16 + y) * SZ + sz * 16 + z) * SX + sx * 16 + x;
                int v = f.light[i];
                if (v != 0) { any = true; h = h * 31 + v * 7L + f.sky[i]; }
                if (f.skyColour != null) {
                    int sc = f.skyColour[i];
                    if ((sc & 255) != ((sc >> 8) & 255) || (sc & 255) != ((sc >> 16) & 255)) { any = true; h = h * 31 + sc; }
                }
            }
            if (any) hashes.put(SectionPos.asLong((f.ox >> 4) + sx, (f.oy >> 4) + sy, (f.oz >> 4) + sz), h * 31 + f.skyDarken);
        }
        current = f;
        redrawChanged(hashes);
    }

    private static void redrawChanged(Long2LongOpenHashMap hashes) {
        Minecraft mc = Minecraft.getInstance();
        LongOpenHashSet dirtySections = new LongOpenHashSet();
        for (var e : hashes.long2LongEntrySet()) {
            long k = e.getLongKey();
            if (!sectionHashes.containsKey(k) || sectionHashes.get(k) != e.getLongValue()) dirtySections.add(k);
        }
        for (long k : sectionHashes.keySet()) if (!hashes.containsKey(k)) dirtySections.add(k);
        sectionHashes = hashes;
        if (mc.levelRenderer == null || mc.level == null) return;
        LongOpenHashSet withNeighbours = new LongOpenHashSet();
        for (long k : dirtySections) {
            int x = SectionPos.x(k), y = SectionPos.y(k), z = SectionPos.z(k);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 1) withNeighbours.add(SectionPos.asLong(x + dx, y + dy, z + dz));
        }
        try {
            for (long k : withNeighbours) mc.levelRenderer.setSectionDirty(SectionPos.x(k), SectionPos.y(k), SectionPos.z(k));
        } catch (RuntimeException ex) {
            // another renderer (Embeddium...) isn't ready to rebuild right now; the next change will try again
        }
    }

    static void reset() {
        current = null; builtCentre = null; dirty = true;
        sectionHashes = new Long2LongOpenHashMap();
    }

    // ------------------------------------------------------------------ worker thread
    /** What the flood needs to know about a block state. */
    private record Info(int opacity, int filter, int emission, int colour, int extra) {}
    private static final Info AIR = new Info(0, 0xFFFFFF, 0, 0, 0);

    /** Extra light (beyond vanilla) at a block, for blocks we make glow (amethyst...). Read on any thread. */
    public static int extraAt(BlockPos pos) {
        Field f = current;
        if (f == null || f.extra == null) return 0;
        int i = f.index(pos.getX(), pos.getY(), pos.getZ());
        return i < 0 ? 0 : f.extra[i];
    }

    private static Field build(ClientLevel level, int ox, int oy, int oz, boolean glass, int darken) {
        byte[] opacity = new byte[N];
        int[] filter = null;
        int[] light = new int[N];
        byte[] sky = new byte[N];
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue(4096);
        IntArrayFIFOQueue extraQueue = new IntArrayFIFOQueue(64);
        byte[] extra = null;
        Map<BlockState, Info> infos = new IdentityHashMap<>();
        LayerLightEventListener skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        // 1. read the blocks and the sky light, and seed every light source
        for (int cx = ox >> 4; cx < (ox + SX) >> 4; cx++)
            for (int cz = oz >> 4; cz < (oz + SZ) >> 4; cz++) {
                LevelChunk ch = level.getChunkSource().getChunk(cx, cz, false);
                for (int sy = oy >> 4; sy < (oy + SY) >> 4; sy++) {
                    int bx = cx << 4, by = sy << 4, bz = cz << 4;
                    if (ch == null) {                                            // not loaded: solid, no light
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                            opacity[idx(bx + x - ox, by + y - oy, bz + z - oz)] = 15;
                        continue;
                    }
                    LevelChunkSection sec = null;
                    int si = ch.getSectionIndexFromSectionY(sy);
                    if (si >= 0 && si < ch.getSections().length) sec = ch.getSections()[si];
                    DataLayer skyData = skyLight.getDataLayerData(SectionPos.of(cx, sy, cz));
                    boolean empty = sec == null || sec.hasOnlyAir();
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        int i = idx(bx + x - ox, by + y - oy, bz + z - oz);
                        sky[i] = (byte) (skyData != null ? skyData.get(x, y, z) : skyLight.getLightValue(m.set(bx + x, by + y, bz + z)));
                        if (empty) continue;
                        BlockState st = sec.getBlockState(x, y, z);
                        if (st.isAir()) continue;
                        Info in = infos.get(st);
                        if (in == null) { in = info(st, glass); infos.put(st, in); }
                        opacity[i] = (byte) in.opacity;
                        if (in.filter != 0xFFFFFF) {
                            if (filter == null) { filter = new int[N]; java.util.Arrays.fill(filter, 0xFFFFFF); }
                            filter[i] = in.filter;
                        }
                        if (in.emission > 0) {
                            light[i] = max(light[i], scale(in.colour, in.emission * UNIT));
                            queue.enqueue(i);
                        }
                        if (in.extra > 0) {
                            if (extra == null) extra = new byte[N];
                            extra[i] = (byte) in.extra;
                            extraQueue.enqueue(i);
                        }
                    }
                }
            }

        // 2. flood: each step loses a level (plus whatever the block in the way absorbs), per colour channel
        flood(light, queue, opacity, filter, null);

        // 3. sky light through stained glass: only worth it when there's glass about. Sky light pours straight down
        //    without fading (as vanilla's does) and spreads sideways losing a level a block; glass colours it.
        int[] skyColour = null;
        if (filter != null) {
            skyColour = new int[N];
            int full = 15 * UNIT, white = full | full << 8 | full << 16;
            for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
                boolean open = true;
                for (int y = SY - 1; y >= 0; y--) {
                    int i = idx(x, y, z);
                    if (filter[i] != 0xFFFFFF || sky[i] < 15) open = false;
                    if (open) { skyColour[i] = white; queue.enqueue(i); }
                }
            }
            flood(skyColour, queue, opacity, filter, sky);
        }

        // 4. glow added to blocks vanilla leaves dark (amethyst...): a plain light level, for brightness
        if (extra != null) {
            while (!extraQueue.isEmpty()) {
                int i = extraQueue.dequeueInt();
                int v = extra[i] - 1;
                if (v <= 0) continue;
                int x = i % SX, z = (i / SX) % SZ, y = i / (SX * SZ);
                for (int dir = 0; dir < 6; dir++) {
                    int nx = x, ny = y, nz = z;
                    switch (dir) { case 0 -> nx--; case 1 -> nx++; case 2 -> ny--; case 3 -> ny++; case 4 -> nz--; default -> nz++; }
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= SX || ny >= SY || nz >= SZ) continue;
                    int j = idx(nx, ny, nz);
                    if (opacity[j] >= 15) continue;
                    int nv = v - opacity[j];
                    if (nv > extra[j]) { extra[j] = (byte) nv; extraQueue.enqueue(j); }
                }
            }
        }
        return new Field(ox, oy, oz, light, sky, skyColour, extra, darken);
    }

    /** Spread light through the volume. With sky set, light going down into full sky light doesn't fade. */
    private static void flood(int[] light, IntArrayFIFOQueue queue, byte[] opacity, int[] filter, byte[] sky) {
        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            int v = light[i];
            if (v == 0) continue;
            int x = i % SX, z = (i / SX) % SZ, y = i / (SX * SZ);
            for (int dir = 0; dir < 6; dir++) {
                int nx = x, ny = y, nz = z;
                switch (dir) { case 0 -> nx--; case 1 -> nx++; case 2 -> ny--; case 3 -> ny++; case 4 -> nz--; default -> nz++; }
                if (nx < 0 || ny < 0 || nz < 0 || nx >= SX || ny >= SY || nz >= SZ) continue;
                int j = idx(nx, ny, nz);
                int op = opacity[j];
                if (op >= 15) continue;
                int loss = sky != null && dir == 2 && sky[j] >= 15 ? 0 : UNIT * (1 + op);
                int r = (v & 255) - loss, g = ((v >> 8) & 255) - loss, b = ((v >> 16) & 255) - loss;
                if (r <= 0 && g <= 0 && b <= 0) continue;
                if (filter != null && filter[j] != 0xFFFFFF) {
                    int f = filter[j];
                    r = r * (f & 255) / 255; g = g * ((f >> 8) & 255) / 255; b = b * ((f >> 16) & 255) / 255;
                }
                int nv = Math.max(r, 0) | Math.max(g, 0) << 8 | Math.max(b, 0) << 16;
                int cur = light[j];
                int merged = max(cur, nv);
                if (merged != cur) { light[j] = merged; queue.enqueue(j); }
            }
        }
    }

    private static Info info(BlockState st, boolean glass) {
        int op = Math.max(0, Math.min(15, st.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        int filter = glass && op < 15 ? LightColors.filter(st) : 0xFFFFFF;
        int extra = LightColors.extraGlow(st);
        int em = Math.max(st.getLightEmission(), extra);
        if (extra <= st.getLightEmission()) extra = 0;
        int colour = 0;
        if (em > 0) {
            LightColors.Tone t = LightColors.of(st);
            colour = (int) (t.r() * 255) | (int) (t.g() * 255) << 8 | (int) (t.b() * 255) << 16;
        }
        if (op == 0 && filter == 0xFFFFFF && em == 0) return AIR;
        return new Info(op, filter, em, colour, extra);
    }

    private static int idx(int x, int y, int z) { return (y * SZ + z) * SX + x; }

    private static int scale(int colour, int level) {
        int r = (colour & 255) * level / 255, g = ((colour >> 8) & 255) * level / 255, b = ((colour >> 16) & 255) * level / 255;
        return r | g << 8 | b << 16;
    }

    static int max(int a, int b) {
        return Math.max(a & 255, b & 255) | Math.max((a >> 8) & 255, (b >> 8) & 255) << 8 | Math.max((a >> 16) & 255, (b >> 16) & 255) << 16;
    }
}
