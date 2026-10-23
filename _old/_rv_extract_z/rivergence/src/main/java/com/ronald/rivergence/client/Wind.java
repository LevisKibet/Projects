package com.ronald.rivergence.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.ronald.rivergence.Rivergence;
import com.ronald.rivergence.RivergenceConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Gusts of wind made visible: pale, wispy ribbons that sweep past a few blocks above the ground, curling as they go
 * and following the lie of the land, then thinning away. Now and then on a still day; often in rain, more often in a
 * thunderstorm, and in a snowstorm they carry the snow along with them. The wind keeps one direction for a while and
 * slowly turns.
 */
@Mod.EventBusSubscriber(modid = Rivergence.MODID, value = Dist.CLIENT)
public final class Wind {
    private Wind() {}

    static final ResourceLocation STREAK = new ResourceLocation(Rivergence.MODID, "textures/environment/wind_streak.png");

    private static final class Gust {
        double x, z, y;             // where its head started
        float dx, dz;               // which way it blows (unit)
        float speed, length, width, curl, phase, rise;
        int age, life;
        boolean snowy;
        double baseY;             // one smooth height for the whole gust
    }

    private static final List<Gust> GUSTS = new ArrayList<>();
    private static int cooldown = 200;

    /** Where the wind blows from today: a direction that drifts round slowly over the days, plus a wander through the day. */
    private static float heading(ClientLevel level) {
        long day = level.getDayTime() / 24000L;
        double base = (hash(day) * Math.PI * 2);
        double next = (hash(day + 1) * Math.PI * 2);
        float t = Math.floorMod(level.getDayTime(), 24000L) / 24000f;
        double d = next - base;
        while (d > Math.PI) d -= Math.PI * 2;
        while (d < -Math.PI) d += Math.PI * 2;
        return (float) (base + d * t + 0.3 * Math.sin(level.getGameTime() / 2400.0));
    }

    private static double hash(long a) {
        long h = a * 0x9E3779B97F4A7C15L; h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player p = mc.player;
        if (level == null || p == null) { GUSTS.clear(); return; }
        if (mc.isPaused()) return;
        if (!RivergenceConfig.weather() || !level.dimensionType().natural() || level.dimensionType().hasCeiling()) { GUSTS.clear(); return; }
        RandomSource r = level.random;
        boolean rain = level.isRaining(), thunder = level.isThundering();
        BlockPos at = p.blockPosition();
        Biome biome = level.getBiome(at).value();
        boolean snow = rain && biome.coldEnoughToSnow(at);
        boolean sheltered = !level.canSeeSky(at.above());

        // how often: a gust every minute or two on a still day, every few seconds in a storm
        // higher up, windier: twice as often 50 blocks above the sea, about four times as often high in the sky
        float height = Mth.clamp((float) (p.getY() - 70) / 50f, 0, 3);
        // moving fast (flying, gliding), the air rushes past
        Vec3 v = p.getDeltaMovement();
        float speed = (float) v.length() * 20;                                  // blocks a second
        if (--cooldown <= 0) {
            spawn(level, p, r, snow);
            int gap = thunder ? 40 + r.nextInt(80) : snow ? 30 + r.nextInt(70) : rain ? 80 + r.nextInt(160) : 1200 + r.nextInt(1800);
            if (sheltered) gap *= 2;
            gap = (int) (gap / (1 + height));
            cooldown = Math.max(6, gap);
            // storms send them in little bands
            if ((thunder || snow) && r.nextFloat() < 0.5f) cooldown = 8 + r.nextInt(20);
        }
        if (speed > 9 && !sheltered && r.nextFloat() < Math.min(0.5f, (speed - 9) / 40f)) rushPast(p, v, speed, r);

        for (int i = GUSTS.size() - 1; i >= 0; i--) {
            Gust g = GUSTS.get(i);
            if (++g.age > g.life) { GUSTS.remove(i); continue; }
            // in a snowstorm, the snow is swept along with it
            if (g.snowy && r.nextInt(2) == 0) {
                double s = g.age * g.speed / 20.0 - r.nextDouble() * g.length;
                double px = g.x + g.dx * s, pz = g.z + g.dz * s;
                double py = g.baseY + (r.nextDouble() - 0.5) * 2;
                level.addParticle(ParticleTypes.SNOWFLAKE, px, py, pz, g.dx * g.speed / 20.0 * 0.9, -0.02, g.dz * g.speed / 20.0 * 0.9);
            }
        }
    }

    private static void spawn(ClientLevel level, Player p, RandomSource r, boolean snow) {
        if (GUSTS.size() > 16) return;
        float head = heading(level) + (r.nextFloat() - 0.5f) * 0.5f;
        Gust g = new Gust();
        g.dx = Mth.cos(head); g.dz = Mth.sin(head);
        boolean storm = level.isRaining();
        g.speed = (storm ? 13 : 9) + r.nextFloat() * 5;                       // blocks a second
        g.length = 5 + r.nextFloat() * (storm ? 8 : 6);
        g.width = 0.35f + r.nextFloat() * 0.5f;
        g.curl = 0.4f + r.nextFloat() * 0.8f;
        g.phase = r.nextFloat() * 6.28f;
        g.rise = 2f + r.nextFloat() * (storm ? 5 : 4);
        g.snowy = snow;
        // it comes in from upwind and passes near the player, a little to one side
        double side = (r.nextDouble() - 0.5) * 40, back = 36 + r.nextDouble() * 20;
        g.x = p.getX() - g.dx * back - g.dz * side;
        g.z = p.getZ() - g.dz * back + g.dx * side;
        g.life = (int) ((back * 2 + g.length) / g.speed * 20);
        // one height for the gust: above the highest ground along its way near the player, or at the player's own
        // height when they're high above the land
        double top = 0;
        for (int k = -2; k <= 2; k++) top = Math.max(top, ground(level, p.getX() + g.dx * k * 10 - g.dz * side, p.getZ() + g.dz * k * 10 + g.dx * side));
        double above = p.getY() - ground(level, p.getX(), p.getZ());
        g.baseY = above > 12 ? p.getY() + (r.nextDouble() - 0.5) * 14 : top + g.rise;
        GUSTS.add(g);
    }

    /** Flying fast: a streak just ahead, held still in the air, so it whips past. */
    private static void rushPast(Player p, Vec3 v, float speed, RandomSource r) {
        if (GUSTS.size() > 24) return;
        Vec3 dir = v.normalize();
        Gust g = new Gust();
        double h = Math.hypot(dir.x, dir.z);
        g.dx = h < 1e-3 ? 1 : (float) (-dir.x / h); g.dz = h < 1e-3 ? 0 : (float) (-dir.z / h);
        g.speed = 2 + r.nextFloat() * 2;
        g.length = 3 + r.nextFloat() * 4;
        g.width = 0.2f + r.nextFloat() * 0.25f;
        g.curl = 0.3f; g.phase = r.nextFloat() * 6.28f; g.rise = 0;
        double ahead = 10 + speed * 0.6, side = (r.nextDouble() - 0.5) * 14;
        g.x = p.getX() + dir.x * ahead - g.dz * side;
        g.z = p.getZ() + dir.z * ahead + g.dx * side;
        g.baseY = p.getEyeY() + dir.y * ahead + (r.nextDouble() - 0.5) * 8;
        g.life = 20 + r.nextInt(15);
        GUSTS.add(g);
    }

    private static double ground(ClientLevel level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || GUSTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 cam = e.getCamera().getPosition();
        float pt = e.getPartialTick();
        float light = Mth.clamp(level.getSkyDarken(pt) * 1.1f, 0.3f, 1f);
        Matrix4f mat = e.getPoseStack().last().pose();

        var tex = mc.getTextureManager().getTexture(STREAK);
        RenderSystem.setShaderTexture(0, STREAK);
        RenderSystem.bindTexture(tex.getId());
        tex.setFilter(true, false);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        final int N = 24;
        Vector3f[] pts = new Vector3f[N + 1];
        float c = 0.9f * light + 0.1f;
        for (Gust g : GUSTS) {
            float age = g.age + pt;
            float lifeF = age / g.life;
            float fade = Math.min(1, lifeF * 5) * Math.min(1, (1 - lifeF) * 5);
            float headS = age * g.speed / 20f;
            float alpha = 0.26f * fade * (g.snowy ? 1.3f : 1);
            // the ribbon's line: back along the wind from its head, swinging side to side and rolling up and down
            for (int i = 0; i <= N; i++) {
                float t = i / (float) N, s = headS - t * g.length;
                // one soft bend side to side and a gentle rise and fall, slowly changing as it goes
                double wob = Math.sin(t * Math.PI * g.curl + g.phase + age * 0.03) * 0.7;
                double roll = Math.sin(t * Math.PI * 0.8 + g.phase * 1.7 + age * 0.04) * 0.35;
                double x = g.x + g.dx * s - g.dz * wob, z = g.z + g.dz * s + g.dx * wob;
                double y = g.baseY + roll;
                pts[i] = new Vector3f((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z));
            }
            // one sideways direction per point (shared by the pieces either side), so the ribbon bends without kinks
            Vector3f[] sides = new Vector3f[N + 1];
            for (int i = 0; i <= N; i++) {
                Vector3f seg = new Vector3f(pts[Math.min(N, i + 1)]).sub(pts[Math.max(0, i - 1)]);
                Vector3f sd = new Vector3f(seg).cross(pts[i]);
                if (sd.lengthSquared() < 1e-8) sd.set(0, 1, 0);
                float taper = (float) Math.pow(Math.sin(Math.PI * i / N), 0.8);  // fine points at both ends
                sides[i] = sd.normalize().mul(g.width * 0.5f * taper);
            }
            float cb = Math.min(1, c + 0.04f);
            for (int i = 0; i < N; i++) {
                Vector3f a = pts[i], q = pts[i + 1], sa = sides[i], sq = sides[i + 1];
                float u0 = 1 - i / (float) N, u1 = 1 - (i + 1) / (float) N;
                b.vertex(mat, a.x - sa.x, a.y - sa.y, a.z - sa.z).uv(u0, 0).color(c, c, cb, alpha).endVertex();
                b.vertex(mat, a.x + sa.x, a.y + sa.y, a.z + sa.z).uv(u0, 1).color(c, c, cb, alpha).endVertex();
                b.vertex(mat, q.x + sq.x, q.y + sq.y, q.z + sq.z).uv(u1, 1).color(c, c, cb, alpha).endVertex();
                b.vertex(mat, q.x - sq.x, q.y - sq.y, q.z - sq.z).uv(u1, 0).color(c, c, cb, alpha).endVertex();
            }
        }
        BufferUploader.drawWithShader(b.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
