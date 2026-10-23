package com.ronald.rivergence.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ronald.rivergence.Rivergence;
import com.ronald.rivergence.RivergenceConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * More life in the sea, drawn only on this screen like the schools of fish: great glowing blooms of jellyfish over
 * coral reefs, lighting the reef in their colours; and, now and then out over deep water, a few big tuna tearing past.
 */
@Mod.EventBusSubscriber(modid = Rivergence.MODID, value = Dist.CLIENT)
public final class SeaLife {
    private SeaLife() {}

    private static final ResourceLocation TEX = new ResourceLocation(Rivergence.MODID, "textures/misc/fish.png");
    private static final Random RND = new Random();
    /** For testing: sightings on demand. */
    static boolean forceTuna;

    // ------------------------------------------------------------------ jellyfish

    static final class Jelly {
        float x, y, z, px, py, pz, vx, vy, vz;
        float size, phase, rate;
        int r, g, b, cr, cg, cb;
        int strands;
        /** Where it hangs (its place in its layer) and the floor under it. */
        float hx, hy, hz, floor;
    }

    static final class Bloom {
        double cx, cz;
        int floorY, topY;
        float alpha, palpha, radius;
        boolean leaving;
        Jelly[] jellies;
        Integer[] order;
        float[] dist;
        int nextSound;
        /** The coloured lights it gives (a few, from groups of jellies near you), refreshed now and then. */
        volatile float[][] lights = new float[0][];
    }

    /** Debugging: the deep-sea sightings almost every time (set false for release). */
    private static final boolean SIGHTINGS_OFTEN = false;
    private static long bloomRest;

    private static final List<Bloom> blooms = new ArrayList<>();

    // ------------------------------------------------------------------ the deep: tuna

    static final class Tuna {
        float x, y, z, px, py, pz, dx, dy, dz, speed, yaw, turn, beat, pyaw;
        int age, life;
        float alpha, palpha;
    }

    private static final List<Tuna> tunas = new ArrayList<>();
    private static long tunaRest;
    private static ClientLevel lastLevel;
    private static int clock;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player p = mc.player;
        if (level != lastLevel) { blooms.clear(); tunas.clear(); lastLevel = level; }
        if (level == null || p == null || mc.isPaused()) return;
        if (!RivergenceConfig.schools()) { blooms.clear(); tunas.clear(); return; }
        clock++;
        if (clock % 40 == 7 && clock > bloomRest) tryBloom(level, p);
        for (int i = blooms.size() - 1; i >= 0; i--) {
            Bloom b = blooms.get(i);
            updateBloom(level, p, b);
            if (b.leaving && b.alpha <= 0) { blooms.remove(i); bloomRest = clock + 20 * 60; }
        }
        ensureLights();
        boolean deepSea = clock % 20 == 0 && deepSea(level, p);
        // a few tuna tearing past: a one-in-ten chance every half minute out there, then a few minutes' rest
        if (tunas.isEmpty() && (forceTuna || deepSea && clock > tunaRest && (SIGHTINGS_OFTEN ? clock % 100 == 0 && RND.nextInt(10) != 0 : clock % 600 == 0 && RND.nextInt(10) == 0))) {
            forceTuna = false;
            spawnTuna(p);
        }
        if (!tunas.isEmpty()) {
            tunas.removeIf(SeaLife::updateTuna);
            if (tunas.isEmpty()) tunaRest = clock + (SIGHTINGS_OFTEN ? 20 * 15 : 20 * 60 * 4);
        }
    }

    /** Swimming in any ocean with deep water under you. */
    private static boolean deepSea(ClientLevel level, Player p) {
        if (!p.isEyeInFluid(FluidTags.WATER)) return false;
        BlockPos at = p.blockPosition();
        String id = level.getBiome(at).unwrapKey().map(k -> k.location().getPath()).orElse("");
        if (!id.contains("ocean")) return false;                            // any ocean, as long as it's deep under you
        int d = 0;
        BlockPos.MutableBlockPos m = at.mutable();
        while (d < 40 && level.getFluidState(m.move(0, -1, 0)).is(FluidTags.WATER)) d++;
        return d >= 10 || id.contains("deep");
    }

    // ---- jellyfish over reefs

    /** A great glowing bloom over a coral reef: one at a time, a little way off, so you come upon it. */
    private static void tryBloom(ClientLevel level, Player p) {
        if (!blooms.isEmpty()) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < 3; attempt++) {
            double a = RND.nextDouble() * Math.PI * 2, d = 26 + RND.nextDouble() * 22;
            int x = Mth.floor(p.getX() + Math.cos(a) * d), z = Mth.floor(p.getZ() + Math.sin(a) * d);
            int top = surfaceAt(level, x, z, Mth.floor(p.getY()));
            if (top == Integer.MIN_VALUE) continue;
            int floor = floorAt(level, x, top, z);
            if (top - floor < 6) continue;
            // a reef: coral on the bed round about
            int coral = 0;
            for (int dx = -6; dx <= 6; dx += 3) for (int dz = -6; dz <= 6; dz += 3) {
                int fl = floorAt(level, x + dx, top, z + dz);
                for (int dy = 0; dy <= 2; dy++) {
                    var st = level.getBlockState(m.set(x + dx, fl + dy, z + dz));
                    if (st.is(BlockTags.CORALS) || st.is(BlockTags.CORAL_BLOCKS) || st.is(BlockTags.WALL_CORALS)) { coral++; break; }
                }
            }
            if (coral < 4) continue;
            blooms.add(makeBloom(level, x, z, floor, top));
            return;
        }
    }

    private static int surfaceAt(ClientLevel level, int x, int z, int near) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = near + 20; y >= near - 30; y--)
            if (level.getFluidState(m.set(x, y, z)).is(FluidTags.WATER)) return level.getFluidState(m.set(x, y + 1, z)).isEmpty() ? y : Integer.MIN_VALUE;
        return Integer.MIN_VALUE;
    }

    /** The lowest water block under the surface here (the bed is just below it). */
    private static int floorAt(ClientLevel level, int x, int top, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int f = top;
        while (f > top - 40 && level.getFluidState(m.set(x, f - 1, z)).is(FluidTags.WATER)) f--;
        return f;
    }

    /** Jellyfish colours as they come in the sea: amber, peach, pink, pale moon-jelly blue and white, violet. */
    private static final int[][] JELLY = {
            {235, 135, 60}, {245, 170, 110}, {235, 125, 200}, {245, 160, 215}, {150, 205, 245}, {175, 220, 250},
            {215, 225, 245}, {170, 140, 235}, {245, 205, 120}};

    /** Layers of the bloom, top to bottom, and the colours each runs to: pale moon jellies up in the light, pinks and violets in the middle, amber glowing low over the coral. */
    private static final int[][][] LAYER = {
            {{150, 205, 245}, {175, 220, 250}, {215, 225, 245}, {170, 140, 235}},
            {{235, 125, 200}, {245, 160, 215}, {170, 140, 235}, {150, 205, 245}},
            {{235, 135, 60}, {245, 170, 110}, {245, 205, 120}, {235, 125, 200}}};

    static Bloom makeBloom(ClientLevel level, int x, int z, int floor, int top) {
        Bloom b = new Bloom();
        b.cx = x + 0.5; b.cz = z + 0.5; b.floorY = floor; b.topY = top;
        b.radius = 17 + RND.nextFloat() * 6;                                // 34 to 46 blocks across
        int layers = top - floor >= 10 ? 3 : 2;
        int n = 150 + RND.nextInt(70);
        List<Jelly> out = new ArrayList<>();
        long seed = RND.nextLong();
        for (int i = 0; i < n * 3 && out.size() < n; i++) {
            double a = RND.nextDouble() * Math.PI * 2, r = Math.sqrt(RND.nextDouble()) * b.radius;
            float jx = (float) (b.cx + Math.cos(a) * r), jz = (float) (b.cz + Math.sin(a) * r);
            // ragged, patchy edges: thicker in drifts, thinning to nothing
            double edge = r / b.radius + 0.35 * Planner_noise(jx / 9.0, jz / 9.0, seed);
            if (edge > 1 || RND.nextDouble() < edge * edge * 0.6) continue;
            float fl = level == null ? floor : floorAt(level, Mth.floor(jx), top, Mth.floor(jz));
            if (top - fl < 4) continue;
            int layer = layers == 3 ? RND.nextInt(3) : RND.nextInt(2) * 2;
            float ly = layer == 0 ? top - 1.3f : layer == 1 ? (top + fl) / 2f + 0.5f : fl + 2.6f;
            ly += (RND.nextFloat() - 0.5f) * (layer == 1 ? 2.5f : 1.4f);
            ly = Mth.clamp(ly, fl + 1.6f, top - 0.8f);
            Jelly j = new Jelly();
            j.x = j.px = j.hx = jx; j.z = j.pz = j.hz = jz; j.y = j.py = j.hy = ly; j.floor = fl;
            float big = RND.nextFloat();
            j.size = (layer == 2 ? 0.24f : 0.18f) + big * big * 0.3f;
            j.phase = RND.nextFloat() * 6.283f;
            j.rate = 0.08f + RND.nextFloat() * 0.05f;
            j.strands = 4;
            int[][] pal = LAYER[layer];
            int[] c = pal[RND.nextFloat() < 0.6f ? 0 : RND.nextInt(pal.length)];
            if (RND.nextFloat() < 0.1f) c = LAYER[RND.nextInt(3)][RND.nextInt(4)];
            float v = 0.9f + RND.nextFloat() * 0.15f;
            j.r = Math.min(255, (int) (c[0] * v)); j.g = Math.min(255, (int) (c[1] * v)); j.b = Math.min(255, (int) (c[2] * v));
            j.cr = Math.min(255, j.r + 40); j.cg = Math.min(255, j.g + 50); j.cb = Math.min(255, j.b + 40);
            out.add(j);
        }
        b.jellies = out.toArray(new Jelly[0]);
        return b;
    }

    private static double Planner_noise(double x, double z, long seed) { return com.ronald.rivergence.plan.Planner.valueNoise(x, z, seed); }

    private static void updateBloom(ClientLevel level, Player p, Bloom b) {
        b.palpha = b.alpha;
        double pd = Math.hypot(b.cx - p.getX(), b.cz - p.getZ());
        if (pd > 120) b.leaving = true;
        b.alpha = Mth.clamp(b.alpha + (b.leaving ? -0.04f : 0.012f), 0, 1);
        // the whole bloom borne slowly along by the current
        float cur = (float) Math.sin(clock * 0.0007) * 0.004f, cur2 = (float) Math.cos(clock * 0.0005) * 0.004f;
        for (Jelly j : b.jellies) {
            j.px = j.x; j.py = j.y; j.pz = j.z;
            j.phase += j.rate;
            j.hx += cur; j.hz += cur2;
            // each beat lifts it a little, between beats it sinks; it keeps loosely to its own place in its layer
            float beat = Math.max(0, Mth.sin(j.phase));
            j.vy = j.vy * 0.9f + beat * 0.006f - 0.0022f + (j.hy - j.y) * 0.002f;
            j.vx = j.vx * 0.95f + (RND.nextFloat() - 0.5f) * 0.0015f + (j.hx - j.x) * 0.0008f;
            j.vz = j.vz * 0.95f + (RND.nextFloat() - 0.5f) * 0.0015f + (j.hz - j.z) * 0.0008f;
            // drifting aside from a swimmer
            double qx = j.x - p.getX(), qy = j.y - p.getEyeY(), qz = j.z - p.getZ(), qd = Math.sqrt(qx * qx + qy * qy + qz * qz);
            if (qd < 2.5 && qd > 1e-3) { j.vx += qx / qd * 0.004f; j.vy += qy / qd * 0.003f; j.vz += qz / qd * 0.004f; }
            j.x += j.vx; j.z += j.vz;
            j.y = Mth.clamp(j.y + j.vy, j.floor + 1.2f, b.topY - 0.25f - j.size * 0.6f);
        }
        if (b.alpha < 0.3f) { b.lights = new float[0][]; return; }
        // their lights: the bloom gathered into patches six blocks across, the patches nearest you lit
        if (clock % 5 == 0) {
            java.util.Map<Long, float[]> cells = new java.util.HashMap<>();
            for (Jelly j : b.jellies) {
                long k = ((long) Mth.floor(j.x / 6) << 32) ^ (Mth.floor(j.y / 6) & 0xFFFFL) << 16 ^ (Mth.floor(j.z / 6) & 0xFFFFL);
                float[] c = cells.computeIfAbsent(k, kk -> new float[7]);
                c[0] += j.x; c[1] += j.y; c[2] += j.z; c[3] += j.cr; c[4] += j.cg; c[5] += j.cb; c[6]++;
            }
            List<float[]> ls = new ArrayList<>();
            for (float[] c : cells.values()) {
                if (c[6] < 2) continue;
                ls.add(new float[]{c[0] / c[6], c[1] / c[6], c[2] / c[6], c[3] / c[6] / 255f, c[4] / c[6] / 255f, c[5] / c[6] / 255f, Math.min(1f, c[6] / 8f)});
            }
            double ex = p.getX(), ey = p.getEyeY(), ez = p.getZ();
            ls.sort((u, v) -> Double.compare(sq(u, ex, ey, ez), sq(v, ex, ey, ez)));
            b.lights = ls.subList(0, Math.min(14, ls.size())).toArray(new float[0][]);
        }
        // their sound: a soft wet squelch and the glow squid's murmur, now and then from one near you
        if (pd < b.radius + 14 && --b.nextSound <= 0) {
            b.nextSound = 20 + RND.nextInt(40);
            Jelly j = b.jellies[RND.nextInt(b.jellies.length)];
            if ((j.x - p.getX()) * (j.x - p.getX()) + (j.z - p.getZ()) * (j.z - p.getZ()) < 18 * 18) {
                float vol = b.alpha;
                if (RND.nextBoolean())
                    level.playLocalSound(j.x, j.y, j.z, net.minecraft.sounds.SoundEvents.GLOW_SQUID_AMBIENT, net.minecraft.sounds.SoundSource.AMBIENT, 0.35f * vol, 1.35f + RND.nextFloat() * 0.4f, false);
                else
                    level.playLocalSound(j.x, j.y, j.z, net.minecraft.sounds.SoundEvents.GLOW_SQUID_SQUIRT, net.minecraft.sounds.SoundSource.AMBIENT, 0.15f * vol, 1.6f + RND.nextFloat() * 0.3f, false);
                level.playLocalSound(j.x, j.y, j.z, net.minecraft.sounds.SoundEvents.SLIME_SQUISH_SMALL, net.minecraft.sounds.SoundSource.AMBIENT, 0.22f * vol, 0.55f + RND.nextFloat() * 0.25f, false);
            }
        }
    }

    private static double sq(float[] c, double x, double y, double z) { double a = c[0] - x, b = c[1] - y, d = c[2] - z; return a * a + b * b + d * d; }

    // ---- coloured light (from Chronicles of Logres, or Chroma, when one is installed)

    private static boolean lightsTried;

    /** Hands the bloom's lights to the coloured-light mod each frame, through its public provider hook (no hard dependency). */
    private static void ensureLights() {
        if (lightsTried) return;
        lightsTried = true;
        for (String pkg : new String[]{"com.ronald.ancientpeaks_lore.light", "com.ronald.chroma.light"}) {
            try {
                Class<?> lights = Class.forName(pkg + ".Lights"), sink = Class.forName(pkg + ".Lights$Sink");
                java.lang.reflect.Method put = sink.getMethod("light", double.class, double.class, double.class, float.class, float.class, float.class, float.class, float.class);
                java.util.function.Consumer<Object> provider = out -> {
                    for (Bloom b : blooms) {
                        float a = b.alpha;
                        for (float[] l : b.lights) {
                            try { put.invoke(out, (double) l[0], (double) l[1], (double) l[2], l[3], l[4], l[5], 7f, 0.9f * a * (0.5f + 0.5f * l[6])); }
                            catch (Exception ex) { return; }
                        }
                    }
                };
                lights.getMethod("addProvider", java.util.function.Consumer.class).invoke(null, provider);
                Rivergence.LOGGER.info("Rivergence: jellyfish light up through {}", pkg);
                return;                                                      // one is enough (Chronicles stands Chroma aside)
            } catch (ClassNotFoundException ignored) {
            } catch (Exception ex) { Rivergence.LOGGER.warn("Rivergence: couldn't hook coloured light in {}", pkg, ex); }
        }
    }

    // ---- the tuna

    /** One to three of them together, out of the murk on one side, sweeping past a few blocks ahead, gone into the other. */
    private static void spawnTuna(Player p) {
        Vec3 look = p.getLookAngle();
        double lx = look.x, lz = look.z, ll = Math.sqrt(lx * lx + lz * lz);
        if (ll < 1e-3) { lx = 1; lz = 0; ll = 1; }
        lx /= ll; lz /= ll;
        double sx = -lz, sz = lx, dir = RND.nextBoolean() ? 1 : -1;
        double ahead = 5 + RND.nextDouble() * 4;
        float turn = (RND.nextFloat() - 0.5f) * 0.012f;
        float speed = 1.1f + RND.nextFloat() * 0.35f;
        int n = 1 + RND.nextInt(3);
        for (int i = 0; i < n; i++) {
            Tuna t = new Tuna();
            double back = i * (2.5 + RND.nextDouble() * 2), off = i == 0 ? 0 : (RND.nextDouble() - 0.5) * 3;
            t.x = t.px = (float) (p.getX() + lx * (ahead + off) - sx * dir * (34 + back));
            t.z = t.pz = (float) (p.getZ() + lz * (ahead + off) - sz * dir * (34 + back));
            t.y = t.py = (float) (p.getEyeY() - 0.8 + RND.nextDouble() * 1.6 + i * 0.4);
            t.yaw = t.pyaw = (float) Math.atan2(sz * dir, sx * dir) - turn * 30;  // aimed so the curve brings it across in front of you
            t.turn = turn;
            t.speed = speed * (0.95f + RND.nextFloat() * 0.1f);
            t.beat = RND.nextFloat() * 6;
            t.life = (int) ((68 + back) / t.speed) + 6;
            tunas.add(t);
        }
    }

    private static boolean updateTuna(Tuna t) {
        t.age++;
        t.px = t.x; t.py = t.y; t.pz = t.z; t.palpha = t.alpha; t.pyaw = t.yaw;
        // a long sweeping curve, the body rolling from side to side with every fast beat of the tail
        t.yaw += t.turn;
        t.dx = Mth.cos(t.yaw); t.dz = Mth.sin(t.yaw);
        float surge = 1 + 0.08f * Mth.sin(t.age * 0.9f + t.beat);
        t.x += t.dx * t.speed * surge; t.z += t.dz * t.speed * surge;
        t.y += Mth.sin(t.age * 0.25f + t.beat) * 0.02f;
        t.alpha = t.age < 8 ? t.age / 8f : t.age > t.life - 8 ? Math.max(0, (t.life - t.age) / 8f) : 1;
        return t.age >= t.life;
    }

    // ------------------------------------------------------------------ drawing

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) return;
        if (blooms.isEmpty() && tunas.isEmpty()) return;
        long t0 = System.nanoTime();
        try { draw(e); } finally { devNanos += System.nanoTime() - t0; devFrames++; }
    }

    public static long devNanos, devFrames, devDraw;
    /** Which way the camera looks this frame (for leaving out what's behind it). */
    private static float LX, LY, LZ;

    private static void draw(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        float pt = e.getPartialTick();
        Vec3 cam = e.getCamera().getPosition();
        var look = e.getCamera().getLookVector();
        LX = look.x(); LY = look.y(); LZ = look.z();
        // the camera's turn goes into the shader's model-view once, not into every vertex
        var mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        mv.mulPoseMatrix(e.getPoseStack().last().pose());
        RenderSystem.applyModelViewMatrix();
        Matrix4f mat = null;
        try { drawAll(mc, level, cam, mat, pt); }
        finally { mv.popPose(); RenderSystem.applyModelViewMatrix(); }
    }

    private static void drawAll(Minecraft mc, ClientLevel level, Vec3 cam, Matrix4f mat, float pt) {
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, TEX);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();

        // solid bodies first (they hide what's behind them)
        if (!tunas.isEmpty()) {
            RenderSystem.depthMask(true);
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            for (Tuna t : tunas) drawTuna(bb, mat, cam, pt, level, t);
            BufferUploader.drawWithShader(bb.end());
        }
        // the jellyfish: see-through and glowing. Drawn furthest first, and they do mark the depth buffer, so the water
        // drawn after them (the far surface on the horizon) can't paint over the ones in front of it
        if (!blooms.isEmpty()) {
            RenderSystem.depthMask(true);
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            int n = 0;
            for (Bloom b : blooms) {
                float a = Mth.lerp(pt, b.palpha, b.alpha);
                if (a <= 0.01f) continue;
                Jelly[] js = b.jellies;
                if (b.order == null || b.order.length != js.length) { b.order = new Integer[js.length]; b.dist = new float[js.length]; for (int i = 0; i < js.length; i++) b.order[i] = i; }
                for (int i = 0; i < js.length; i++) { double qx = js[i].x - cam.x, qy = js[i].y - cam.y, qz = js[i].z - cam.z; b.dist[i] = (float) (qx * qx + qy * qy + qz * qz); }
                float[] dd = b.dist;
                java.util.Arrays.sort(b.order, (u, v) -> Float.compare(dd[v], dd[u]));
                for (Integer i : b.order) { drawJelly(bb, mat, cam, pt, js[i], a); n++; }
            }
            long tb = System.nanoTime();
            if (n > 0) BufferUploader.drawWithShader(bb.end()); else bb.end().release();
            devDraw += System.nanoTime() - tb;
            RenderSystem.depthMask(true);
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }

    private static final float SU0 = 0.02f, SU1 = 0.1f, SV0 = 0.52f, SV1 = 0.98f;   // the plain white patch of the texture
    private static final int GLOW = 0xF000F0;

    private static void vr(BufferBuilder bb, Matrix4f ignored, float x, float y, float z, float u, float v, int r, int g, int b, int a, int light) {
        bb.vertex(x, y, z).uv(u, v).color(r, g, b, a).uv2(light).endVertex();
    }

    private static void quad(BufferBuilder bb, Matrix4f m, float[] a, float[] b, float[] c, float[] d, int r, int g, int bl, int al, int light) {
        vr(bb, m, a[0], a[1], a[2], SU0, SV0, r, g, bl, al, light);
        vr(bb, m, b[0], b[1], b[2], SU1, SV0, r, g, bl, al, light);
        vr(bb, m, c[0], c[1], c[2], SU1, SV1, r, g, bl, al, light);
        vr(bb, m, d[0], d[1], d[2], SU0, SV1, r, g, bl, al, light);
    }

    /** How far into the murk a point is: 0 near you, 1 at the end of what you can see (they melt into it, never stand out of it). */
    private static float murk(float d2) {
        float start = RenderSystem.getShaderFogStart(), end = RenderSystem.getShaderFogEnd();
        if (end <= start) return 0;
        float d = Mth.sqrt(d2);
        return Mth.clamp((d - start) / (end - start), 0, 1);
    }

    /**
     * A jellyfish from the model (jellyfish.obj, made at 0.4 blocks): its bell squeezes in and stretches tall each beat,
     * the threads and arms hang from the bell's edge, trailing and swaying behind.
     */
    private static void drawJelly(BufferBuilder bb, Matrix4f m, Vec3 cam, float pt, Jelly j, float fade) {
        float x = (float) (Mth.lerp(pt, j.px, j.x) - cam.x), y = (float) (Mth.lerp(pt, j.py, j.y) - cam.y), z = (float) (Mth.lerp(pt, j.pz, j.z) - cam.z);
        float d2 = x * x + y * y + z * z;
        if (d2 > 72 * 72) return;
        // behind you: not drawn
        if (d2 > 9 && x * LX + y * LY + z * LZ < -0.35f * Mth.sqrt(d2)) return;
        fade *= 1 - murk(d2);
        if (fade < 0.02f) return;
        SeaModel model = SeaModel.get("jellyfish");
        float S = j.size / 0.4f;
        float ph = j.phase + j.rate * pt, beat = Mth.sin(ph);
        float wide = 1 - 0.18f * beat, tall = 1 + 0.28f * beat;
        float[] bellY = model.partY.get("bell_sides");
        float bottom = bellY == null ? -0.15f : bellY[0];
        float[] vx = new float[4], vy = new float[4], vz = new float[4];
        for (SeaModel.Face f : model.faces) {
            boolean hang = f.hang;
            float len = hang ? f.len : 1;
            int idx = f.idx;
            for (int c = 0; c < 4; c++) {
                float mx = f.x[c], my = f.y[c], mz = f.z[c];
                if (hang) {
                    float t = Mth.clamp((bottom - my) / len, 0, 1);
                    float sway = Mth.sin(ph * 0.9f - t * 3f + idx) * 0.04f * t;
                    mx = mx * wide + sway; mz = mz * wide + sway * 0.6f;
                    my = bottom * tall - (bottom - my) * (1 + 0.12f * beat);
                } else { mx *= wide; mz *= wide; my *= tall; }
                vx[c] = x + mx * S; vy[c] = y + my * S; vz[c] = z + mz * S;
            }
            int r, g, b;
            switch (f.role) {
                case 0 -> { r = j.r; g = j.g; b = j.b; }
                case 1 -> { r = (int) (j.r * 0.86f); g = (int) (j.g * 0.86f); b = (int) (j.b * 0.86f); }
                default -> { r = j.cr; g = j.cg; b = j.cb; }              // rim, heart, arms: the glow
            }
            int a = (int) (fade * f.alpha * 255);
            for (int c = 0; c < 4; c++) vr(bb, m, vx[c], vy[c], vz[c], SU0 + (SU1 - SU0) * (c == 1 || c == 2 ? 1 : 0), c < 2 ? SV0 : SV1, r, g, b, a, GLOW);
        }
    }

    /**
     * A tuna from the model (tuna.obj, nose toward +x): the front stays stiff, the back half and the tail sweep hard and
     * fast, quicker the faster it's going; the body bends a little into the curve it's following.
     */
    private static void drawTuna(BufferBuilder bb, Matrix4f m, Vec3 cam, float pt, ClientLevel level, Tuna t) {
        float a = Mth.lerp(pt, t.palpha, t.alpha);
        if (a <= 0.01f) return;
        float x = (float) (Mth.lerp(pt, t.px, t.x) - cam.x), y = (float) (Mth.lerp(pt, t.py, t.y) - cam.y), z = (float) (Mth.lerp(pt, t.pz, t.z) - cam.z);
        float d2 = x * x + y * y + z * z;
        a *= 1 - murk(d2);
        if (a <= 0.01f) return;
        SeaModel model = SeaModel.get("tuna");
        float yaw = Mth.lerp(pt, t.pyaw, t.yaw);
        float dx = Mth.cos(yaw), dz = Mth.sin(yaw), sx = -dz, sz = dx;
        float time = t.age + pt;
        float rate = 2.2f + t.speed * 1.6f;                                // tail beats faster at speed
        float L = model.maxX - model.minX;
        int light = LevelRenderer.getLightColor(level, BlockPos.containing(t.x, t.y, t.z));
        int A = (int) (a * 255);
        float[] vx = new float[4], vy = new float[4], vz = new float[4];
        for (SeaModel.Face f : model.faces) {
            for (int c = 0; c < 4; c++) {
                float u = L < 1e-4f ? 0 : (model.maxX - f.x[c]) / L;      // 0 at the nose, 1 at the tail tip
                float back = -f.x[c];
                float wig = Mth.sin(time * rate + t.beat - u * 2.4f) * 0.22f * u * u * u - t.turn / t.speed * back * back * 0.5f;
                float roll = Mth.sin(time * rate + t.beat) * 0.025f * (1 - u);
                float side = f.z[c] + wig;
                vx[c] = x + dx * f.x[c] + sx * side;
                vy[c] = y + f.y[c] + roll;
                vz[c] = z + dz * f.x[c] + sz * side;
            }
            int r = (int) (f.r * f.shade), g = (int) (f.g * f.shade), b = (int) (f.b * f.shade);
            int al = (int) (A * f.alpha);
            for (int c = 0; c < 4; c++) vr(bb, m, vx[c], vy[c], vz[c], SU0 + (SU1 - SU0) * (c == 1 || c == 2 ? 1 : 0), c < 2 ? SV0 : SV1, r, g, b, al, light);
        }
    }

    // ------------------------------------------------------------------ testing

    public static void devBloom(int x, int z, int floor, int top) { Bloom b = makeBloom(Minecraft.getInstance().level, x, z, floor, top); b.alpha = b.palpha = 1; blooms.add(b); }
    /** For testing: building one frame of the bloom's jellyfish, without drawing it, averaged (ms). */
    public static String devBench() {
        if (blooms.isEmpty()) return "no bloom";
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        var bb = new BufferBuilder(1 << 20);
        int runs = 200; long verts = 0;
        long t0 = System.nanoTime();
        for (int r = 0; r < runs; r++) {
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            for (Bloom b : blooms) for (Jelly j : b.jellies) drawJelly(bb, null, cam, 0.5f, j, 1);
            var out = bb.end(); verts = out.drawState().vertexCount(); out.release();
        }
        return String.format("bloom build %.3f ms/frame, %d vertices, %d jellies", (System.nanoTime() - t0) / 1e6 / runs, verts, blooms.get(0).jellies.length);
    }

    public static String devInfo() {
        return "blooms " + blooms.size() + (tunas.isEmpty() ? " no tuna" : String.format(" %d tuna %.1f,%.1f,%.1f", tunas.size(), tunas.get(0).x, tunas.get(0).y, tunas.get(0).z));
    }
    public static void devTuna() { forceTuna = true; }
}
