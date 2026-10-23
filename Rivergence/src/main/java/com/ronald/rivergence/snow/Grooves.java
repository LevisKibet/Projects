package com.ronald.rivergence.snow;

import com.ronald.rivergence.Rivergence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grooves ploughed through the snow by anything wading through it near you. Each snow block that has been walked
 * through keeps an 8×8 grid of how much of its depth has been pushed aside (a little negative at the groove's edge,
 * where it's heaped up); the smooth snow model reads it when the chunk is meshed. Kept on this computer only, like
 * the footprints of most games: nothing is sent or saved, and snowfall slowly fills them back in.
 */
@Mod.EventBusSubscriber(modid = Rivergence.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class Grooves {
    private Grooves() {}

    public static final int N = 16;
    private static final Map<Long, float[]> DENTS = new ConcurrentHashMap<>();
    /** The order blocks were last walked through, oldest first, so the oldest are forgotten first. */
    private static final LinkedHashMap<Long, Boolean> AGE = new LinkedHashMap<>(256, 0.75f, true);
    private static final Map<Entity, double[]> WALKERS = new WeakHashMap<>();
    private static final double RANGE = 40;

    public static boolean has(long key) { return !DENTS.isEmpty() && DENTS.containsKey(key); }

    /** How much of the snow's depth is pushed aside at lattice point (lx, lz) of block (x, y, z), lx, lz in 0..N. */
    public static float at(int x, int y, int z, int lx, int lz) {
        if (DENTS.isEmpty()) return 0;
        if (lx >= N) { x++; lx -= N; }
        if (lz >= N) { z++; lz -= N; }
        float[] a = DENTS.get(BlockPos.asLong(x, y, z));
        return a == null ? 0 : a[lz * N + lx];
    }

    /** Whether this block or one beside it has a groove (then it's meshed finely). */
    public static boolean detailed(BlockPos p) {
        if (DENTS.isEmpty()) return false;
        int x = p.getX(), y = p.getY(), z = p.getZ();
        return DENTS.containsKey(BlockPos.asLong(x, y, z)) || DENTS.containsKey(BlockPos.asLong(x + 1, y, z)) || DENTS.containsKey(BlockPos.asLong(x - 1, y, z))
                || DENTS.containsKey(BlockPos.asLong(x, y, z + 1)) || DENTS.containsKey(BlockPos.asLong(x, y, z - 1));
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload e) {
        if (e.getLevel().isClientSide()) { DENTS.clear(); synchronized (AGE) { AGE.clear(); } WALKERS.clear(); }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        if (!SnowConfig.enabled() || !SnowConfig.TRAILS.get()) { if (!DENTS.isEmpty()) { DENTS.clear(); mc.levelRenderer.allChanged(); } return; }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Set<Long> dirty = new HashSet<>();
        for (Entity en : level.entitiesForRendering()) {
            if (!(en instanceof LivingEntity le) || en.isSpectator() || en.getBbWidth() < 0.3f) continue;
            if (!(en instanceof Player) && !SnowConfig.MOB_TRAILS.get()) continue;
            if (en.distanceToSqr(cam) > RANGE * RANGE) continue;
            double[] w = WALKERS.get(en);
            if (w == null) { WALKERS.put(en, new double[]{en.getX(), en.getZ()}); continue; }
            double dx = en.getX() - w[0], dz = en.getZ() - w[1];
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > 4) { w[0] = en.getX(); w[1] = en.getZ(); continue; }      // teleported
            if (d < 0.18 || !le.onGround()) continue;
            w[0] = en.getX(); w[1] = en.getZ();
            BlockPos feet = en.blockPosition();
            BlockState s = level.getBlockState(feet);
            if (!s.is(Blocks.SNOW) || s.getValue(SnowLayerBlock.LAYERS) >= 8) continue;
            float scale = Mth.clamp(en.getBbWidth() / 0.6f, 0.6f, 2.2f);
            float deep = en instanceof Player || en.getBbWidth() > 1.2f ? 0.9f : 0.7f;
            stamp(level, en.getX(), en.getZ(), feet.getY(), 0.30f * scale, deep, dirty);
        }
        if (!dirty.isEmpty()) {
            for (long k : dirty) {
                int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
                mc.levelRenderer.setBlocksDirty(x - 1, y, z - 1, x + 1, y, z + 1);
            }
            forgetOld(mc);
        }
        if (level.isRaining() && (level.getGameTime() % 40) == 0) refill(level, mc);
    }

    /** Pushes the snow aside in a soft round dip of radius r at (x, z), heaped a little round its edge. */
    private static void stamp(ClientLevel level, double x, double z, int y, float r, float deep, Set<Long> dirty) {
        float rim = r * 1.6f;
        int gx0 = Mth.floor((x - rim) * N), gx1 = Mth.ceil((x + rim) * N);
        int gz0 = Mth.floor((z - rim) * N), gz1 = Mth.ceil((z + rim) * N);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        long lastKey = Long.MIN_VALUE; float[] arr = null; boolean ok = false;
        for (int gz = gz0; gz <= gz1; gz++) for (int gx = gx0; gx <= gx1; gx++) {
            double px = (double) gx / N - x, pz = (double) gz / N - z;
            float dist = (float) Math.sqrt(px * px + pz * pz);
            if (dist >= rim) continue;
            int bx = Math.floorDiv(gx, N), bz = Math.floorDiv(gz, N);
            long key = BlockPos.asLong(bx, y, bz);
            if (key != lastKey) {
                lastKey = key;
                BlockState s = level.getBlockState(m.set(bx, y, bz));
                ok = s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) < 8;
                arr = ok ? DENTS.computeIfAbsent(key, k -> new float[N * N]) : null;
                if (ok) { dirty.add(key); synchronized (AGE) { AGE.put(key, Boolean.TRUE); } }
            }
            if (!ok) continue;
            int i = Math.floorMod(gz, N) * N + Math.floorMod(gx, N);
            if (dist < r) {
                float t = dist / r;
                float v = deep * (float) Math.pow(1 - t * t, 0.6);
                if (v > arr[i]) arr[i] = v;
            } else if (arr[i] <= 0) {
                float t = (dist - r) / (rim - r);
                float v = -0.14f * Mth.sin(t * Mth.PI);                          // the pushed-up edge
                if (v < arr[i]) arr[i] = v;
            }
        }
    }

    private static void forgetOld(Minecraft mc) {
        int max = SnowConfig.MAX_TRAILS.get();
        synchronized (AGE) {
            Iterator<Long> it = AGE.keySet().iterator();
            while (AGE.size() > max && it.hasNext()) {
                long k = it.next();
                it.remove();
                DENTS.remove(k);
                int x = BlockPos.getX(k), y = BlockPos.getY(k), z = BlockPos.getZ(k);
                mc.levelRenderer.setBlocksDirty(x - 1, y, z - 1, x + 1, y, z + 1);
            }
        }
    }

    /** While it snows, the grooves near you slowly fill back in. */
    private static void refill(ClientLevel level, Minecraft mc) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Iterator<Map.Entry<Long, float[]>> it = DENTS.entrySet().iterator();
        int touched = 0;
        while (it.hasNext() && touched < 400) {
            Map.Entry<Long, float[]> en = it.next();
            long k = en.getKey();
            m.set(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
            if (!level.canSeeSky(m.above()) || !level.getBiome(m).value().coldEnoughToSnow(m)) continue;
            touched++;
            float[] a = en.getValue();
            boolean any = false;
            for (int i = 0; i < a.length; i++) {
                a[i] *= 0.9f;
                if (Math.abs(a[i]) < 0.02f) a[i] = 0; else any = true;
            }
            if (!any) { it.remove(); synchronized (AGE) { AGE.remove(k); } }
            mc.levelRenderer.setBlocksDirty(m.getX() - 1, m.getY(), m.getZ() - 1, m.getX() + 1, m.getY(), m.getZ() + 1);
        }
    }
}
