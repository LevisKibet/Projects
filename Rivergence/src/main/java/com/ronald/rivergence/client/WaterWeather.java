package com.ronald.rivergence.client;

import com.ronald.rivergence.Rivergence;
import com.ronald.rivergence.RivergenceConfig;
import com.ronald.rivergence.world.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Weather that belongs to the water, seen only on this screen: mist drifting over lakes and rivers at dawn (and in the
 * rain), fog settling into river valleys as the sun comes up, spray where falls hit the water, drizzle hanging over
 * wetlands and showers that pass over big water now and then. It reads the world round the player, so it works on
 * any server, Rivergence's water or not.
 */
@Mod.EventBusSubscriber(modid = Rivergence.MODID, value = Dist.CLIENT)
public final class WaterWeather {
    private WaterWeather() {}

    @Mod.EventBusSubscriber(modid = Rivergence.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Providers {
        @SubscribeEvent
        public static void register(RegisterParticleProvidersEvent e) {
            e.registerSpriteSet(ModParticles.MIST.get(), MistParticle.Mist::new);
            e.registerSpriteSet(ModParticles.SPRAY.get(), MistParticle.Spray::new);
        }
    }

    // what the land round the player is like, surveyed now and then
    private static float waterFrac, avgWaterY, avgLandY;
    private static boolean swamp, sea;
    // how strong each effect is now (eased toward its target)
    private static float valleyFog, shower, drizzle;
    private static final List<BlockPos> falls = new ArrayList<>();
    private static int ticks;

    /** 0 at night and in the day, 1 round sunrise. */
    static float dawn(long time) {
        long t = Math.floorMod(time, 24000L);
        if (t >= 22200) return Mth.clamp((t - 22200) / 1300f, 0, 1);
        if (t <= 2600) return t <= 1000 ? 1 : 1 - (t - 1000) / 1600f;
        return 0;
    }

    private static double hash(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player p = mc.player;
        if (level == null || p == null || mc.isPaused()) return;
        if (!RivergenceConfig.weather() || !level.dimensionType().natural() || level.dimensionType().hasCeiling()) {
            valleyFog = shower = drizzle = gloom = 0; seen = List.of(); falls.clear(); com.ronald.rivergence.world.WeatherHook.local = 0; return;
        }
        RandomSource r = level.random;
        ticks++;
        if (ticks % 20 == 0) survey(level, p, r);

        float dawn = dawn(level.getDayTime());
        boolean raining = com.ronald.rivergence.world.WeatherHook.vanillaRaining();
        // fog settling in a river valley at sunrise: low by the water with higher ground round it
        boolean valley = waterFrac > 0.03f && p.getY() < avgWaterY + 18 && avgLandY > avgWaterY + 5 && !sea;
        float fogTarget = dawn * (valley ? 1 : waterFrac > 0.2f ? 0.45f : 0);
        valleyFog += (fogTarget - valleyFog) * 0.01f;

        // spray where falls hit the water
        sprayTick(level, p, r);

        // passing showers over big water (from the cloud banks overhead), drizzle over wetlands (when it isn't raining anyway)
        int px = Mth.floor(p.getX()), pz = Mth.floor(p.getZ());
        long window = level.getGameTime() / 3600;
        float showerTarget = 0;
        float gloomTarget = 0;
        List<Shower> around = raining ? List.of() : showersInView(level, p.position(), 540);
        seen = around;
        for (Shower sh : around) {
            float d = (float) Math.hypot(sh.x - p.getX(), sh.z - p.getZ()) / sh.radius;
            showerTarget = Math.max(showerTarget, sh.strength * Mth.clamp((1 - d) * 2.5f, 0, 1));
            gloomTarget = Math.max(gloomTarget, sh.strength * Mth.clamp(1.9f - d, 0, 1));    // gloomy as you come near, darkest under it
        }
        gloom += (gloomTarget - gloom) * 0.015f;
        float drizzleTarget = !raining && (swamp || waterFrac > 0.18f && waterFrac < 0.6f && hash(Math.floorDiv(px, 96), Math.floorDiv(pz, 96), window + 7) < 0.3) ? (swamp ? 0.7f : 0.45f) : 0;
        shower += (showerTarget - shower) * 0.02f;
        drizzle += (drizzleTarget - drizzle) * 0.008f;
        // the game's own rain, lighter than a real storm: drops from the sky, splashes, its sound and grey light
        com.ronald.rivergence.world.WeatherHook.local = Math.min(0.75f, shower * 0.75f);
    }

    /** A shower passing over big water: where, how wide, how heavy just now (it builds and dies away over a few minutes). */
    static final class Shower {
        final float x, z, radius, strength, ground;
        final long seed;
        /** How far through its life (0..1): rainbows come toward the end. */
        final float phase;
        Shower(float x, float z, float radius, float strength, float ground, long seed, float phase) {
            this.x = x; this.z = z; this.radius = radius; this.strength = strength; this.ground = ground; this.seed = seed; this.phase = phase;
        }
        /** The cloud's base: well above the ground under it, never below the usual cloud level. */
        float cloudY() { return Mth.clamp(ground + 38, 150, 236); }
    }

    /** The showers round the player just now (for the clouds, the gloom and the rainbows). */
    static volatile List<Shower> seen = List.of();
    /** How gloomy it is where the player stands: under a shower or close to one. 0..1, eased. */
    static float gloom;
    public static float gloom() { return gloom; }

    static final int SHOWER_CELL = 320, SHOWER_TIME = 4800;

    /** The showers within reach (worked out the same way the server does, so where it rains here it really rains). */
    static java.util.List<Shower> showersInView(ClientLevel level, net.minecraft.world.phys.Vec3 at, float reach) {
        java.util.List<Shower> out = new ArrayList<>();
        if (com.ronald.rivergence.world.WeatherHook.vanillaRaining()) return out;
        for (var s : com.ronald.rivergence.world.Showers.around(level, at.x, at.z, reach))
            out.add(new Shower(s.x(), s.z(), s.radius(), s.strength(), s.ground(), s.seed(), s.phase()));
        return out;
    }

    /** What's round the player: how much of it is water, at what height, and the land's height. */
    private static void survey(ClientLevel level, Player p, RandomSource r) {
        int water = 0, land = 0;
        double wy = 0, ly = 0;
        for (int i = 0; i < 48; i++) {
            int x = Mth.floor(p.getX()) + r.nextInt(81) - 40, z = Mth.floor(p.getZ()) + r.nextInt(81) - 40;
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (level.getFluidState(new BlockPos(x, top - 1, z)).is(FluidTags.WATER)) { water++; wy += top - 1; }
            else { land++; ly += top - 1; }
        }
        waterFrac = water / 48f;
        avgWaterY = water > 0 ? (float) (wy / water) : -999;
        avgLandY = land > 0 ? (float) (ly / land) : -999;
        var biome = level.getBiome(p.blockPosition());
        swamp = biome.is(net.minecraftforge.common.Tags.Biomes.IS_SWAMP);
        sea = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH);
    }

    private static void sprayTick(ClientLevel level, Player p, RandomSource r) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // find the feet of falls nearby: down a few dozen columns a tick, looking for 4 or more blocks of falling water
        for (int i = 0; i < 40 && falls.size() < 32; i++) {
            int x = Mth.floor(p.getX()) + r.nextInt(97) - 48, z = Mth.floor(p.getZ()) + r.nextInt(97) - 48;
            BlockPos foot = footIn(level, x, z, m);
            if (foot == null) continue;
            boolean near = false;
            for (BlockPos f : falls) if (f.distManhattan(foot) < 3) { near = true; break; }
            if (!near) falls.add(foot);
        }
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        for (int i = falls.size() - 1; i >= 0; i--) {
            BlockPos f = falls.get(i);
            if (f.distToCenterSqr(px, py, pz) > 64 * 64 || ticks % 40 == 0 && !isFoot(level, f)) { falls.remove(i); continue; }
            // spray thrown up and out
            int n = 2 + r.nextInt(2);
            for (int k = 0; k < n; k++)
                level.addParticle(ModParticles.SPRAY.get(), f.getX() + 0.2 + r.nextDouble() * 0.6, f.getY() + 1.0, f.getZ() + 0.2 + r.nextDouble() * 0.6,
                        (r.nextDouble() - 0.5) * 0.25, 0.08 + r.nextDouble() * 0.14, (r.nextDouble() - 0.5) * 0.25);
            // and mist where it meets the water below, lying low and spreading out over the pool
            if (r.nextInt(2) == 0) {
                double ang = r.nextDouble() * Math.PI * 2, out = 0.03 + r.nextDouble() * 0.03;
                level.addParticle(ModParticles.MIST.get(), f.getX() + 0.5 + Math.cos(ang) * 0.6, f.getY() + 1.05 + r.nextDouble() * 0.3, f.getZ() + 0.5 + Math.sin(ang) * 0.6,
                        Math.cos(ang) * out, 0.002, Math.sin(ang) * out);
            }
            if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SPLASH, f.getX() + r.nextDouble(), f.getY() + 1.05, f.getZ() + r.nextDouble(), 0, 0, 0);
        }
    }

    /** Down one column from the top: the water where at least 4 blocks of falling water come down onto it. */
    private static BlockPos footIn(ClientLevel level, int x, int z, BlockPos.MutableBlockPos m) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int run = 0;
        for (int y = top; y > top - 64 && y > level.getMinBuildHeight(); y--) {
            FluidState f = level.getFluidState(m.set(x, y, z));
            boolean falling = f.is(FluidTags.WATER) && !f.isSource() && f.hasProperty(BlockStateProperties.FALLING) && f.getValue(BlockStateProperties.FALLING);
            if (falling) { run++; continue; }
            if (run >= 4 && f.is(FluidTags.WATER)) return m.immutable();
            run = 0;
        }
        return null;
    }

    /** Still the foot of a fall: falling water coming down onto it, four blocks of it at least. */
    private static boolean isFoot(ClientLevel level, BlockPos at) {
        for (int k = 1; k <= 4; k++) {
            FluidState up = level.getFluidState(at.above(k));
            if (!up.is(FluidTags.WATER) || up.isSource() || !(up.hasProperty(BlockStateProperties.FALLING) && up.getValue(BlockStateProperties.FALLING))) return false;
        }
        return true;
    }

    @SubscribeEvent
    public static void fog(ViewportEvent.RenderFog e) {
        if (e.getMode() != FogRenderer.FogMode.FOG_TERRAIN || e.getCamera().getFluidInCamera() != FogType.NONE) return;
        float f = Math.max(valleyFog * 0.85f, Math.max(Math.max(shower * 0.5f, gloom() * 0.3f), drizzle * 0.35f));
        if (f < 0.01f) return;
        float far = e.getFarPlaneDistance(), near = e.getNearPlaneDistance();
        float thick = Math.min(far, 34);
        e.setFarPlaneDistance(Mth.lerp(f, far, thick));
        e.setNearPlaneDistance(Mth.lerp(f, near, -8));
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void fogColour(ViewportEvent.ComputeFogColor e) {
        if (e.getCamera().getFluidInCamera() != FogType.NONE) return;
        float f = Math.max(valleyFog * 0.35f, shower * 0.25f);
        if (f >= 0.01f) {
            float g = 0.78f;
            e.setRed(Mth.lerp(f, e.getRed(), g)); e.setGreen(Mth.lerp(f, e.getGreen(), g)); e.setBlue(Mth.lerp(f, e.getBlue(), g + 0.04f));
        }
        // under a rain cloud the air goes grey and dim, as in vanilla's rain
        float gl = gloom();
        if (gl > 0.01f) {
            float lum = (e.getRed() * 0.3f + e.getGreen() * 0.59f + e.getBlue() * 0.11f) * 0.62f;
            float k = gl * 0.8f;
            e.setRed(Mth.lerp(k, e.getRed(), lum)); e.setGreen(Mth.lerp(k, e.getGreen(), lum)); e.setBlue(Mth.lerp(k, e.getBlue(), lum * 1.06f));
        }
    }
}
