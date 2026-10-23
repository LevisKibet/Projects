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
    private static float dawn(long time) {
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
            valleyFog = shower = drizzle = 0; falls.clear(); return;
        }
        RandomSource r = level.random;
        ticks++;
        if (ticks % 20 == 0) survey(level, p, r);

        float dawn = dawn(level.getDayTime());
        boolean raining = level.isRaining();
        // mist over still and slow water: thickest at dawn, some in the rain, none in the heat of the day
        float mist = Math.max(dawn, raining ? 0.35f : 0) * (sea ? 0.3f : 1);
        if (mist > 0.02f && waterFrac > 0.01f) {
            int tries = Math.round(mist * 7);
            for (int i = 0; i < tries; i++) {
                int x = Mth.floor(p.getX()) + r.nextInt(57) - 28, z = Mth.floor(p.getZ()) + r.nextInt(57) - 28;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos w = new BlockPos(x, top - 1, z);
                FluidState f = level.getFluidState(w);
                if (!f.is(FluidTags.WATER) || !f.isSource() || top - 1 < 62 || !level.getBlockState(w.above()).isAir()) continue;
                level.addParticle(ModParticles.MIST.get(), x + r.nextDouble(), top + 0.1 + r.nextDouble() * 0.9, z + r.nextDouble(),
                        (r.nextDouble() - 0.5) * 0.012, 0.0015, (r.nextDouble() - 0.5) * 0.012);
            }
        }
        // fog settling in a river valley at sunrise: low by the water with higher ground round it
        boolean valley = waterFrac > 0.03f && p.getY() < avgWaterY + 18 && avgLandY > avgWaterY + 5 && !sea;
        float fogTarget = dawn * (valley ? 1 : waterFrac > 0.2f ? 0.45f : 0);
        valleyFog += (fogTarget - valleyFog) * 0.01f;

        // spray where falls hit the water
        sprayTick(level, p, r);

        // passing showers over big water, drizzle over wetlands (when it isn't raining anyway)
        int px = Mth.floor(p.getX()), pz = Mth.floor(p.getZ());
        long window = level.getGameTime() / 3600;
        boolean cell = hash(Math.floorDiv(px, 160), Math.floorDiv(pz, 160), window) < 0.22;
        float showerTarget = !raining && waterFrac > 0.5f && cell ? 1 : 0;
        float drizzleTarget = !raining && (swamp || waterFrac > 0.18f && waterFrac < 0.6f && hash(Math.floorDiv(px, 96), Math.floorDiv(pz, 96), window + 7) < 0.3) ? (swamp ? 0.7f : 0.45f) : 0;
        shower += (showerTarget - shower) * 0.006f;
        drizzle += (drizzleTarget - drizzle) * 0.008f;
        float wet = Math.max(shower, drizzle * 0.6f);
        if (wet > 0.02f) {
            int drops = Math.round(wet * (shower > drizzle ? 14 : 6));
            for (int i = 0; i < drops; i++) {
                double x = p.getX() + (r.nextDouble() - 0.5) * 30, z = p.getZ() + (r.nextDouble() - 0.5) * 30;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
                double y = Math.max(top + 1, p.getY() + 6 + r.nextDouble() * 6);
                if (y - top > 24) y = top + 10 + r.nextDouble() * 8;
                level.addParticle(ParticleTypes.FALLING_WATER, x, y, z, 0, 0, 0);
            }
            if (ticks % 30 == 0 && r.nextFloat() < wet)
                level.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, 0.08f + 0.12f * wet, 0.9f + r.nextFloat() * 0.2f, false);
        }
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
        // find the feet of falls nearby
        for (int i = 0; i < 12 && falls.size() < 24; i++) {
            int x = Mth.floor(p.getX()) + r.nextInt(41) - 20, y = Mth.floor(p.getY()) + r.nextInt(25) - 12, z = Mth.floor(p.getZ()) + r.nextInt(41) - 20;
            if (!isFoot(level, m.set(x, y, z))) continue;
            boolean near = false;
            for (BlockPos f : falls) if (f.distManhattan(m) < 3) { near = true; break; }
            if (!near) falls.add(m.immutable());
        }
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        for (int i = falls.size() - 1; i >= 0; i--) {
            BlockPos f = falls.get(i);
            if (f.distToCenterSqr(px, py, pz) > 40 * 40 || ticks % 40 == 0 && !isFoot(level, f)) { falls.remove(i); continue; }
            if (f.distToCenterSqr(px, py, pz) > 28 * 28) continue;
            int n = 1 + r.nextInt(2);
            for (int k = 0; k < n; k++)
                level.addParticle(ModParticles.SPRAY.get(), f.getX() + 0.2 + r.nextDouble() * 0.6, f.getY() + 1.0, f.getZ() + 0.2 + r.nextDouble() * 0.6,
                        (r.nextDouble() - 0.5) * 0.22, 0.08 + r.nextDouble() * 0.12, (r.nextDouble() - 0.5) * 0.22);
            if (r.nextInt(5) == 0)
                level.addParticle(ModParticles.MIST.get(), f.getX() + r.nextDouble(), f.getY() + 1.2 + r.nextDouble(), f.getZ() + r.nextDouble(),
                        (r.nextDouble() - 0.5) * 0.03, 0.01, (r.nextDouble() - 0.5) * 0.03);
            if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.SPLASH, f.getX() + r.nextDouble(), f.getY() + 1.05, f.getZ() + r.nextDouble(), 0, 0, 0);
        }
    }

    /** The water at the foot of a fall: falling water coming down onto it, at least three blocks of fall above. */
    private static boolean isFoot(ClientLevel level, BlockPos at) {
        FluidState here = level.getFluidState(at);
        if (!here.is(FluidTags.WATER)) return false;
        if (here.hasProperty(BlockStateProperties.FALLING) && here.getValue(BlockStateProperties.FALLING)) return false;
        for (int k = 1; k <= 3; k++) {
            FluidState up = level.getFluidState(at.above(k));
            if (!up.is(FluidTags.WATER) || up.isSource() || !(up.hasProperty(BlockStateProperties.FALLING) && up.getValue(BlockStateProperties.FALLING))) return false;
        }
        return true;
    }

    @SubscribeEvent
    public static void fog(ViewportEvent.RenderFog e) {
        if (e.getMode() != FogRenderer.FogMode.FOG_TERRAIN || e.getCamera().getFluidInCamera() != FogType.NONE) return;
        float f = Math.max(valleyFog * 0.85f, Math.max(shower * 0.5f, drizzle * 0.35f));
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
        if (f < 0.01f) return;
        float g = 0.78f;
        e.setRed(Mth.lerp(f, e.getRed(), g)); e.setGreen(Mth.lerp(f, e.getGreen(), g)); e.setBlue(Mth.lerp(f, e.getBlue(), g + 0.04f));
    }
}
