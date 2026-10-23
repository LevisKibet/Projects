package com.ronald.ancientpeaks.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ronald.ancientpeaks.mixin.SurfaceContextAccessor;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Like vanilla "steep", but checks all four directions. Vanilla only catches north- and east-facing slopes,
 * which leaves grass and dirt plastered over south- and west-facing cliffs.
 */
public record SteepAny(int threshold) implements SurfaceRules.ConditionSource {
    public static final Codec<SteepAny> CODEC_RAW = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("threshold", 3).forGetter(SteepAny::threshold)).apply(i, SteepAny::new));
    public static final KeyDispatchDataCodec<SteepAny> CODEC = KeyDispatchDataCodec.of(CODEC_RAW);

    @Override public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return CODEC; }

    @Override
    public SurfaceRules.Condition apply(SurfaceRules.Context context) {
        SurfaceContextAccessor ctx = (SurfaceContextAccessor) (Object) context;
        return () -> {
            ChunkAccess chunk = ctx.ancientpeaks$chunk();
            int lx = ctx.ancientpeaks$blockX() & 15, lz = ctx.ancientpeaks$blockZ() & 15;
            int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz);
            int[][] o = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : o) {
                int x = lx + d[0], z = lz + d[1];
                if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                if (Math.abs(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - h) >= threshold) return true;
            }
            return false;
        };
    }
}
