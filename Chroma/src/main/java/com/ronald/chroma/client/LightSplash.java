package com.ronald.chroma.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ronald.chroma.content.Curve;
import com.ronald.chroma.content.LineEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Coloured light thrown onto the surfaces round a line, added on top of the world like real light (the way Luxium
 * does it). The ordinary coloured lighting only tints the light that's already there, which is subtle; this adds a
 * soft wash of the line's own colour that fades with distance and faces toward it. Worked out once per line (and
 * again when the line or the blocks round it change), then drawn each frame. Off when Luxium is installed, since
 * Luxium's own lights already do this.
 */
public final class LightSplash {
    private LightSplash() {}

    private static final boolean TIMING = Boolean.getBoolean("chroma.autotest");
    static final boolean ON = !net.minecraftforge.fml.ModList.get().isLoaded("luxium");

    /**
     * Each face: 4 corners of x, y, z, r, g, b (world space, colour already scaled by distance and facing). Kept until
     * the line changes or a block near it does (see BlockChanges), so it's worked out once, not every few seconds.
     */
    private record Cache(int revision, long stamp, long[] sections, float[] quads) {}
    private static final Map<LineEntity, Cache> CACHE = new WeakHashMap<>();

    /** How many lines may work their light out again this frame: one, so a crowd of lines never stutters. */
    private static int rebuildsLeft = 1;
    static void newFrame() { rebuildsLeft = 1; }

    static void render(LineEntity line, List<Curve.Sample> samples, BlockPos o, PoseStack.Pose pose, MultiBufferSource buf, double t) {
        if (!ON || samples.size() < 2) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Cache c = CACHE.get(line);
        boolean stale = c == null || c.revision != line.revision || BlockChanges.stamp(c.sections) != c.stamp;
        if (stale && rebuildsLeft > 0) {
            rebuildsLeft--;
            long t0 = System.nanoTime();
            c = build(level, line, samples);
            CACHE.put(line, c);
            if (TIMING) com.ronald.chroma.Chroma.LOGGER.info("CHROMADEV splash built: {} faces in {} us", c.quads.length / 24, (System.nanoTime() - t0) / 1000);
        }
        if (c == null) return;                         // its turn to be worked out comes next frame
        if (c.quads.length == 0) return;
        // fade out where daylight is strong: only the dark lets the colour show
        float daylight = Math.max(0, 1f - level.getSkyDarken() / 11f);
        float pulse = line.kind() == Curve.STRIP ? Curve.stripBrightness(line.mode(), Curve.SPEEDS[line.speed()], 0, t) : 1f;
        VertexConsumer vc = buf.getBuffer(RenderType.lightning());
        Matrix4f m = pose.pose();
        float[] q = c.quads;
        for (int i = 0; i < q.length; i += 24) {
            for (int pass = 0; pass < 2; pass++) {            // both windings, so it shows whichever way the face is wound
                for (int k = 0; k < 4; k++) {
                    int v = i + (pass == 0 ? k : 3 - k) * 6;
                    vc.vertex(m, q[v] - o.getX(), q[v + 1] - o.getY(), q[v + 2] - o.getZ())
                            .color(q[v + 3], q[v + 4], q[v + 5], pulse * (1f - daylight * 0.85f)).endVertex();
                }
            }
        }
    }

    /** Find the faces round the line's light and how much of its colour reaches each corner. */
    private static Cache build(ClientLevel level, LineEntity line, List<Curve.Sample> samples) {
        int colour = Curve.castColour(line.colours());
        float cr = ((colour >> 16) & 255) / 255f, cg = ((colour >> 8) & 255) / 255f, cb = (colour & 255) / 255f;
        float mx = Math.max(0.01f, Math.max(cr, Math.max(cg, cb)));
        cr /= mx; cg /= mx; cb /= mx;
        boolean bright = line.kind() == Curve.STRIP;
        double radius = bright ? 5.0 : 4.0;
        float strength = bright ? 0.42f : 0.32f;

        // light sources along the line, a block apart
        java.util.List<Vec3> lights = new java.util.ArrayList<>();
        double next = 0;
        for (Curve.Sample s : samples) {
            if (s.s() < next) continue;
            next = s.s() + 1.0;
            lights.add(s.pos().add(s.normal().scale(0.25)));
        }
        if (lights.size() > 64) lights = lights.subList(0, 64);

        // every block near the line is looked at once (not once per light), and only if some light can reach it
        double x0 = Double.MAX_VALUE, y0 = x0, z0 = x0, x1 = -x0, y1 = -x0, z1 = -x0;
        for (Vec3 l : lights) {
            x0 = Math.min(x0, l.x); y0 = Math.min(y0, l.y); z0 = Math.min(z0, l.z);
            x1 = Math.max(x1, l.x); y1 = Math.max(y1, l.y); z1 = Math.max(z1, l.z);
        }
        int bx0 = (int) Math.floor(x0 - radius), by0 = (int) Math.floor(y0 - radius), bz0 = (int) Math.floor(z0 - radius);
        int bx1 = (int) Math.floor(x1 + radius), by1 = (int) Math.floor(y1 + radius), bz1 = (int) Math.floor(z1 + radius);
        double reach2 = (radius + 1) * (radius + 1);
        Map<Long, float[]> faces = new HashMap<>();    // key: block pos + face -> 4 corner intensities
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(), n = new BlockPos.MutableBlockPos();
        java.util.List<Vec3> near = new java.util.ArrayList<>();
        for (int bx = bx0; bx <= bx1; bx++) for (int by = by0; by <= by1; by++) for (int bz = bz0; bz <= bz1; bz++) {
            double cx = bx + 0.5, cy = by + 0.5, cz = bz + 0.5;
            near.clear();
            for (Vec3 l : lights) {
                double dx = l.x - cx, dy = l.y - cy, dz = l.z - cz;
                if (dx * dx + dy * dy + dz * dz < reach2) near.add(l);
            }
            if (near.isEmpty()) continue;
            m.set(bx, by, bz);
            BlockState st = level.getBlockState(m);
            if (!st.canOcclude()) continue;
            for (Direction d : DIRS) {
                n.setWithOffset(m, d);
                if (level.getBlockState(n).canOcclude()) continue;              // hidden face
                double[] cs = cornersD(bx, by, bz, d);
                double nx = d.getStepX(), ny = d.getStepY(), nz = d.getStepZ();
                double fx = (cs[0] + cs[6]) * 0.5, fy = (cs[1] + cs[7]) * 0.5, fz = (cs[2] + cs[8]) * 0.5;
                float[] acc = null;
                for (Vec3 l : near) {
                    if (nx * (l.x - fx) + ny * (l.y - fy) + nz * (l.z - fz) <= -0.05) continue;   // faces away from this light
                    for (int i = 0; i < 4; i++) {
                        double tx = l.x - cs[i * 3], ty = l.y - cs[i * 3 + 1], tz = l.z - cs[i * 3 + 2];
                        double d2 = tx * tx + ty * ty + tz * tz;
                        if (d2 >= radius * radius) continue;
                        double dist = Math.sqrt(d2);
                        double fall = 1 - dist / radius;
                        double facing = dist < 1e-4 ? 1 : 0.35 + 0.65 * Math.max(0, (nx * tx + ny * ty + nz * tz) / dist);
                        if (acc == null) acc = faces.computeIfAbsent(m.asLong() * 6 + d.ordinal(), k -> new float[4]);
                        acc[i] += (float) (fall * fall * facing);
                    }
                }
            }
        }
        float[] out = new float[faces.size() * 24];
        int k = 0;
        for (Map.Entry<Long, float[]> e : faces.entrySet()) {
            long key = e.getKey();
            BlockPos p = BlockPos.of(Math.floorDiv(key, 6));
            Direction d = Direction.values()[(int) Math.floorMod(key, 6)];
            float[] a = e.getValue();
            if (a[0] + a[1] + a[2] + a[3] < 0.02f) continue;
            float sky = level.getBrightness(LightLayer.SKY, p.relative(d)) / 15f;
            Vec3[] cs = corners(p, d);
            Vec3 lift = Vec3.atLowerCornerOf(d.getNormal()).scale(0.003);
            for (int i = 0; i < 4; i++) {
                float v = Math.min(1f, a[i]) * strength * (1f - sky * 0.5f);
                Vec3 q = cs[i].add(lift);
                out[k++] = (float) q.x; out[k++] = (float) q.y; out[k++] = (float) q.z;
                out[k++] = cr * v; out[k++] = cg * v; out[k++] = cb * v;
            }
        }
        // the chunk sections this covers, so a block change there can be noticed
        java.util.List<Long> secs = new java.util.ArrayList<>();
        for (int sx = bx0 >> 4; sx <= bx1 >> 4; sx++) for (int sy = by0 >> 4; sy <= by1 >> 4; sy++) for (int sz = bz0 >> 4; sz <= bz1 >> 4; sz++)
            secs.add(net.minecraft.core.SectionPos.asLong(sx, sy, sz));
        long[] sections = secs.stream().mapToLong(Long::longValue).toArray();
        return new Cache(line.revision, BlockChanges.stamp(sections), sections, java.util.Arrays.copyOf(out, k));
    }

    private static final Direction[] DIRS = Direction.values();

    /** The four corners of a block face as 12 numbers (no objects made: this runs for every face near a line). */
    private static double[] cornersD(int x, int y, int z, Direction d) {
        Vec3[] v = corners(new BlockPos(x, y, z), d);
        return new double[]{v[0].x, v[0].y, v[0].z, v[1].x, v[1].y, v[1].z, v[2].x, v[2].y, v[2].z, v[3].x, v[3].y, v[3].z};
    }

    /** The four corners of a block face, in order round it. */
    private static Vec3[] corners(BlockPos p, Direction d) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        return switch (d) {
            case UP -> new Vec3[]{new Vec3(x, y + 1, z), new Vec3(x, y + 1, z + 1), new Vec3(x + 1, y + 1, z + 1), new Vec3(x + 1, y + 1, z)};
            case DOWN -> new Vec3[]{new Vec3(x, y, z), new Vec3(x + 1, y, z), new Vec3(x + 1, y, z + 1), new Vec3(x, y, z + 1)};
            case NORTH -> new Vec3[]{new Vec3(x, y, z), new Vec3(x, y + 1, z), new Vec3(x + 1, y + 1, z), new Vec3(x + 1, y, z)};
            case SOUTH -> new Vec3[]{new Vec3(x, y, z + 1), new Vec3(x + 1, y, z + 1), new Vec3(x + 1, y + 1, z + 1), new Vec3(x, y + 1, z + 1)};
            case WEST -> new Vec3[]{new Vec3(x, y, z), new Vec3(x, y, z + 1), new Vec3(x, y + 1, z + 1), new Vec3(x, y + 1, z)};
            case EAST -> new Vec3[]{new Vec3(x + 1, y, z), new Vec3(x + 1, y + 1, z), new Vec3(x + 1, y + 1, z + 1), new Vec3(x + 1, y, z + 1)};
        };
    }
}
