package com.ronald.rivergence.world;

import com.ronald.rivergence.RivergenceConfig;
import com.ronald.rivergence.plan.Planner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * Keeps the water near players lived in, the way natural spawning does for the sea:
 *   rivers and streams wide enough to swim in: salmon and cod only;
 *   still water (lakes, ponds, springs, big plunge pools): axolotls, with tropical fish where it's warm and
 *   salmon and cod elsewhere.
 * A few at a time, never more than a handful in one stretch of water; they come and go like any other fish.
 */
public final class FaunaSpawner {
    private FaunaSpawner() {}

    public static void init() { MinecraftForge.EVENT_BUS.addListener(FaunaSpawner::tick); }

    private static void tick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % 50 != 0 || !RivergenceConfig.fauna()) return;
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING)) return;
        Planner planner = ((RiverAccess) (Object) level.getChunkSource().randomState()).rivergence$planner();
        if (planner == null) return;
        RandomSource r = level.random;
        Planner.Column c = new Planner.Column();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            for (int tries = 0; tries < 3; tries++) {
                double ang = r.nextDouble() * Math.PI * 2, dist = 20 + r.nextDouble() * 44;
                int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist), z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
                if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
                planner.column(x, z, c, false);
                if (c.plan == null || !c.wet() || c.water <= 62) continue;
                trySpawn(level, c, x, z, r);
            }
        }
    }

    /** For testing: fill the water round a spot as if a player had been there a while. */
    public static void populate(ServerLevel level, int cx, int cz, int radius, int tries) {
        Planner planner = ((RiverAccess) (Object) level.getChunkSource().randomState()).rivergence$planner();
        Planner.Column c = new Planner.Column();
        RandomSource r = level.random;
        for (int i = 0; i < tries; i++) {
            int x = cx + r.nextInt(2 * radius) - radius, z = cz + r.nextInt(2 * radius) - radius;
            planner.column(x, z, c, true);
            if (c.wet() && c.water > 62) trySpawn(level, c, x, z, r);
        }
    }

    /** Frogs in a wetland: a couple at a time, at the edge of a pool (their colour follows the climate, as in vanilla). */
    private static void frogs(ServerLevel level, Planner.Column c, int x, int z, RandomSource r) {
        BlockPos at = new BlockPos(x, c.water, z);
        if (!level.getFluidState(at).is(FluidTags.WATER) || !level.getBlockState(at.above()).isAir()) return;
        AABB near = new AABB(x - 16, c.water - 4, z - 16, x + 16, c.water + 4, z + 16);
        if (level.getEntitiesOfClass(net.minecraft.world.entity.animal.frog.Frog.class, near).size() >= 4) return;
        int n = 1 + r.nextInt(2);
        for (int i = 0; i < n; i++) {
            Mob frog = EntityType.FROG.create(level);
            if (frog == null) return;
            frog.moveTo(x + 0.5 + r.nextInt(3) - 1, c.water + 0.9, z + 0.5 + r.nextInt(3) - 1, r.nextFloat() * 360, 0);
            frog.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.NATURAL, null, null);
            level.addFreshEntityWithPassengers(frog);
        }
    }

    private static boolean padsNear(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
            if (level.getBlockState(m.set(x + dx, y, z + dz)).is(net.minecraft.world.level.block.Blocks.LILY_PAD)) return true;
        return false;
    }

    private static void trySpawn(ServerLevel level, Planner.Column c, int x, int z, RandomSource r) {
        if (c.wetland && r.nextFloat() < 0.6f) { frogs(level, c, x, z, r); return; }
        if ((c.lake || c.beaver || c.poolQ < 1 || c.oasisQ < 1) && r.nextFloat() < 0.35f && padsNear(level, x, c.water + 1, z)) { frogs(level, c, x, z, r); return; }
        boolean still = c.lake || c.wetland || c.springQ < 1 || c.oasisQ < 1 || (c.poolQ < 1 && c.pool.r >= 3.5);
        boolean river = c.channel && !still;
        if (river && c.hw < 1.6) return;                                   // too narrow to swim in
        if (!still && !river) return;
        int top = c.water;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, top, z);
        int depth = 0;
        while (depth < 12 && level.getFluidState(m.set(x, top - depth, z)).is(FluidTags.WATER)) depth++;
        if (depth < (still ? 2 : 1)) return;
        AABB near = new AABB(x - 20, top - 10, z - 20, x + 20, top + 4, z + 20);
        int cap = still ? (c.lake ? 7 : 4) : 4;
        if (level.getEntitiesOfClass(Mob.class, near, mob -> mob instanceof WaterAnimal || mob instanceof Axolotl).size() >= cap) return;

        var biome = level.getBiome(m.set(x, top, z));
        boolean warm = biome.value().getBaseTemperature() >= 0.8f;
        boolean cold = biome.value().getBaseTemperature() < 0.3f;
        EntityType<? extends Mob> type;
        int group;
        if (river) {
            type = cold || r.nextFloat() < 0.6f ? EntityType.SALMON : EntityType.COD;
            group = 1 + r.nextInt(c.hw >= 4 ? 4 : 2);
        } else {
            float f = r.nextFloat();
            if (!cold && f < 0.3f) { type = EntityType.AXOLOTL; group = 1 + r.nextInt(2); }
            else if (warm && f < 0.75f) { type = EntityType.TROPICAL_FISH; group = 2 + r.nextInt(4); }
            else { type = cold || r.nextBoolean() ? EntityType.SALMON : EntityType.COD; group = 1 + r.nextInt(3); }
        }
        for (int i = 0; i < group; i++) {
            int y = top - r.nextInt(Math.max(1, depth - 1));
            BlockPos at = new BlockPos(x + r.nextInt(3) - 1, y, z + r.nextInt(3) - 1);
            if (!level.getFluidState(at).is(FluidTags.WATER) || !level.getFluidState(at).isSource()) continue;
            Mob mob = type.create(level);
            if (mob == null) continue;
            mob.moveTo(at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, r.nextFloat() * 360, 0);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.NATURAL, null, null);
            level.addFreshEntityWithPassengers(mob);
        }
    }
}
