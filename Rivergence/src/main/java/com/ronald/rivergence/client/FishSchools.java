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
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Schools of small fish, drawn only on this screen (nothing to catch or hit): flat silhouettes in oceans, lakes and
 * big rivers, a handful to a great shoal, tinted for the water they live in. Come near and the school turns away and
 * hurries off; swim right in among them and they part round you and close up again behind. Now and then a pair of
 * koi, one white and one black, circles head to tail in a plunge pool, drawn with some body to them since you see
 * them close; step into the pool and they're gone in the stir of the water.
 */
@Mod.EventBusSubscriber(modid = Rivergence.MODID, value = Dist.CLIENT)
public final class FishSchools {
    private FishSchools() {}

    private static final ResourceLocation TEX = new ResourceLocation(Rivergence.MODID, "textures/misc/fish.png");
    private static final int MAX_SCHOOLS = 9, MAX_FISH = 750;
    private static final double REACH = 56, GONE = 84;

    static final class Fish {
        float x, y, z, px, py, pz, vx, vy, vz;
        /** Its place in the school (along, across, up, in the school's own frame). */
        float oa, oc, ou;
        float size, phase, rate, shade;
        int r, g, b;
    }

    static final class School {
        double cx, cy, cz;                  // centre
        float hx = 1, hz = 0, hy;           // heading
        float speed, cruise;
        float scale, spread;
        int r, g, b, variant;
        int turnTicks;
        float alpha, palpha;
        boolean leaving;
        int age;
        Fish[] fish;
        // the water it keeps to
        int floorY, topY;
    }

    static final class Koi {
        double x, y, z;                     // pool centre, at the swimming depth
        float r;                            // circling radius
        float ang, pang, speed;
        float alpha, palpha;
        boolean leaving;
        long key;
    }

    private static final List<School> schools = new ArrayList<>();
    private static Koi koi;
    private static final Map<Long, Long> koiRest = new HashMap<>();
    private static final Random RND = new Random();
    private static ClientLevel lastLevel;
    private static int clock;

    private static int total() { int n = 0; for (School s : schools) n += s.fish.length; return n; }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player p = mc.player;
        if (level != lastLevel) { schools.clear(); koi = null; koiRest.clear(); lastLevel = level; }
        if (level == null || p == null || mc.isPaused()) return;
        if (!RivergenceConfig.schools()) { schools.clear(); koi = null; return; }
        clock++;
        if (clock % 10 == 0) trySpawn(level, p);
        if (clock % 40 == 5) tryKoi(level, p);
        for (int i = schools.size() - 1; i >= 0; i--) {
            School s = schools.get(i);
            update(level, p, s);
            if (s.leaving && s.alpha <= 0) schools.remove(i);
        }
        if (koi != null) { updateKoi(p); if (koi.leaving && koi.alpha <= 0) koi = null; }
    }

    /** For testing: a school right here. */
    public static void devSchool(ClientLevel level, int x, int z, int top) {
        int depth = depth(level, x, top, z, 24);
        Holder<Biome> bio = level.getBiome(new BlockPos(x, top, z));
        School s = make(level, x, top, z, depth, bio, bio.is(BiomeTags.IS_OCEAN), bio.is(BiomeTags.IS_RIVER));
        s.alpha = s.palpha = 1;
        schools.add(s);
    }

    public static String devInfo() {
        StringBuilder b = new StringBuilder(schools.size() + " schools");
        for (School s : schools) b.append(String.format(" [%.1f,%.1f,%.1f n%d a%.2f top%d floor%d f0 %.1f,%.1f,%.1f]", s.cx, s.cy, s.cz, s.fish.length, s.alpha, s.topY, s.floorY, s.fish[0].x, s.fish[0].y, s.fish[0].z));
        return b.toString();
    }

    /** For testing: koi in the pool centred here. */
    public static void devKoi(double x, double y, double z, float r) {
        Koi k = new Koi();
        k.x = x; k.y = y; k.z = z; k.r = r; k.speed = 0.025f; k.alpha = k.palpha = 1;
        koi = k;
    }

    // ---------------------------------------------------------------- spawning

    private static void trySpawn(ClientLevel level, Player p) {
        if (schools.size() >= MAX_SCHOOLS || total() >= MAX_FISH) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            double a = RND.nextDouble() * Math.PI * 2, d = 20 + RND.nextDouble() * (REACH - 20);
            int x = Mth.floor(p.getX() + Math.cos(a) * d), z = Mth.floor(p.getZ() + Math.sin(a) * d);
            int top = surface(level, x, z, Mth.floor(p.getY()) + 20, Mth.floor(p.getY()) - 40);
            if (top == Integer.MIN_VALUE) continue;
            int depth = depth(level, x, top, z, 24);
            Holder<Biome> bio = level.getBiome(new BlockPos(x, top, z));
            boolean ocean = bio.is(BiomeTags.IS_OCEAN) || bio.is(BiomeTags.IS_DEEP_OCEAN), river = bio.is(BiomeTags.IS_RIVER);
            int need = ocean ? 4 : river ? 2 : 3;
            if (depth < need) continue;
            // rivers and lakes: only broad water (not a stream or a puddle)
            int wide = 0;
            for (int k = 0; k < 8; k++) {
                double b = k * Math.PI / 4;
                int qx = Mth.floor(x + Math.cos(b) * 5), qz = Mth.floor(z + Math.sin(b) * 5);
                if (level.getFluidState(new BlockPos(qx, top, qz)).is(FluidTags.WATER)) wide++;
            }
            if (wide < (ocean ? 6 : 7)) continue;
            // not too close to another school
            boolean near = false;
            for (School o : schools) if (Math.hypot(o.cx - x, o.cz - z) < 14) near = true;
            if (near) continue;
            schools.add(make(level, x, top, z, depth, bio, ocean, river));
            return;
        }
    }

    private static School make(ClientLevel level, int x, int top, int z, int depth, Holder<Biome> bio, boolean ocean, boolean river) {
        School s = new School();
        s.topY = top; s.floorY = top - depth + 1;
        float t = bio.value().getBaseTemperature();
        String id = bio.unwrapKey().map(k -> k.location().getPath()).orElse("");
        int[][] pal;
        boolean mixed = false;
        int count;
        if (ocean) {
            if (id.contains("warm") || id.contains("lukewarm")) {
                pal = new int[][]{{255, 210, 40}, {255, 140, 30}, {60, 215, 235}, {245, 90, 150}, {150, 110, 245}, {90, 235, 120}, {255, 80, 70}};
                mixed = RND.nextFloat() < 0.45f;
                s.scale = 0.4f + RND.nextFloat() * 0.2f; count = 12 + RND.nextInt(50); s.variant = 1;
            } else if (id.contains("frozen") || id.contains("cold") || t < 0.2f) {
                pal = new int[][]{{185, 198, 215}, {120, 150, 190}, {150, 190, 185}, {200, 175, 150}};
                s.scale = 0.45f + RND.nextFloat() * 0.35f; count = 20 + RND.nextInt(110); s.variant = 0;
            } else {
                pal = new int[][]{{80, 150, 220}, {60, 190, 200}, {130, 165, 215}, {240, 200, 70}, {210, 120, 200}, {100, 210, 160}};
                s.scale = 0.45f + RND.nextFloat() * 0.45f; count = 25 + RND.nextInt(130); s.variant = 0;
            }
        } else if (river) {
            pal = t < 0.3f ? new int[][]{{170, 175, 170}, {200, 120, 110}, {130, 160, 150}} : new int[][]{{175, 160, 90}, {205, 130, 70}, {140, 170, 100}, {210, 175, 120}};
            s.scale = 0.28f + RND.nextFloat() * 0.14f; count = 5 + RND.nextInt(18); s.variant = 1;
        } else if (id.contains("swamp") || id.contains("mangrove") || id.contains("bog") || id.contains("marsh")) {
            pal = new int[][]{{140, 150, 70}, {170, 120, 60}, {110, 140, 110}};
            s.scale = 0.28f + RND.nextFloat() * 0.12f; count = 5 + RND.nextInt(14); s.variant = 1;
        } else if (id.contains("jungle") || id.contains("tropical")) {
            pal = new int[][]{{245, 140, 50}, {240, 70, 80}, {70, 200, 220}, {250, 215, 60}};
            mixed = RND.nextFloat() < 0.35f;
            s.scale = 0.3f + RND.nextFloat() * 0.15f; count = 6 + RND.nextInt(25); s.variant = 1;
        } else {
            pal = t < 0.3f ? new int[][]{{180, 190, 200}, {140, 175, 200}, {200, 150, 130}} : new int[][]{{240, 180, 60}, {235, 110, 60}, {140, 190, 110}, {200, 210, 170}, {120, 170, 210}};
            s.scale = 0.3f + RND.nextFloat() * 0.18f; count = 6 + RND.nextInt(36); s.variant = RND.nextInt(2);
        }
        int[] col = pal[RND.nextInt(pal.length)];
        // some schools wear a pattern: bars or spots, the slim ones barred and the round ones spotted
        if (RND.nextFloat() < 0.45f) s.variant += 2;
        // a few just a handful
        if (RND.nextFloat() < 0.18f) count = 5 + RND.nextInt(4);
        s.r = col[0]; s.g = col[1]; s.b = col[2];
        s.spread = (float) Math.max(1.2, Math.cbrt(count) * 0.9 * (0.6 + s.scale));
        s.cx = x + 0.5; s.cz = z + 0.5;
        s.cy = Mth.clamp(top - 1.0 - RND.nextDouble() * Math.max(0, depth - 2.5), s.floorY + 0.8, top - 0.6);
        double a = RND.nextDouble() * Math.PI * 2;
        s.hx = (float) Math.cos(a); s.hz = (float) Math.sin(a);
        s.cruise = 0.05f + RND.nextFloat() * 0.04f + s.scale * 0.05f;
        s.speed = s.cruise;
        s.fish = new Fish[count];
        for (int i = 0; i < count; i++) {
            Fish f = new Fish();
            // an oval cloud, longest along the way they swim, flattened top to bottom
            double u = RND.nextGaussian(), v = RND.nextGaussian(), w = RND.nextGaussian();
            f.oa = (float) (u * s.spread * 0.75);
            f.oc = (float) (v * s.spread * 0.55);
            f.ou = (float) (w * Math.min(s.spread * 0.3, Math.max(0.3, (depth - 1.5) * 0.3)));
            f.size = s.scale * (0.8f + RND.nextFloat() * 0.4f);
            f.phase = RND.nextFloat() * 6.283f;
            f.rate = 0.5f + RND.nextFloat() * 0.3f;
            f.shade = 0.58f + RND.nextFloat() * 0.18f;                    // a darker tint: they read as fish in the water, not bright cut-outs
            int[] c = mixed ? pal[RND.nextInt(pal.length)] : col;
            f.r = c[0]; f.g = c[1]; f.b = c[2];
            f.x = f.px = (float) (s.cx + s.hx * f.oa - s.hz * f.oc);
            f.y = f.py = (float) Mth.clamp(s.cy + f.ou, s.floorY + 0.3, s.topY + 0.6);
            f.z = f.pz = (float) (s.cz + s.hz * f.oa + s.hx * f.oc);
            s.fish[i] = f;
        }
        return s;
    }

    /** The top water block in this column between hi and lo (or MIN_VALUE): open air over it, so it's a surface. */
    private static int surface(ClientLevel level, int x, int z, int hi, int lo) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = hi; y >= lo; y--) {
            if (level.getFluidState(m.set(x, y, z)).is(FluidTags.WATER)) {
                return level.getFluidState(m.set(x, y + 1, z)).isEmpty() ? y : Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int depth(ClientLevel level, int x, int top, int z, int max) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int d = 0;
        while (d < max && level.getFluidState(m.set(x, top - d, z)).is(FluidTags.WATER)) d++;
        return d;
    }

    private static boolean water(ClientLevel level, double x, double y, double z) {
        return level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER);
    }

    // ---------------------------------------------------------------- swimming

    private static void update(ClientLevel level, Player p, School s) {
        s.age++;
        s.palpha = s.alpha;
        double dpx = s.cx - p.getX(), dpz = s.cz - p.getZ(), dpy = s.cy - p.getEyeY();
        double dp = Math.sqrt(dpx * dpx + dpz * dpz);
        if (dp > GONE) s.leaving = true;
        s.alpha = Mth.clamp(s.alpha + (s.leaving ? -0.05f : 0.025f), 0, 1);
        // the player near: turn away and hurry (more the closer)
        double scare = 4.5 + s.spread * 1.3;
        double d3 = Math.sqrt(dp * dp + dpy * dpy);
        float want = s.cruise;
        if (d3 < scare) {
            float away = (float) Math.atan2(dpz, dpx), cur = (float) Math.atan2(s.hz, s.hx);
            float k = (float) (1 - d3 / scare);
            cur = rotateToward(cur, away, 0.12f + 0.25f * k);
            s.hx = Mth.cos(cur); s.hz = Mth.sin(cur);
            want = s.cruise * (1 + 2.5f * k);
        } else if (--s.turnTicks <= 0) {
            // drifting: a slow new bearing now and then
            float cur = (float) Math.atan2(s.hz, s.hx) + (RND.nextFloat() - 0.5f) * 1.2f;
            s.hx = Mth.cos(cur); s.hz = Mth.sin(cur);
            s.turnTicks = 60 + RND.nextInt(160);
        }
        s.speed += (want - s.speed) * 0.15f;
        // the way ahead must be water: otherwise wheel round
        double ax = s.cx + s.hx * (2.5 + s.spread), az = s.cz + s.hz * (2.5 + s.spread);
        if (!water(level, ax, s.cy, az)) {
            float cur = (float) Math.atan2(s.hz, s.hx);
            float best = cur + (float) Math.PI;
            for (int k = 1; k <= 6; k++) {
                float c1 = cur + k * 0.45f * (RND.nextBoolean() ? 1 : -1);
                if (water(level, s.cx + Mth.cos(c1) * (2.5 + s.spread), s.cy, s.cz + Mth.sin(c1) * (2.5 + s.spread))) { best = c1; break; }
            }
            float nc = rotateToward(cur, best, 0.6f);
            s.hx = Mth.cos(nc); s.hz = Mth.sin(nc);
        }
        double nx = s.cx + s.hx * s.speed, nz = s.cz + s.hz * s.speed;
        if (water(level, nx, s.cy, nz)) { s.cx = nx; s.cz = nz; }
        // drifting up and down a little within the water
        s.hy += (RND.nextFloat() - 0.5f) * 0.004f;
        s.hy = Mth.clamp(s.hy * 0.98f, -0.02f, 0.02f);
        double ny = s.cy + s.hy;
        if (ny > s.floorY + 0.7 && ny < s.topY - 0.5 && water(level, s.cx, ny, s.cz)) s.cy = ny; else s.hy = -s.hy;
        // a school that's been met too long or wandered out of the water goes
        if (!water(level, s.cx, s.cy, s.cz) || s.age > 20 * 60 * 6) s.leaving = true;

        // each fish: toward its place in the school, kept off the player (they part round you)
        float hx = s.hx, hz = s.hz;
        double ex = p.getX(), ey = p.getY() + 0.9, ez = p.getZ();
        double open = 1.6 + s.scale * 3 + s.spread * 0.15;
        for (Fish f : s.fish) {
            f.px = f.x; f.py = f.y; f.pz = f.z;
            float wob = Mth.sin(s.age * 0.05f * f.rate + f.phase);
            double tx = s.cx + hx * f.oa - hz * (f.oc + wob * 0.25f);
            double ty = s.cy + f.ou + Mth.sin(s.age * 0.03f + f.phase) * 0.12f;
            double tz = s.cz + hz * f.oa + hx * (f.oc + wob * 0.25f);
            float ax2 = (float) ((tx - f.x) * 0.06), ay2 = (float) ((ty - f.y) * 0.06), az2 = (float) ((tz - f.z) * 0.06);
            double qx = f.x - ex, qy = f.y - ey, qz = f.z - ez, qd = Math.sqrt(qx * qx + qy * qy * 1.5 + qz * qz);
            if (qd < open && qd > 1e-4) {
                float push = (float) ((open - qd) / open) * 0.22f;
                ax2 += qx / qd * push; ay2 += qy / qd * push * 0.6f; az2 += qz / qd * push;
            }
            f.vx = f.vx * 0.82f + ax2 + hx * s.speed * 0.18f;
            f.vy = f.vy * 0.82f + ay2;
            f.vz = f.vz * 0.82f + az2 + hz * s.speed * 0.18f;
            float sp = Mth.sqrt(f.vx * f.vx + f.vy * f.vy + f.vz * f.vz), max = 0.45f;
            if (sp > max) { f.vx *= max / sp; f.vy *= max / sp; f.vz *= max / sp; }
            // they never get caught on the land: a fish that strays into a bank just carries on through (it's hidden
            // there) and rejoins the school; only the surface is a limit
            f.x += f.vx; f.z += f.vz;
            f.y = Mth.clamp(f.y + f.vy, s.floorY - 1.5f, s.topY + 0.55f);
        }
    }

    private static float rotateToward(float cur, float target, float max) {
        float d = Mth.wrapDegrees((target - cur) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
        return cur + Mth.clamp(d, -max, max);
    }

    // ---------------------------------------------------------------- koi

    /** A plunge pool near by: deep still water with the rock rim (mossy cobble) round it and falling water into it. */
    private static void tryKoi(ClientLevel level, Player p) {
        if (koi != null) return;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < 4; attempt++) {
            int x = Mth.floor(p.getX()) + RND.nextInt(49) - 24, z = Mth.floor(p.getZ()) + RND.nextInt(49) - 24;
            int top = surface(level, x, z, Mth.floor(p.getY()) + 16, Mth.floor(p.getY()) - 24);
            if (top == Integer.MIN_VALUE || depth(level, x, top, z, 6) < 2) continue;
            int rim = 0, fall = 0, wet = 0;
            double sx = 0, sz = 0;
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                m.set(x + dx, top, z + dz);
                if (level.getBlockState(m).is(Blocks.MOSSY_COBBLESTONE) || level.getBlockState(m.set(x + dx, top + 1, z + dz)).is(Blocks.MOSSY_COBBLESTONE)) rim++;
                FluidState fs = level.getFluidState(m.set(x + dx, top, z + dz));
                if (fs.is(FluidTags.WATER) && fs.isSource()) { wet++; sx += dx; sz += dz; }
                for (int up = 1; up <= 3; up++) {
                    FluidState f2 = level.getFluidState(m.set(x + dx, top + up, z + dz));
                    if (f2.is(FluidTags.WATER) && !f2.isSource()) { fall++; break; }
                }
            }
            if (rim < 2 || fall < 1 || wet < 9 || wet > 70) continue;
            double cx = x + 0.5 + sx / wet, cz = z + 0.5 + sz / wet;
            long key = BlockPos.asLong(Mth.floor(cx) >> 3, top, Mth.floor(cz) >> 3);
            // only a few pools ever have them, and once you've stirred them up they stay away a while
            long h = key * 0x9E3779B97F4A7C15L; h ^= h >>> 29;
            if ((h & 1023) > 160) continue;
            Long rest = koiRest.get(key);
            if (rest != null && rest > clock) continue;
            int cdep = depth(level, Mth.floor(cx), top, Mth.floor(cz), 6);
            if (cdep < 2) continue;
            Koi k = new Koi();
            k.x = cx; k.z = cz; k.y = top + 1 - Math.min(1.1, cdep * 0.45);
            k.r = (float) Math.min(1.5, Math.max(0.8, Math.sqrt(wet) * 0.22));
            k.speed = 0.022f + RND.nextFloat() * 0.01f;
            k.ang = k.pang = RND.nextFloat() * 6.283f;
            k.key = key;
            koi = k;
            return;
        }
    }

    private static void updateKoi(Player p) {
        Koi k = koi;
        k.pang = k.ang; k.palpha = k.alpha;
        k.ang += k.speed;
        double d = Math.hypot(p.getX() - k.x, p.getZ() - k.z);
        // the player stepping into the pool: the water's stirred and they're lost in it
        if (p.isInWater() && d < k.r + 3.5 && Math.abs(p.getY() - k.y) < 4) {
            k.leaving = true;
            koiRest.put(k.key, (long) clock + 20 * 120);
        }
        if (d > 40) k.leaving = true;
        k.alpha = Mth.clamp(k.alpha + (k.leaving ? -0.12f : 0.02f), 0, 1);
    }

    // ---------------------------------------------------------------- drawing

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) return;
        if (schools.isEmpty() && koi == null) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        float pt = e.getPartialTick();
        Vec3 cam = e.getCamera().getPosition();
        Matrix4f mat = e.getPoseStack().last().pose();

        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, TEX);
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        int n = 0;
        for (School s : schools) {
            float a = Mth.lerp(pt, s.palpha, s.alpha);
            if (a <= 0.01f) continue;
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(s.cx, s.cy, s.cz));
            // the four silhouettes: plain slim, plain round (top row), barred, spotted with a dark back (bottom row)
            float u0 = s.variant == 0 ? 0 : s.variant == 1 ? 0.5f : s.variant == 2 ? 16 / 128f : 72 / 128f;
            float u1 = s.variant < 2 ? u0 + 0.5f : u0 + 56 / 128f, v0 = s.variant < 2 ? 0 : 0.5f;
            for (Fish f : s.fish) {
                float x = Mth.lerp(pt, f.px, f.x), y = Mth.lerp(pt, f.py, f.y), z = Mth.lerp(pt, f.pz, f.z);
                double cdx = x - cam.x, cdy = y - cam.y, cdz = z - cam.z;
                if (cdx * cdx + cdy * cdy + cdz * cdz > 96 * 96) continue;
                // facing the way the school swims, turned a little by its own drift; never pitched far up or down
                float dx = s.hx * 0.6f + f.vx * 4, dz = s.hz * 0.6f + f.vz * 4;
                float hl = Mth.sqrt(dx * dx + dz * dz);
                if (hl < 1e-4f) { dx = s.hx; dz = s.hz; hl = 1; }
                float dy = Mth.clamp(f.vy * 4, -0.25f * hl, 0.25f * hl);
                float l = Mth.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= l; dy /= l; dz /= l;
                fishQuad(bb, mat, (float) cdx, (float) cdy, (float) cdz, dx, dy, dz, f.size, s.age + pt, f, f.r * f.shade, f.g * f.shade, f.b * f.shade, a, u0, u1, v0, light);
                n++;
            }
        }
        if (koi != null) drawKoi(bb, mat, cam, pt, level);
        if (n > 0 || koi != null) BufferUploader.drawWithShader(bb.end());
        else bb.end().release();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }

    /** One fish: a flat silhouette along the way it swims, turned to face you about its own length, its tail flicking. */
    private static void fishQuad(BufferBuilder bb, Matrix4f mat, float x, float y, float z, float dx, float dy, float dz,
                                 float size, float time, Fish f, float r, float g, float b, float a, float u0, float u1, float v0, int light) {
        // the side of it you see: across its length, as square to your eye as can be, back always up
        float vx = x, vy = y, vz = z;
        float sx = dy * vz - dz * vy, sy = dz * vx - dx * vz, sz = dx * vy - dy * vx;    // d x view
        float ux = sx, uy = sy, uz = sz;                                                 // across its length, square to the eye
        float ul = Mth.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1e-4f) { ux = 0; uy = 1; uz = 0; ul = 1; }
        ux /= ul; uy /= ul; uz /= ul;
        if (uy < 0) { ux = -ux; uy = -uy; uz = -uz; }
        float L = size, Hh = size * 0.17f;
        // the tail swings side to side (across its length, level)
        float wx = -dz, wz = dx, wl = Mth.sqrt(wx * wx + wz * wz);
        if (wl > 1e-4f) { wx /= wl; wz /= wl; }
        float sw = Mth.sin(time * 0.6f * f.rate * 1.8f + f.phase) * 0.12f * L;
        float hx = x + dx * L * 0.5f, hy = y + dy * L * 0.5f, hz = z + dz * L * 0.5f;
        float mx = x, my = y, mz = z;
        float tx = x - dx * L * 0.5f + wx * sw, ty = y - dy * L * 0.5f, tz = z - dz * L * 0.5f + wz * sw;
        float um = (u0 + u1) / 2;
        int R = Mth.clamp((int) r, 0, 255), G = Mth.clamp((int) g, 0, 255), B = Mth.clamp((int) b, 0, 255), A = (int) (a * 255);
        // tail half
        v(bb, mat, tx - ux * Hh, ty - uy * Hh, tz - uz * Hh, u0, v0 + 0.5f, R, G, B, A, light);
        v(bb, mat, mx - ux * Hh, my - uy * Hh, mz - uz * Hh, um, v0 + 0.5f, R, G, B, A, light);
        v(bb, mat, mx + ux * Hh, my + uy * Hh, mz + uz * Hh, um, v0, R, G, B, A, light);
        v(bb, mat, tx + ux * Hh, ty + uy * Hh, tz + uz * Hh, u0, v0, R, G, B, A, light);
        // head half
        v(bb, mat, mx - ux * Hh, my - uy * Hh, mz - uz * Hh, um, v0 + 0.5f, R, G, B, A, light);
        v(bb, mat, hx - ux * Hh, hy - uy * Hh, hz - uz * Hh, u1, v0 + 0.5f, R, G, B, A, light);
        v(bb, mat, hx + ux * Hh, hy + uy * Hh, hz + uz * Hh, u1, v0, R, G, B, A, light);
        v(bb, mat, mx + ux * Hh, my + uy * Hh, mz + uz * Hh, um, v0, R, G, B, A, light);
    }

    static void v(BufferBuilder bb, Matrix4f mat, float x, float y, float z, float u, float vv, int r, int g, int b, int a, int light) {
        bb.vertex(mat, x, y, z).uv(u, vv).color(r, g, b, a).uv2(light).endVertex();
    }

    /** The pair of koi: bodies with some depth, bent to the circle they swim, one white and one black, head to tail. */
    private static void drawKoi(BufferBuilder bb, Matrix4f mat, Vec3 cam, float pt, ClientLevel level) {
        Koi k = koi;
        float a = Mth.lerp(pt, k.palpha, k.alpha);
        if (a <= 0.01f) return;
        int light = LevelRenderer.getLightColor(level, BlockPos.containing(k.x, k.y, k.z));
        float ang = Mth.lerp(pt, k.pang, k.ang);
        for (int which = 0; which < 2; which++) {
            float head = ang + which * Mth.PI;
            int[] body = which == 0 ? new int[]{238, 236, 228} : new int[]{32, 32, 38};
            int[] spot = which == 0 ? new int[]{32, 32, 38} : new int[]{238, 236, 228};
            koiBody(bb, mat, cam, k, head, body, spot, (int) (a * 255), light, (ang + which) * 7f);
        }
    }

    private static void koiBody(BufferBuilder bb, Matrix4f mat, Vec3 cam, Koi k, float head, int[] body, int[] spot, int A, int light, float time) {
        final int N = 9;
        float len = k.r * 2.3f;                       // along the arc
        float[] px = new float[N + 1], py = new float[N + 1], pz = new float[N + 1], w = new float[N + 1], hgt = new float[N + 1];
        for (int i = 0; i <= N; i++) {
            float s = i / (float) N;                   // 0 head .. 1 tail
            float an = head - s * len / k.r;           // back along the circle
            float wig = Mth.sin(time * 0.9f - s * 4f) * 0.06f * s * s;
            float rr = k.r + wig;
            px[i] = (float) (k.x + Mth.cos(an) * rr - cam.x);
            pz[i] = (float) (k.z + Mth.sin(an) * rr - cam.z);
            py[i] = (float) (k.y - cam.y);
            float prof = s < 0.22f ? 0.6f + 0.4f * Mth.sqrt(s / 0.22f) : 1 - (s - 0.22f) / 0.78f * 0.88f;
            w[i] = 0.24f * k.r * prof;
            hgt[i] = 0.16f * k.r * prof;
        }
        for (int i = 0; i < N; i++) {
            // the body's side-to-side direction at each ring: across the circle (toward its centre)
            float ax = (float) (k.x - cam.x) - px[i], az = (float) (k.z - cam.z) - pz[i];
            float al = Mth.sqrt(ax * ax + az * az); ax /= al; az /= al;
            float bx = (float) (k.x - cam.x) - px[i + 1], bz = (float) (k.z - cam.z) - pz[i + 1];
            float bl = Mth.sqrt(bx * bx + bz * bz); bx /= bl; bz /= bl;
            // a spot of the other colour near the head (the eye of the yin-yang)
            boolean dot = i == 2 || i == 3;
            int[] c = dot ? spot : body;
            // four faces of a diamond section: top-left, top-right, bottom-right, bottom-left
            float[][] ring0 = {{px[i] + ax * w[i], py[i], pz[i] + az * w[i]}, {px[i], py[i] + hgt[i], pz[i]}, {px[i] - ax * w[i], py[i], pz[i] - az * w[i]}, {px[i], py[i] - hgt[i] * 0.7f, pz[i]}};
            float[][] ring1 = {{px[i + 1] + bx * w[i + 1], py[i + 1], pz[i + 1] + bz * w[i + 1]}, {px[i + 1], py[i + 1] + hgt[i + 1], pz[i + 1]}, {px[i + 1] - bx * w[i + 1], py[i + 1], pz[i + 1] - bz * w[i + 1]}, {px[i + 1], py[i + 1] - hgt[i + 1] * 0.7f, pz[i + 1]}};
            for (int q = 0; q < 4; q++) {
                int q2 = (q + 1) % 4;
                float shade = q < 2 ? 1f : 0.62f;
                int R = (int) (c[0] * shade), G = (int) (c[1] * shade), B = (int) (c[2] * shade);
                v(bb, mat, ring0[q][0], ring0[q][1], ring0[q][2], 0.02f, 0.52f, R, G, B, A, light);
                v(bb, mat, ring1[q][0], ring1[q][1], ring1[q][2], 0.1f, 0.52f, R, G, B, A, light);
                v(bb, mat, ring1[q2][0], ring1[q2][1], ring1[q2][2], 0.1f, 0.98f, R, G, B, A, light);
                v(bb, mat, ring0[q2][0], ring0[q2][1], ring0[q2][2], 0.02f, 0.98f, R, G, B, A, light);
            }
            // pectoral fins behind the head
            if (i == 2) {
                for (int side = -1; side <= 1; side += 2) {
                    float fx = px[i] + ax * w[i] * side, fz = pz[i] + az * w[i] * side;
                    float ox = ax * side * 0.22f * k.r, oz = az * side * 0.22f * k.r;
                    float tx = (px[i + 1] - px[i]) * 0.9f, tz = (pz[i + 1] - pz[i]) * 0.9f;
                    int R = (int) (body[0] * 0.9f), G = (int) (body[1] * 0.9f), B = (int) (body[2] * 0.9f);
                    v(bb, mat, fx, py[i], fz, 0.02f, 0.52f, R, G, B, A * 3 / 4, light);
                    v(bb, mat, fx + ox, py[i] - 0.02f, fz + oz, 0.1f, 0.52f, R, G, B, A * 3 / 4, light);
                    v(bb, mat, fx + ox + tx, py[i] - 0.02f, fz + oz + tz, 0.1f, 0.98f, R, G, B, A * 3 / 4, light);
                    v(bb, mat, fx + tx, py[i], fz + tz, 0.02f, 0.98f, R, G, B, A * 3 / 4, light);
                }
            }
        }
        // the tail fin: a flat fan off the last ring, flicking
        float ex = px[N], ez = pz[N], dxT = px[N] - px[N - 1], dzT = pz[N] - pz[N - 1];
        float ax = (float) (k.x - cam.x) - ex, az = (float) (k.z - cam.z) - ez;
        float al = Mth.sqrt(ax * ax + az * az); ax /= al; az /= al;
        float fl = 0.35f * k.r, sw = Mth.sin(time * 0.9f) * 0.12f * k.r;
        float dl = Mth.sqrt(dxT * dxT + dzT * dzT); dxT /= dl; dzT /= dl;
        float tx = ex + dxT * fl + ax * sw, tz = ez + dzT * fl + az * sw;
        int R = body[0], G = body[1], B = body[2];
        v(bb, mat, ex, py[N], ez, 0.02f, 0.52f, R, G, B, A, light);
        v(bb, mat, tx + ax * fl * 0.6f, py[N], tz + az * fl * 0.6f, 0.1f, 0.52f, R, G, B, A, light);
        v(bb, mat, tx, py[N], tz, 0.1f, 0.98f, R, G, B, A, light);
        v(bb, mat, tx - ax * fl * 0.6f, py[N], tz - az * fl * 0.6f, 0.02f, 0.98f, R, G, B, A, light);
    }
}
