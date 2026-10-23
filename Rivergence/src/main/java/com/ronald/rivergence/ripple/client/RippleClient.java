package com.ronald.rivergence.ripple.client;

import com.ronald.rivergence.ripple.RippleConfig;

import com.ronald.rivergence.ripple.sim.RippleField;
import com.ronald.rivergence.ripple.sim.RippleWorld;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides what disturbs the water and feeds it into the wave simulation. Client-only.
 */
@Mod.EventBusSubscriber(modid = com.ronald.rivergence.Rivergence.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class RippleClient {
    private static RippleWorld world;
    private static ClientLevel worldLevel;

    private static IntOpenHashSet previouslyInWater = new IntOpenHashSet();
    private static IntOpenHashSet currentlyInWater = new IntOpenHashSet();

    // Waterfall bookkeeping: the area around the player is scanned one slice per tick.
    private static final int SCAN_RADIUS = 16;
    private static final int SCAN_HEIGHT = 12;
    private static final int MAX_FALLS = 24;
    private static List<BlockPos> falls = new ArrayList<>();
    private static List<BlockPos> scanning = new ArrayList<>();
    private static int slice = 0;

    private static int sprayBudget;

    private static final RippleField.Terrain TERRAIN = (blockX, blockZ, surfaceY) -> {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        int by = Mth.floor(surfaceY - 0.01);
        BlockPos pos = new BlockPos(blockX, by, blockZ);
        FluidState fluid = level.getFluidState(pos);
        if (!fluid.is(FluidTags.WATER) || level.getFluidState(pos.above()).is(FluidTags.WATER)) {
            return false;
        }
        return Math.abs(by + fluid.getHeight(level, pos) - surfaceY) < 0.12;
    };

    /** The active ripple simulation, or null when there is nothing in the world yet. */
    public static RippleWorld world() {
        return world;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            reset();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        if (world == null || worldLevel != level) {
            reset();
            world = new RippleWorld(RippleConfig.MAX_PATCHES.get(), TERRAIN);
            worldLevel = level;
        }

        sprayBudget = 36;
        handleEntities(level, player);
        if (RippleConfig.WATERFALLS.get()) {
            handleWaterfalls(level, player);
        }
        world.tick();
    }

    private static void reset() {
        if (world != null) {
            world.clear();
        }
        world = null;
        worldLevel = null;
        previouslyInWater.clear();
        currentlyInWater.clear();
        falls.clear();
        scanning.clear();
        slice = 0;
    }

    // ------------------------------------------------------------------ entities

    private static void handleEntities(ClientLevel level, Player player) {
        double range = RippleConfig.RANGE.get();
        double rangeSqr = range * range;
        boolean entering = RippleConfig.ENTERING_WATER.get();
        boolean spray = RippleConfig.SPRAY.get();
        boolean wakes = RippleConfig.WAKES.get();
        boolean elytra = RippleConfig.ELYTRA.get();

        currentlyInWater.clear();

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator() || entity.isRemoved() || entity.distanceToSqr(player) > rangeSqr) {
                continue;
            }

            // Position change since last tick works for the local player and remote entities alike.
            double dx = entity.getX() - entity.xo;
            double dy = entity.getY() - entity.yo;
            double dz = entity.getZ() - entity.zo;
            double horizSqr = dx * dx + dz * dz;
            double speedSqr = horizSqr + dy * dy;
            int id = entity.getId();
            int tick = entity.tickCount + id;

            if (entity.isInWater()) {
                currentlyInWater.add(id);
                double surface = surfaceY(level, entity.getX(), entity.getY(), entity.getZ());
                if (Double.isNaN(surface)) {
                    continue;
                }
                float size = sizeOf(entity);

                if (!previouslyInWater.contains(id)) {
                    // Just dropped into the water.
                    if (entity.tickCount > 3 && speedSqr > 0.0009) {
                        float speed = (float) Math.sqrt(speedSqr);
                        if (entering) {
                            world.splash(entity.getX(), surface, entity.getZ(), size, speed);
                        }
                        if (spray && speed >= 0.12F) {
                            int count = Math.round(size * size * speed * 30F + size * speed * 4F);
                            spray(level, entity.getX(), surface, entity.getZ(), count, size, speed);
                        }
                    }
                } else if (wakes) {
                    AABB box = entity.getBoundingBox();
                    boolean atSurface = box.minY <= surface + 0.1 && box.maxY >= surface - 0.1;
                    if (atSurface && horizSqr > 0.0004 && horizSqr < 36.0) {
                        float strength = Mth.clamp(0.6F + size * 0.5F, 0.6F, 2.0F);
                        world.wake(entity.xo, entity.zo, entity.getX(), entity.getZ(), surface,
                                Math.max(0.3F, size), strength);
                    }
                    if (entity instanceof Boat boat && tick % 5 == 0) {
                        paddle(level, boat, surface);
                    }
                }
            } else if (elytra && entity instanceof LivingEntity living && living.isFallFlying()
                    && horizSqr > 0.09 && horizSqr < 36.0) {
                double surface = surfaceBelow(level, entity.getX(), entity.getY(), entity.getZ());
                if (!Double.isNaN(surface)) {
                    double height = entity.getY() - surface;
                    if (height > 0.0 && height <= 3.5) {
                        float closeness = (float) (1.0 - height / 3.5);
                        world.wake(entity.xo, entity.zo, entity.getX(), entity.getZ(), surface,
                                0.5F, 0.4F + closeness * 1.4F);
                        if (RippleConfig.SPRAY.get() && height < 1.3 && tick % 2 == 0) {
                            spray(level, entity.getX(), surface, entity.getZ(), 2, 0.4F, 0.3F);
                        }
                    }
                }
            }
        }

        IntOpenHashSet swap = previouslyInWater;
        previouslyInWater = currentlyInWater;
        currentlyInWater = swap;
    }

    /** Rough edge length of the object, so bigger things make bigger splashes. */
    private static float sizeOf(Entity entity) {
        double volume = entity.getBbWidth() * entity.getBbWidth() * entity.getBbHeight();
        return (float) Math.min(3.0, Math.cbrt(volume));
    }

    private static void paddle(ClientLevel level, Boat boat, double surface) {
        double yaw = Math.toRadians(boat.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double px = Math.cos(yaw);
        double pz = Math.sin(yaw);
        for (int side = 0; side < 2; side++) {
            if (!boat.getPaddleState(side)) {
                continue;
            }
            double lateral = side == 0 ? 0.65 : -0.65;
            double x = boat.getX() + px * lateral + fx * 0.1;
            double z = boat.getZ() + pz * lateral + fz * 0.1;
            world.drip(x, surface, z, 1.0F);
            if (RippleConfig.SPRAY.get()) {
                spray(level, x, surface, z, 2, 0.25F, 0.2F);
            }
        }
    }

    // --------------------------------------------------------------------- spray

    /** Throws up droplets of water. Bigger and faster objects throw more, and higher. */
    private static void spray(ClientLevel level, double x, double y, double z, int count, float size, float speed) {
        int n = Math.min(Math.min(count, 36), sprayBudget);
        if (n <= 0) {
            return;
        }
        sprayBudget -= n;
        var engine = Minecraft.getInstance().particleEngine;
        var random = level.random;
        for (int i = 0; i < n; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double reach = random.nextDouble() * size * 0.35;
            Particle p = engine.createParticle(ParticleTypes.SPLASH,
                    x + Math.cos(angle) * reach, y + 0.08, z + Math.sin(angle) * reach, 0.0, 0.0, 0.0);
            if (p != null) {
                double out = 0.02 + random.nextDouble() * 0.05 * (0.5 + size);
                double up = 0.12 + speed * 0.4 + random.nextDouble() * 0.12 * (1.0 + size);
                p.setParticleSpeed(Math.cos(angle) * out, up, Math.sin(angle) * out);
            }
        }
    }

    // ------------------------------------------------------------------- surfaces

    /** Top of the water column at the entity's position, or NaN if it is not in water. */
    private static double surfaceY(ClientLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y + 0.1, z);
        for (int i = 0; i < 4; i++) {
            FluidState fluid = level.getFluidState(pos);
            if (!fluid.is(FluidTags.WATER)) {
                return Double.NaN;
            }
            if (!level.getFluidState(pos.above()).is(FluidTags.WATER)) {
                return pos.getY() + fluid.getHeight(level, pos);
            }
            pos = pos.above();
        }
        return Double.NaN;
    }

    /** Water surface below a flying entity, or NaN if something solid or nothing is there. */
    private static double surfaceBelow(ClientLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        for (int i = 0; i <= 5; i++) {
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)) {
                if (!level.getFluidState(pos.above()).is(FluidTags.WATER)) {
                    return pos.getY() + fluid.getHeight(level, pos);
                }
                return Double.NaN;
            }
            if (!level.getBlockState(pos).isAir()) {
                return Double.NaN;
            }
            pos = pos.below();
        }
        return Double.NaN;
    }

    // ----------------------------------------------------------------- waterfalls

    private static void handleWaterfalls(ClientLevel level, Player player) {
        BlockPos center = player.blockPosition();

        // Scan one slice of the surrounding area each tick (about 800 lookups).
        int x = center.getX() - SCAN_RADIUS + slice;
        for (int z = center.getZ() - SCAN_RADIUS; z <= center.getZ() + SCAN_RADIUS; z++) {
            for (int y = center.getY() - SCAN_HEIGHT; y <= center.getY() + SCAN_HEIGHT; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (isFallingWater(level.getFluidState(pos)) && isWaterfallBase(level, pos)) {
                    scanning.add(pos);
                }
            }
        }
        slice++;
        if (slice > SCAN_RADIUS * 2) {
            slice = 0;
            if (scanning.size() > MAX_FALLS) {
                scanning.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
                scanning = new ArrayList<>(scanning.subList(0, MAX_FALLS));
            }
            falls = scanning;
            scanning = new ArrayList<>();
        }

        // Ripple only now and then, spread across the known waterfalls.
        if (falls.isEmpty()) {
            return;
        }
        int tries = Math.min(falls.size(), 3);
        for (int i = 0; i < tries; i++) {
            if (level.random.nextInt(14) != 0) {
                continue;
            }
            BlockPos base = falls.get(level.random.nextInt(falls.size()));
            if (!isFallingWater(level.getFluidState(base)) || !isWaterfallBase(level, base)) {
                continue;
            }
            BlockPos below = base.below();
            double surface = below.getY() + level.getFluidState(below).getHeight(level, below);
            double ox = (level.random.nextDouble() - 0.5) * 0.5;
            double oz = (level.random.nextDouble() - 0.5) * 0.5;
            world.drip(base.getX() + 0.5 + ox, surface, base.getZ() + 0.5 + oz,
                    0.7F + level.random.nextFloat() * 0.5F);
        }
    }

    private static boolean isFallingWater(FluidState fluid) {
        return fluid.is(FluidTags.WATER)
                && fluid.hasProperty(FlowingFluid.FALLING)
                && fluid.getValue(FlowingFluid.FALLING);
    }

    /** Lowest block of a falling column that lands in a pool of water. */
    private static boolean isWaterfallBase(ClientLevel level, BlockPos pos) {
        if (!isFallingWater(level.getFluidState(pos.above()))) {
            return false;
        }
        FluidState below = level.getFluidState(pos.below());
        return below.is(FluidTags.WATER) && !isFallingWater(below);
    }
}
