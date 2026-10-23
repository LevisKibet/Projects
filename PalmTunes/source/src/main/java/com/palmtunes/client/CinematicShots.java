package com.palmtunes.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The camera moves of the idle cinematic: a small repertoire of shots, each of which describes where the
 * camera is and what it looks at as a function of time. The director ({@link Cinematic}) chooses shots and
 * flies the camera smoothly from one to the next.
 */
final class CinematicShots {
    private CinematicShots() {
    }

    /** One frame of camera: where it is, what it looks at, how zoomed it is, and whether one subject is the focus. */
    record Pose(Vec3 pos, Vec3 aim, float fovScale, boolean focus) {
        Pose lerp(Pose to, float t) {
            return new Pose(pos.lerp(to.pos, t), aim.lerp(to.aim, t), Mth.lerp(t, fovScale, to.fovScale),
                    t < 0.5f ? focus : to.focus);
        }
    }

    abstract static class Shot {
        final float duration;

        Shot(float duration) {
            this.duration = duration;
        }

        abstract String name();

        /** Camera for time {@code t} seconds into the shot. {@code dt} is the frame time (for shots that follow things). */
        abstract Pose pose(Level level, LocalPlayer player, float t, float dt, float partialTick);

        /** False once the shot cannot continue (its subject is gone). */
        boolean valid(Level level) {
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Choosing shots
    // ------------------------------------------------------------------------------------------

    /** How long a subject (a flower, a mob) or a kind of shot stays off the list once it has been filmed. */
    private static final long COOLDOWN_MS = 3 * 60 * 1000L;

    private record Used(Vec3 where, long at) {
    }

    private static final List<Used> RECENT_POIS = new ArrayList<>();
    private static final java.util.Map<java.util.UUID, Long> RECENT_MOBS = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> KIND_LAST = new java.util.HashMap<>();
    private static String lastShot = "";
    private static boolean lastWasPlayerCentred;

    static void reset() {
        RECENT_POIS.clear();
        RECENT_MOBS.clear();
        KIND_LAST.clear();
        lastShot = "";
        lastWasPlayerCentred = false;
    }

    private static boolean kindCooling(String name, long now) {
        Long t = KIND_LAST.get(name);
        return t != null && now - t < COOLDOWN_MS;
    }

    /**
     * Picks the next shot. Shots last 1-2x {@code minSeconds}. Nothing is filmed twice within three minutes
     * (a subject, or a kind of shot), and after a shot of the player the next one looks at the world instead.
     */
    static Shot choose(Level level, LocalPlayer player, RandomSource rng, float minSeconds) {
        long now = System.currentTimeMillis();
        float dur = minSeconds * (1f + rng.nextFloat());
        int dir = rng.nextBoolean() ? 1 : -1;

        record Option(Shot shot, float weight, boolean playerCentred) {
        }
        List<Option> options = new ArrayList<>();

        Shot poi = poiShot(level, player, rng, dur);
        if (poi != null) {
            options.add(new Option(poi, 4f, false));
        }
        Shot follow = followShot(level, player, rng, dur);
        if (follow != null) {
            options.add(new Option(follow, 4f, false));
        }
        boolean haveSubject = !options.isEmpty();
        if (!kindCooling("truck", now)) {
            options.add(new Option(new TruckShot(dur, dir), 2.2f, false));
        }
        Shot tele = teleShot(level, player, rng, dur);
        if (tele != null && !kindCooling("tele", now)) {
            options.add(new Option(tele, 1.6f, true));
        }
        Shot overhead = overheadShot(level, player, rng, dur);
        if (overhead != null && !kindCooling("overhead", now)) {
            options.add(new Option(overhead, 1.4f, true));
        }
        if (!kindCooling("crane", now)) {
            options.add(new Option(new CraneShot(dur), 1.2f, true));
        }
        if (!kindCooling("portrait", now)) {
            options.add(new Option(new PortraitShot(dur, dir), 1.4f, true));
        }

        // If everything is cooling down, fall back to the world shot that was filmed longest ago.
        if (options.isEmpty()) {
            options.add(new Option(new TruckShot(dur, dir), 1f, false));
        }

        float total = 0;
        float[] w = new float[options.size()];
        for (int i = 0; i < w.length; i++) {
            Option o = options.get(i);
            float x = o.weight;
            if (o.shot.name().equals(lastShot)) {
                x = 0f;
            }
            if (o.playerCentred) {
                if (lastWasPlayerCentred) {
                    x = 0f;                      // never two shots of the player in a row
                } else if (haveSubject) {
                    x *= 0.35f;                  // subjects first: less coming back to the player
                }
            }
            w[i] = x;
            total += x;
        }
        Option chosen = options.get(0);
        if (total > 0f) {
            float roll = rng.nextFloat() * total;
            for (int i = 0; i < w.length; i++) {
                roll -= w[i];
                if (roll <= 0f && w[i] > 0f) {
                    chosen = options.get(i);
                    break;
                }
            }
        }
        lastShot = chosen.shot.name();
        lastWasPlayerCentred = chosen.playerCentred;
        KIND_LAST.put(chosen.shot.name(), now);
        if (chosen.shot instanceof FollowShot f) {
            RECENT_MOBS.put(f.mobId(), now);
        }
        return chosen.shot;
    }

    // ---- points of interest ---------------------------------------------------------------------

    /** How interesting a block is to look at: flowers, lights and things somebody put there. 0 = not at all. */
    static int blockScore(BlockState s) {
        if (s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN)) return 9;
        if (s.is(BlockTags.CAMPFIRES)) return 8;
        if (s.is(BlockTags.CANDLES) || s.is(BlockTags.CANDLE_CAKES)) return 7;
        if (s.is(BlockTags.FLOWER_POTS)) return 7;
        if (s.is(BlockTags.FLOWERS)) return 5;
        if (s.is(Blocks.BELL) || s.is(Blocks.JUKEBOX) || s.is(Blocks.NOTE_BLOCK) || s.is(Blocks.ENCHANTING_TABLE)
                || s.is(Blocks.BREWING_STAND) || s.is(Blocks.LECTERN) || s.is(Blocks.CAKE)) return 6;
        if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.SOUL_TORCH) || s.is(Blocks.SOUL_WALL_TORCH)
                || s.is(Blocks.END_ROD)) return 4;
        if (s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER)
                || s.is(Blocks.CHEST) || s.is(Blocks.BARREL) || s.is(BlockTags.ANVIL) || s.is(Blocks.CAULDRON)) return 3;
        if (s.is(BlockTags.BEDS) || s.is(BlockTags.BANNERS)) return 3;
        return 0;
    }

    private record Candidate(Vec3 center, int score, boolean tiny) {
    }

    private static Shot poiShot(Level level, LocalPlayer player, RandomSource rng, float dur) {
        BlockPos base = player.blockPosition();
        List<Candidate> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int r = 12;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                for (int dy = -4; dy <= 6; dy++) {
                    pos.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    int score = blockScore(state);
                    if (score > 0 && !recentlyUsed(Vec3.atCenterOf(pos))) {
                        // Flowers and lights are small: get closer to them.
                        found.add(new Candidate(Vec3.atCenterOf(pos).add(0, -0.1, 0), score, score >= 4 && score != 6));
                    }
                }
            }
        }
        AABB box = player.getBoundingBox().inflate(r);
        for (Entity e : level.getEntities(player, box)) {
            if (e instanceof ItemFrame || e instanceof ArmorStand || e instanceof Painting || e instanceof ItemEntity) {
                Vec3 c = e.getBoundingBox().getCenter();
                if (!recentlyUsed(c)) {
                    found.add(new Candidate(c, 6, e instanceof ItemEntity));
                }
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        Candidate chosen = null;
        float radius = 3f;
        for (int attempt = 0; attempt < 8 && !found.isEmpty() && chosen == null; attempt++) {
            int total = 0;
            for (Candidate c : found) {
                total += c.score;
            }
            int roll = rng.nextInt(Math.max(1, total));
            Candidate pick = found.get(found.size() - 1);
            for (Candidate c : found) {
                roll -= c.score;
                if (roll < 0) {
                    pick = c;
                    break;
                }
            }
            found.remove(pick);
            float rad = pick.tiny ? 1.9f : 3.0f;
            // Skip anything in a hole, a nook or a tight room: the camera needs somewhere to go.
            if (openness(level, pick.center, rad, player) >= 0.5f) {
                chosen = pick;
                radius = rad;
            }
        }
        if (chosen == null) {
            return null;
        }
        RECENT_POIS.add(new Used(chosen.center, System.currentTimeMillis()));
        return new OrbitShot(level, chosen.center, radius, dur, rng.nextBoolean() ? 1 : -1, rng);
    }

    /**
     * Fraction (0..1) of the directions around {@code c}, at camera height, in which a camera could stand
     * {@code radius} blocks away with a clear view of the point; also requires some room overhead.
     */
    static float openness(Level level, Vec3 c, float radius, Entity ctx) {
        if (headroom(level, c.add(0, 0.3, 0), 3.0, ctx) < 2.0) {
            return 0f;
        }
        int open = 0;
        final int n = 12;
        for (int i = 0; i < n; i++) {
            float a = i * Mth.TWO_PI / n;
            for (double h : new double[]{0.7, 1.4}) {
                Vec3 ring = c.add(Mth.cos(a) * radius, h, Mth.sin(a) * radius);
                HitResult hit = level.clip(new ClipContext(c.add(0, 0.2, 0), ring, ClipContext.Block.VISUAL,
                        ClipContext.Fluid.NONE, ctx));
                if (hit.getType() == HitResult.Type.MISS) {
                    open++;
                    break;
                }
            }
        }
        return open / (float) n;
    }

    private static boolean recentlyUsed(Vec3 p) {
        long now = System.currentTimeMillis();
        RECENT_POIS.removeIf(u -> now - u.at > COOLDOWN_MS);
        for (Used u : RECENT_POIS) {
            if (u.where.distanceToSqr(p) < 25.0) {
                return true;
            }
        }
        return false;
    }

    // ---- living subjects ---------------------------------------------------------------------------

    private static Shot followShot(Level level, LocalPlayer player, RandomSource rng, float dur) {
        long now = System.currentTimeMillis();
        RECENT_MOBS.values().removeIf(t -> now - t > COOLDOWN_MS);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(22),
                m -> !(m instanceof Enemy) && m.isAlive() && !m.isInvisible() && !m.isPassenger()
                        && !RECENT_MOBS.containsKey(m.getUUID()));
        if (mobs.isEmpty()) {
            return null;
        }
        Mob pick = mobs.get(rng.nextInt(mobs.size()));
        if (openness(level, pick.position().add(0, pick.getBbHeight() * 0.5, 0), 3f, player) < 0.4f) {
            return null;
        }
        return new FollowShot(pick, dur, rng.nextBoolean() ? 1 : -1);
    }

    // ---- sky / overhead (only if there is room) ---------------------------------------------------

    /** How far straight up from {@code from} is free of blocks, capped at {@code max}. */
    static double headroom(Level level, Vec3 from, double max, Entity ctx) {
        HitResult hit = level.clip(new ClipContext(from, from.add(0, max, 0), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, ctx));
        return hit.getType() == HitResult.Type.MISS ? max : hit.getLocation().y - from.y;
    }

    private static Shot overheadShot(Level level, LocalPlayer player, RandomSource rng, float dur) {
        double room = headroom(level, player.position().add(0, 1, 0), 20, player);
        return room >= 7 ? new OverheadShot(dur, rng.nextBoolean() ? 1 : -1) : null;
    }

    /** A long lens from far off: the player small in the landscape. Needs a clear line of sight. */
    private static Shot teleShot(Level level, LocalPlayer player, RandomSource rng, float dur) {
        Vec3 target = player.position().add(0, 1.0, 0);
        float start = rng.nextFloat() * Mth.TWO_PI;
        for (int i = 0; i < 8; i++) {
            float a = start + i * Mth.TWO_PI / 8f;
            for (double dist : new double[]{16, 12}) {
                Vec3 cam = target.add(Mth.cos(a) * dist, 5.0, Mth.sin(a) * dist);
                HitResult hit = level.clip(new ClipContext(cam, target.add(0, 0.6, 0), ClipContext.Block.VISUAL,
                        ClipContext.Fluid.NONE, player));
                if (hit.getType() == HitResult.Type.MISS && level.getBlockState(net.minecraft.core.BlockPos.containing(cam)).isAir()
                        && openness(level, cam, 1.2f, player) >= 0.6f) {
                    return new TeleShot(dur, cam);
                }
            }
        }
        return null;
    }

    /** Pulls {@code desired} towards {@code aim} so the camera never ends up inside a wall. */
    static Vec3 keepClear(Level level, Vec3 aim, Vec3 desired, Entity ctx) {
        HitResult hit = level.clip(new ClipContext(aim, desired, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, ctx));
        if (hit.getType() == HitResult.Type.MISS) {
            return desired;
        }
        Vec3 along = desired.subtract(aim);
        double length = along.length();
        if (length < 1.0e-4) {
            return desired;
        }
        double usable = Math.max(0.9, hit.getLocation().distanceTo(aim) - 0.35);
        return aim.add(along.scale(Math.min(1.0, usable / length)));
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // ------------------------------------------------------------------------------------------
    // The shots
    // ------------------------------------------------------------------------------------------

    /** Slowly circles one small thing (a flower, a lantern, something somebody placed), drifting closer. */
    static final class OrbitShot extends Shot {
        private final Vec3 center;
        private final float radius;
        private final float startAngle;
        private final int dir;
        private final float height;

        OrbitShot(Level level, Vec3 center, float radius, float duration, int dir, RandomSource rng) {
            super(duration);
            this.center = center;
            this.radius = radius;
            this.dir = dir;
            this.height = 0.55f + rng.nextFloat() * 0.8f;
            // Start on the side with the most room to move.
            float best = 0f;
            double bestRoom = -1;
            for (int i = 0; i < 12; i++) {
                float a = i * Mth.TWO_PI / 12f;
                Vec3 ring = center.add(Mth.cos(a) * radius, height, Mth.sin(a) * radius);
                HitResult hit = level.clip(new ClipContext(center, ring, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                        net.minecraft.client.Minecraft.getInstance().player));
                double room = hit.getType() == HitResult.Type.MISS ? radius : hit.getLocation().distanceTo(center);
                if (room > bestRoom) {
                    bestRoom = room;
                    best = a;
                }
            }
            this.startAngle = best;
        }

        @Override
        String name() {
            return "orbit";
        }

        @Override
        Pose pose(Level level, LocalPlayer player, float t, float dt, float pt) {
            float a = startAngle + dir * 1.5f * (t / duration);
            float r = radius * (1f - 0.25f * (t / duration));            // a slow push in
            Vec3 pos = center.add(Mth.cos(a) * r, height + 0.15f * Mth.sin(t * 0.7f), Mth.sin(a) * r);
            pos = keepClear(level, center, pos, player);
            return new Pose(pos, center, 0.62f, true);
        }
    }

    /** Trails behind a friendly or neutral mob. The animal can turn sharply; the camera never does. */
    static final class FollowShot extends Shot {
        private final Mob mob;
        private final int side;
        private Vec3 pos;
        private Vec3 aim;
        private float heading;
        private boolean init;

        FollowShot(Mob mob, float duration, int side) {
            super(duration);
            this.mob = mob;
            this.side = side;
        }

        java.util.UUID mobId() {
            return mob.getUUID();
        }

        @Override
        String name() {
            return "follow";
        }

        @Override
        boolean valid(Level level) {
            LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
            return mob.isAlive() && !mob.isRemoved() && p != null && mob.distanceToSqr(p) < 40 * 40;
        }

        @Override
        Pose pose(Level level, LocalPlayer player, float t, float dt, float pt) {
            Vec3 m = mob.getPosition(pt);
            float h = mob.getBbHeight();
            Vec3 body = m.add(0, h * 0.55, 0);
            float bodyYaw = Mth.rotLerp(pt, mob.yBodyRotO, mob.yBodyRot);
            if (!init) {
                heading = bodyYaw;
                init = true;
            }
            // The heading chases the animal's own at a limited speed (at most ~25 degrees per second).
            float diff = Mth.wrapDegrees(bodyYaw - heading);
            float turn = Mth.clamp(diff * (1f - (float) Math.exp(-dt * 0.8)), -25f * dt, 25f * dt);
            heading += turn;
            float yaw = heading * Mth.DEG_TO_RAD;
            Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
            Vec3 right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
            double dist = 2.6 + h * 1.3;
            Vec3 wanted = m.subtract(forward.scale(dist)).add(right.scale(side * (1.0 + h * 0.4))).add(0, h * 0.8 + 0.6, 0);
            wanted = keepClear(level, body, wanted, player);
            if (pos == null) {
                pos = wanted;
                aim = body;
            }
            pos = pos.lerp(wanted, 1 - Math.exp(-dt * 1.1));
            aim = aim.lerp(body, 1 - Math.exp(-dt * 2.2));
            return new Pose(pos, aim, 0.7f, true);
        }
    }

    /** Wide, high shot that rises and turns above the player. */
    static final class OverheadShot extends Shot {
        private final int dir;

        OverheadShot(float duration, int dir) {
            super(duration);
            this.dir = dir;
        }

        @Override
        String name() {
            return "overhead";
        }

        @Override
        Pose pose(Level level, LocalPlayer player, float t, float dt, float pt) {
            Vec3 base = player.getPosition(pt).add(0, 0.9, 0);
            double room = headroom(level, base, 22, player);
            float f = t / duration;
            double h = Math.min(room - 0.6, 9 + 9 * smooth(f));
            float a = dir * (0.5f + 1.1f * (t / duration));
            Vec3 pos = base.add(Mth.sin(a) * 2.6, h, Mth.cos(a) * 2.6);
            return new Pose(pos, base, 1.28f, false);
        }
    }

    /** A crane move: pulls back and up from the player to reveal the surroundings. */
    static final class CraneShot extends Shot {
        CraneShot(float duration) {
            super(duration);
        }

        @Override
        String name() {
            return "crane";
        }

        @Override
        Pose pose(Level level, LocalPlayer player, float t, float dt, float pt) {
            Vec3 head = player.getPosition(pt).add(0, 1.4, 0);
            float yaw = player.getViewYRot(pt) * Mth.DEG_TO_RAD;
            Vec3 behind = new Vec3(Mth.sin(yaw), 0, -Mth.cos(yaw));       // behind the player
            float f = smooth(t / duration);
            Vec3 pos = head.add(behind.scale(2.2 + 5.5 * f)).add(0, 0.5 + 5.0 * f, 0);
            pos = keepClear(level, head, pos, player);
            return new Pose(pos, head.add(0, -0.3 * f, 0), 1.0f, false);
        }
    }

    /** Three-quarter portrait at or above eye level, drifting round to the player's profile. */
    static final class PortraitShot extends Shot {
        private final int dir;

        PortraitShot(float duration, int dir) {
            super(duration);
            this.dir = dir;
        }

        @Override
        String name() {
            return "portrait";
        }

        @Override
        Pose pose(Level level, LocalPlayer player, float t, float dt, float pt) {
            Vec3 chest = player.getPosition(pt).add(0, 1.3, 0);
            float f = smooth(t / duration);
            float a = (player.getViewYRot(pt) + 180f + dir * Mth.lerp(f, 35f, 80f)) * Mth.DEG_TO_RAD;
            float r = 3.9f - 0.5f * f;
            Vec3 pos = chest.add(-Mth.sin(a) * r, 0.7 + 0.5 * f, Mth.cos(a) * r);
            pos = keepClear(level, chest, pos, player);
            return new Pose(pos, chest.add(0, 0.15, 0), 0.8f, false);
        }
    }

    /** A distant camera on a long lens looking back at the player, held almost still with a faint drift. */
    static final class TeleShot extends Shot {
        private final Vec3 cam;

        TeleShot(float duration, Vec3 cam) {
            super(duration);
            this.cam = cam;
        }

        @Override
        String name() {
            return "tele";
        }

        @Override
        Pose pose(Level level, LocalPlayer player, float t, float dt, float pt) {
            Vec3 target = player.getPosition(pt).add(0, 1.0, 0);
            Vec3 drift = new Vec3(Mth.sin(t * 0.05f) * 1.2, Mth.sin(t * 0.04f) * 0.3, Mth.cos(t * 0.05f) * 1.2);
            return new Pose(cam.add(drift), target, 0.4f, false);
        }
    }

    /** A slow lateral slide at head height, looking out across the land: foreground and horizon part company. */
    static final class TruckShot extends Shot {
        private final int dir;

        TruckShot(float duration, int dir) {
            super(duration);
            this.dir = dir;
        }

        @Override
        String name() {
            return "truck";
        }

        @Override
        Pose pose(Level level, LocalPlayer player, float t, float dt, float pt) {
            Vec3 eye = player.getEyePosition(pt);
            float yaw = player.getViewYRot(pt) * Mth.DEG_TO_RAD;
            Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
            Vec3 right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
            float f = smooth(t / duration);
            Vec3 pos = eye.add(forward.scale(-2.2)).add(right.scale(dir * Mth.lerp(f, -3.5f, 3.5f))).add(0, 0.9, 0);
            pos = keepClear(level, eye, pos, player);
            Vec3 aim = eye.add(forward.scale(26)).add(0, 1.5, 0);
            return new Pose(pos, aim, 0.85f, false);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Safety
    // ------------------------------------------------------------------------------------------

    /** Is a hostile mob within {@code radius} blocks of the player? */
    static boolean hostileNear(Level level, LocalPlayer player, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        double r2 = radius * radius;
        for (Entity e : level.getEntities(player, box, x -> x instanceof Enemy && x.isAlive() && !x.isSpectator())) {
            if (e.distanceToSqr(player) <= r2) {
                return true;
            }
        }
        return false;
    }
}
