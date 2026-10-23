package com.ronald.freshpowder.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The smooth snow surface: each column's depth, softened with its neighbours', blended across the block with an
 * eased curve, less whatever's been pushed aside by someone wading through. Every block works its points out from
 * the same absolute columns and the same groove lattice, so neighbours always meet.
 */
public final class SnowSurface {
    private SnowSurface() {}

    /** Points per side: coarse where the snow is untouched, fine where a groove runs. */
    public static final int COARSE = 4, FINE = Grooves.N;
    /** Never quite down to the ground: the ground's top face under snow isn't drawn, so the snow must cover it. */
    private static final float MIN = 0.004f;

    private static float raw(BlockAndTintGetter level, BlockPos.MutableBlockPos m, BlockPos pos, int dx, int dz) {
        BlockState s = level.getBlockState(m.setWithOffset(pos, dx, 0, dz));
        if (s.is(Blocks.SNOW)) return s.getValue(SnowLayerBlock.LAYERS) / 8f;
        if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) return 1f;
        if (s.isAir() || s.canBeReplaced()) {
            BlockState b = level.getBlockState(m.setWithOffset(pos, dx, -1, dz));
            if (b.is(Blocks.SNOW)) return (b.getValue(SnowLayerBlock.LAYERS) - 8) / 8f;   // a step down
            return 0f;                                                                      // bare ground or a drop
        }
        return Float.NaN;
    }

    private static float ease(float t) { return t * t * (3 - 2 * t); }

    /** The softened depths of this column and the eight round it (NaN for walls). */
    public static float[] cells(BlockAndTintGetter level, BlockPos pos, int layers) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        float[] r = new float[25];
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
            r[(dz + 2) * 5 + dx + 2] = dx == 0 && dz == 0 ? layers / 8f : raw(level, m, pos, dx, dz);
        float[] c = new float[9];
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            float self = r[(dz + 2) * 5 + dx + 2];
            if (Float.isNaN(self)) { c[(dz + 1) * 3 + dx + 1] = Float.NaN; continue; }
            float sum = 0, w = 0;
            for (int bz = -1; bz <= 1; bz++) for (int bx = -1; bx <= 1; bx++) {
                float v = r[(dz + bz + 2) * 5 + dx + bx + 2];
                if (Float.isNaN(v)) continue;
                float wt = (bx == 0 ? 2 : 1) * (bz == 0 ? 2 : 1);
                sum += wt * v; w += wt;
            }
            c[(dz + 1) * 3 + dx + 1] = sum / w;
        }
        return c;
    }

    /** The untouched surface at (fx, fz) across the block, 0..1 each. */
    public static float base(float[] c, float fx, float fz) {
        float px = fx - 0.5f, pz = fz - 0.5f;
        int ix = px < 0 ? -1 : 0, iz = pz < 0 ? -1 : 0;
        float tx = ease(px - ix), tz = ease(pz - iz);
        float sum = 0, w = 0;
        for (int b = 0; b <= 1; b++) for (int a = 0; a <= 1; a++) {
            float v = c[(iz + b + 1) * 3 + ix + a + 1];
            if (Float.isNaN(v)) continue;
            float wt = (a == 0 ? 1 - tx : tx) * (b == 0 ? 1 - tz : tz);
            sum += wt * v; w += wt;
        }
        return Math.min(1f, Math.max(0f, w > 1e-4f ? sum / w : c[4]));
    }

    /** The surface at lattice point (i, j) of a g×g grid over the block at pos, grooves included. */
    public static float point(float[] c, BlockPos pos, int g, int i, int j) {
        float h = base(c, (float) i / g, (float) j / g);
        int step = FINE / g;
        float dent = Grooves.at(pos.getX(), pos.getY(), pos.getZ(), i * step, j * step);
        if (dent != 0) h = dent > 0 ? h * (1 - dent) : Math.min(1f, h * (1 - dent) + 0.01f);
        return Math.max(MIN, h);
    }

    /**
     * The heights of the (g+1)² points over the snow at pos. Where a fine block borders a coarse one, the in-between
     * points along that edge are laid straight between their neighbours so the two meet without a crack.
     */
    public static float[] grid(BlockAndTintGetter level, BlockPos pos, int layers, int g) {
        float[] c = cells(level, pos, layers);
        int W = g + 1;
        float[] h = new float[W * W];
        for (int j = 0; j <= g; j++) for (int i = 0; i <= g; i++) h[j * W + i] = point(c, pos, g, i, j);
        if (g == FINE) {
            boolean n = !Grooves.detailed(pos.north()), sth = !Grooves.detailed(pos.south());
            boolean wst = !Grooves.detailed(pos.west()), e = !Grooves.detailed(pos.east());
            int r = FINE / COARSE;
            for (int k = 0; k <= g; k++) {
                if (k % r == 0) continue;
                int k0 = k - k % r, k1 = k0 + r;
                float t = (float) (k - k0) / r;
                if (n) h[k] = h[k0] + (h[k1] - h[k0]) * t;
                if (sth) h[g * W + k] = h[g * W + k0] + (h[g * W + k1] - h[g * W + k0]) * t;
                if (wst) h[k * W] = h[k0 * W] + (h[k1 * W] - h[k0 * W]) * t;
                if (e) h[k * W + g] = h[k0 * W + g] + (h[k1 * W + g] - h[k0 * W + g]) * t;
            }
        }
        return h;
    }
}
