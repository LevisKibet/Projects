package com.ronald.chromalight;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where the coloured lights are. Light-giving blocks are found by a scan round the player every half second (only
 * chunk sections whose palette holds a light-giving block are looked inside, so it's quick); moving lights - held
 * torches, burning or glowing creatures, test lights and anything other mods add - are gathered every frame.
 *
 * Other mods can add their own lights without depending on this one in any special way:
 *     Lights.addProvider(sink -> sink.light(x, y, z, 1f, 0.2f, 0.2f, 6f, 1f));   // red, 6 blocks, every frame
 */
public final class Lights {
    private Lights() {}

    /** One light: world position, colour (0..1), radius in blocks, intensity, and whether it tints vanilla light or adds its own. */
    public record Light(double x, double y, double z, float r, float g, float b, float radius, float intensity, boolean emit, boolean flame) {
        /** The vanilla-style light level (0..15) a moving light gives where it stands. */
        public int level() { return emit ? Math.min(15, Math.round(radius / 0.7f)) : 0; }
    }

    public interface Sink {
        /** An emitting light (adds light) at this spot for this frame. */
        void light(double x, double y, double z, float r, float g, float b, float radius, float intensity);
    }

    private static final List<Consumer<Sink>> PROVIDERS = new ArrayList<>();
    public static void addProvider(Consumer<Sink> p) { PROVIDERS.add(p); }

    static volatile List<Light> blockLights = List.of();
    static final List<Light> TEST = new ArrayList<>();
    private static int scanTick;

    // ------------------------------------------------------------------ light-giving blocks
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !Settings.enabled() || !Settings.blocks()) { blockLights = List.of(); return; }
        if (true) { blockLights = List.of(); return; }   // light-giving blocks are handled by VoxelLight now
        if (scanTick++ % 10 != 0) return;
        int rad = Settings.scanRadius();
        BlockPos c = mc.player.blockPosition();
        List<Light> out = new ArrayList<>();
        int cr = (rad >> 4) + 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = (c.getX() >> 4) - cr; cx <= (c.getX() >> 4) + cr; cx++)
            for (int cz = (c.getZ() >> 4) - cr; cz <= (c.getZ() >> 4) + cr; cz++) {
                LevelChunk ch = mc.level.getChunkSource().getChunk(cx, cz, false);
                if (ch == null) continue;
                LevelChunkSection[] sections = ch.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection s = sections[si];
                    if (s == null || s.hasOnlyAir()) continue;
                    int sy = SectionPos.sectionToBlockCoord(ch.getMinSection() + si);
                    if (sy + 16 < c.getY() - rad || sy > c.getY() + rad) continue;
                    if (!s.maybeHas(st -> st.getLightEmission() > 0 && LightColors.of(st) != null)) continue;   // palette check: fast
                    for (int i = 0; i < 4096; i++) {
                        int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                        BlockState st = s.getBlockState(x, y, z);
                        int lvl = st.getLightEmission();
                        if (lvl <= 0) continue;
                        LightColors.Tone t = LightColors.of(st);
                        if (t == null) continue;
                        int wx = (cx << 4) + x, wy = sy + y, wz = (cz << 4) + z;
                        if (Math.abs(wx - c.getX()) > rad || Math.abs(wy - c.getY()) > rad || Math.abs(wz - c.getZ()) > rad) continue;
                        out.add(new Light(wx + 0.5, wy + 0.5, wz + 0.5, t.r(), t.g(), t.b(), Math.max(3f, lvl * 0.85f), 1f, false, t.flame()));
                    }
                }
            }
        blockLights = out;
    }

    // ------------------------------------------------------------------ everything this frame
    static List<Light> gather(float partial) {
        List<Light> all = new ArrayList<>(blockLights);
        all.addAll(moving(partial));
        return all;
    }

    /** Only the lights that move (held, burning, glowing, test and provider lights). */
    static List<Light> moving(float partial) {
        Minecraft mc = Minecraft.getInstance();
        List<Light> all = new ArrayList<>(TEST);
        if (mc.level == null || mc.player == null) return all;
        if (Settings.dynamic()) {
            Vec3 me = mc.player.position();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.distanceToSqr(me) > 48 * 48) continue;
                double x = e.xo + (e.getX() - e.xo) * partial, y = e.yo + (e.getY() - e.yo) * partial, z = e.zo + (e.getZ() - e.zo) * partial;
                if (e.isOnFire()) all.add(new Light(x, y + e.getBbHeight() * 0.6, z, 1f, 0.5f, 0.18f, 8f, 1.2f, true, true));
                Object[] c = LightColors.creature(e);
                if (c != null) add(all, x, y + e.getBbHeight() * 0.5, z, c, 1f);
                if (e instanceof LivingEntity le) {
                    Object[] h = LightColors.held(le.getMainHandItem());
                    if (h == null) h = LightColors.held(le.getOffhandItem());
                    if (h != null) {
                        Vec3 look = le.getViewVector(partial);
                        add(all, x + look.x * 0.4, y + le.getEyeHeight() * 0.8, z + look.z * 0.4, h, 1f);
                    }
                } else if (e instanceof ItemEntity it) {
                    Object[] h = LightColors.held(it.getItem());
                    if (h != null) add(all, x, y + 0.3, z, h, 0.8f);
                }
            }
        }
        Sink sink = (x, y, z, r, g, b, radius, intensity) -> all.add(new Light(x, y, z, r, g, b, radius, intensity, true, false));
        for (Consumer<Sink> p : PROVIDERS) {
            try { p.accept(sink); } catch (RuntimeException ex) { ChromaLight.LOGGER.warn("Chroma Light: a light provider failed", ex); }
        }
        return all;
    }

    private static void add(List<Light> out, double x, double y, double z, Object[] toneLevel, float scale) {
        LightColors.Tone t = (LightColors.Tone) toneLevel[0];
        int lvl = (Integer) toneLevel[1];
        out.add(new Light(x, y, z, t.r(), t.g(), t.b(), Math.max(3f, lvl * 0.7f) * scale, 1.1f, true, t.flame()));
    }
}
