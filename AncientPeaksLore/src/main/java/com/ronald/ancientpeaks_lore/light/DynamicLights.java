package com.ronald.ancientpeaks_lore.light;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Real light for moving things. Vanilla light can't move, so - like LambDynamicLights - the game's own light lookups
 * (LevelRenderer.getLightColor for blocks, EntityRenderer.getBlockLightLevel for mobs) are raised wherever a moving
 * light is near, and the chunk sections round it are rebuilt when it moves. That lights the world properly, textures
 * and all, even in pitch darkness; the colour pass (light.fsh) then tints that light.
 *
 * Kept cheap: sources are gathered each tick into one small array that the chunk-building threads only read; a
 * light that hasn't moved a block (or changed) rebuilds nothing; rebuilds happen at most every few ticks.
 */
public final class DynamicLights {
    private DynamicLights() {}

    /** x, y, z, level, r, g, b - for every moving light, read by the chunk builder threads. */
    static final int S = 7;
    private static volatile float[] SOURCES = new float[0];
    private static float[] lastBuilt = new float[0];
    private static int tick;

    /** The block light a moving light gives at this block (0 if none near). */
    public static int levelAt(BlockPos pos) {
        float[] s = SOURCES;
        if (s.length == 0 || !Settings.realLight()) return 0;
        float px = pos.getX() + 0.5f, py = pos.getY() + 0.5f, pz = pos.getZ() + 0.5f;
        int best = 0;
        for (int i = 0; i < s.length; i += S) {
            float lvl = s[i + 3];
            float dx = px - s[i], dy = py - s[i + 1], dz = pz - s[i + 2];
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= lvl * lvl) continue;
            int v = (int) (lvl - Mth.sqrt(d2));
            if (v > best) best = v;
        }
        return Math.min(15, best);
    }

    /** Raise a packed light value (sky << 20 | block << 4) to at least the moving light here. */
    public static int raise(int packed, BlockPos pos) {
        int dyn = Math.max(levelAt(pos), VoxelLight.extraAt(pos));
        if (dyn == 0) return packed;
        int block = (packed >> 4) & 0xF;
        if (dyn <= block) return packed;
        return (packed & ~(0xF << 4)) | (dyn << 4);
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Settings.enabled() || !Settings.dynamic()) {
            if (SOURCES.length > 0) { float[] old = SOURCES; SOURCES = new float[0]; if (mc.level != null) rebuild(old); lastBuilt = new float[0]; }
            return;
        }
        List<Lights.Light> lights = Lights.moving(1f);
        float[] now = new float[lights.size() * S];
        int k = 0;
        for (Lights.Light l : lights) {
            if (l.level() <= 0) continue;
            now[k++] = (float) l.x(); now[k++] = (float) l.y(); now[k++] = (float) l.z(); now[k++] = l.level(); now[k++] = l.r(); now[k++] = l.g(); now[k++] = l.b();
        }
        if (k < now.length) now = java.util.Arrays.copyOf(now, k);
        SOURCES = now;
        if (tick++ % Math.max(1, Settings.updateTicks()) != 0) return;
        if (!moved(lastBuilt, now)) return;
        rebuild(lastBuilt);
        rebuild(now);
        lastBuilt = now;
    }

    /** Has any light moved a block or more, appeared, gone, or changed strength? */
    private static boolean moved(float[] a, float[] b) {
        if (a.length != b.length) return true;
        for (int i = 0; i < a.length; i += S) {
            if (Math.abs(a[i] - b[i]) >= 0.75f || Math.abs(a[i + 1] - b[i + 1]) >= 0.75f || Math.abs(a[i + 2] - b[i + 2]) >= 0.75f
                    || a[i + 3] != b[i + 3] || a[i + 4] != b[i + 4] || a[i + 5] != b[i + 5] || a[i + 6] != b[i + 6]) return true;
        }
        return false;
    }

    /** Rebuild the chunk sections each light reaches into. */
    private static void rebuild(float[] s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null || mc.level == null) return;
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (int i = 0; i < s.length; i += S) {
            int r = (int) Math.ceil(s[i + 3]);
            int x0 = Mth.floor(s[i] - r) >> 4, x1 = Mth.floor(s[i] + r) >> 4;
            int y0 = Mth.floor(s[i + 1] - r) >> 4, y1 = Mth.floor(s[i + 1] + r) >> 4;
            int z0 = Mth.floor(s[i + 2] - r) >> 4, z1 = Mth.floor(s[i + 2] + r) >> 4;
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++)
                if (done.add(BlockPos.asLong(x, y, z))) {
                    try { mc.levelRenderer.setSectionDirty(x, y, z); } catch (RuntimeException ex) { return; }
                }
        }
    }

    static boolean active() { return SOURCES.length > 0; }
    static float[] sources() { return SOURCES; }
    static List<float[]> snapshot() { List<float[]> l = new ArrayList<>(); float[] s = SOURCES; for (int i = 0; i < s.length; i += S) l.add(new float[]{s[i], s[i + 1], s[i + 2], s[i + 3]}); return l; }
}
