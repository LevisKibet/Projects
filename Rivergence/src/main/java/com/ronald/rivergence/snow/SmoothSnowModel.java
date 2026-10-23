package com.ronald.rivergence.snow;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.IDynamicBakedModel;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Snow layers drawn as one smooth surface: the top is a 4×4 grid whose heights are blended from the snow depth of
 * this block and its eight neighbours, so drifts slope into each other instead of stepping, and fade out onto bare
 * ground. Worked out once when the chunk is meshed (never per frame), like any other block.
 */
public final class SmoothSnowModel implements IDynamicBakedModel {
    private static final ModelProperty<Shape> SHAPE = new ModelProperty<>();

    /** The heights of the (G+1)² grid points, and which sides are open (need a skirt down to the block's base). */
    /** Per side (N, S, W, E): 0 closed, 1 a skirt down to the base, 2 an overhang spilling over the edge. */
    private record Shape(int g, float[] h, byte[] sides) {}

    private final BakedModel base;

    public SmoothSnowModel(BakedModel base) { this.base = base; }

    // ---- the shape, from the neighbourhood ----

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        if (!SnowConfig.smooth()) return data;
        SnowConfig.Detail d = SnowConfig.detail();
        int g = Grooves.detailed(pos) ? d.fine : d.coarse;
        float[] h = SnowSurface.grid(level, pos, state.getValue(SnowLayerBlock.LAYERS), g);
        byte[] sides = new byte[4];                                              // N, S, W, E
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        boolean overhang = SnowConfig.OVERHANGS.get();
        for (int k = 0; k < 4; k++) {
            BlockPos np = m.setWithOffset(pos, dirs[k]).immutable();
            BlockState n = level.getBlockState(np);
            if (n.is(Blocks.SNOW) || n.isFaceSturdy(level, np, dirs[k].getOpposite())) continue;
            sides[k] = 1;
            if (overhang && (n.isAir() || n.canBeReplaced()) && n.getFluidState().isEmpty()) {
                // over the edge of a ledge or a bough: the block under us has nothing beside it
                BlockState nb = level.getBlockState(np.below());
                if ((nb.isAir() || nb.canBeReplaced()) && nb.getFluidState().isEmpty() && !level.getBlockState(pos.below()).isAir()) sides[k] = 2;
            }
        }
        return ModelData.builder().with(SHAPE, new Shape(g, h, sides)).build();
    }

    // ---- the quads ----

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType type) {
        Shape sh = data.get(SHAPE);
        if (sh == null) return base.getQuads(state, side, rand, data, type);
        if (side == Direction.DOWN) return base.getQuads(state, side, rand, data, type);
        if (side != null) return List.of();
        TextureAtlasSprite sp = base.getParticleIcon(data);
        float[] h = sh.h;
        final int G = sh.g;
        List<BakedQuad> out = new ArrayList<>(G * G + 16);
        int W = G + 1;
        for (int j = 0; j < G; j++) for (int i = 0; i < G; i++) {
            float x0 = (float) i / G, x1 = (float) (i + 1) / G, z0 = (float) j / G, z1 = (float) (j + 1) / G;
            float h00 = h[j * W + i], h10 = h[j * W + i + 1], h01 = h[(j + 1) * W + i], h11 = h[(j + 1) * W + i + 1];
            // the slope's normal, for a little shading on the slopes and in the dips
            float nx = ((h00 + h01) - (h10 + h11)) * 0.5f * G, nz = ((h00 + h10) - (h01 + h11)) * 0.5f * G;
            float len = (float) Math.sqrt(nx * nx + 1 + nz * nz);
            nx /= len; nz /= len; float ny = 1 / len;
            int shade = shade(ny, nx);
            out.add(quad(sp, Direction.UP, shade, nx, ny, nz,
                    x0, h00, z0, x0, h01, z1, x1, h11, z1, x1, h10, z0));
        }
        // open sides: a skirt down to the base, or snow spilling over the edge and hanging down its side
        for (int k = 0; k < 4; k++) {
            if (sh.sides[k] == 0) continue;
            float[] e = new float[G + 1];
            for (int t = 0; t <= G; t++) e[t] = switch (k) { case 0 -> h[t]; case 1 -> h[G * W + t]; case 2 -> h[t * W]; default -> h[t * W + G]; };
            if (sh.sides[k] == 2) lip(out, sp, k, G, e);
            else for (int t = 0; t < G; t++) {
                float ta = (float) t / G, tb = (float) (t + 1) / G;
                switch (k) {
                    case 0 -> skirt(out, sp, Direction.NORTH, tb, e[t + 1], 0, ta, e[t], 0);
                    case 1 -> skirt(out, sp, Direction.SOUTH, ta, e[t], 1, tb, e[t + 1], 1);
                    case 2 -> skirt(out, sp, Direction.WEST, 0, e[t], ta, 0, e[t + 1], tb);
                    default -> skirt(out, sp, Direction.EAST, 1, e[t + 1], tb, 1, e[t], ta);
                }
            }
        }
        return out;
    }

    /** Brighter facing up, a touch darker on slopes (and a little more on those facing away from the light). */
    private static int shade(float ny, float nx) {
        float s = 0.80f + 0.20f * ny * ny - 0.05f * nx;
        int g = Math.max(0, Math.min(255, (int) (255 * s)));
        return 0xFF000000 | g << 16 | g << 8 | g;
    }

    private static final Direction[] SIDE = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /** A point of side k's overhang: t along the edge, s out past it, at height y. */
    private static void at(int k, float t, float s, float y, float[] o, int i) {
        switch (k) {
            case 0 -> { o[i] = t; o[i + 2] = -s; }
            case 1 -> { o[i] = t; o[i + 2] = 1 + s; }
            case 2 -> { o[i] = -s; o[i + 2] = t; }
            default -> { o[i] = 1 + s; o[i + 2] = t; }
        }
        o[i + 1] = y;
    }

    /**
     * The overhang's cross-section at an edge height e, as (out, height) pairs from the edge's top round to its foot:
     * a soft rounded drape that bulges a little past the edge and curls back under, hanging over the top of the
     * block below's side. Deeper snow drapes further.
     */
    private static float[] profile(float e) {
        float d = Math.min(e, 0.5f);
        float o = 0.05f + 0.12f * d, foot = -(0.10f + 0.35f * d);
        return new float[]{0, e, o * 0.75f, e - 0.015f, o, e * 0.45f - 0.03f, o * 0.6f, foot, 0, foot + 0.07f};
    }

    /** Snow draped over side k's edge: a rounded lip past the edge, hanging down the side of the block below. */
    private static void lip(List<BakedQuad> out, TextureAtlasSprite sp, int k, int G, float[] e) {
        Direction side = SIDE[k];
        Direction[] faceOf = {Direction.UP, side, side, Direction.DOWN};
        int[] tone = {0xFFF4F4F4, 0xFFE8E8E8, 0xFFD8D8D8, 0xFFC4C4C4};
        float[] q = new float[12];
        for (int t = 0; t < G; t++) {
            float ta = (float) t / G, tb = (float) (t + 1) / G;
            float[] pa = profile(e[t]), pb = profile(e[t + 1]);
            for (int f = 0; f < 4; f++) {
                at(k, ta, pa[f * 2], pa[f * 2 + 1], q, 0);
                at(k, ta, pa[f * 2 + 2], pa[f * 2 + 3], q, 3);
                at(k, tb, pb[f * 2 + 2], pb[f * 2 + 3], q, 6);
                at(k, tb, pb[f * 2], pb[f * 2 + 1], q, 9);
                float v0 = 0.15f + f * 0.18f;
                both(out, sp, faceOf[f], tone[f], q, new float[]{ta, v0, ta, v0 + 0.18f, tb, v0 + 0.18f, tb, v0});
            }
        }
        // the two ends, closed (the cross-section as two quads)
        Direction along = k < 2 ? Direction.WEST : Direction.NORTH;
        float[] uv = {0.2f, 0.2f, 0.4f, 0.3f, 0.5f, 0.6f, 0.2f, 0.8f};
        for (int end = 0; end <= 1; end++) {
            float[] p = profile(e[end == 0 ? 0 : G]);
            float t = end;
            Direction d = end == 0 ? along : along.getOpposite();
            int[] a = {0, 1, 2, 4}, b2 = {2, 3, 4, 4};
            for (int c = 0; c < 4; c++) at(k, t, p[a[c] * 2], p[a[c] * 2 + 1], q, c * 3);
            both(out, sp, d, 0xFFDCDCDC, q, uv);
            for (int c = 0; c < 4; c++) at(k, t, p[b2[c] * 2], p[b2[c] * 2 + 1], q, c * 3);
            both(out, sp, d, 0xFFDCDCDC, q, uv);
        }
    }

    /** A quad seen from both sides (the overhang's faces are thin and catch the eye from any angle). */
    private static void both(List<BakedQuad> out, TextureAtlasSprite sp, Direction face, int tone, float[] q, float[] uv) {
        out.add(quad(sp, face, tone, face.getStepX(), face.getStepY(), face.getStepZ(), uv, q.clone()));
        float[] r = {q[9], q[10], q[11], q[6], q[7], q[8], q[3], q[4], q[5], q[0], q[1], q[2]};
        float[] ruv = {uv[6], uv[7], uv[4], uv[5], uv[2], uv[3], uv[0], uv[1]};
        out.add(quad(sp, face, tone, face.getStepX(), face.getStepY(), face.getStepZ(), ruv, r));
    }

    private static void skirt(List<BakedQuad> out, TextureAtlasSprite sp, Direction d, float xa, float ha, float za, float xb, float hb, float zb) {
        if (ha <= 0.001f && hb <= 0.001f) return;
        out.add(quad(sp, d, 0xFFE0E0E0, d.getStepX(), 0, d.getStepZ(), xa, ha, za, xa, 0, za, xb, 0, zb, xb, hb, zb));
    }

    private static BakedQuad quad(TextureAtlasSprite sp, Direction face, int color, float nx, float ny, float nz, float... p) {
        return quad(sp, face, color, nx, ny, nz, null, p);
    }

    private static BakedQuad quad(TextureAtlasSprite sp, Direction face, int color, float nx, float ny, float nz, float[] uvs, float[] p) {
        int[] v = new int[32];
        int n = (Math.round(nx * 127) & 255) | (Math.round(ny * 127) & 255) << 8 | (Math.round(nz * 127) & 255) << 16;
        // vertex colour is stored ABGR
        int abgr = (color & 0xFF000000) | (color & 0xFF) << 16 | (color & 0xFF00) | (color >> 16 & 0xFF);
        for (int k = 0; k < 4; k++) {
            float x = p[k * 3], y = p[k * 3 + 1], z = p[k * 3 + 2];
            float u, w;
            if (face.getAxis() == Direction.Axis.Y) { u = x; w = z; }
            else if (face.getAxis() == Direction.Axis.Z) { u = x; w = 1 - y; }
            else { u = z; w = 1 - y; }
            int o = k * 8;
            v[o] = Float.floatToRawIntBits(x);
            v[o + 1] = Float.floatToRawIntBits(y);
            v[o + 2] = Float.floatToRawIntBits(z);
            v[o + 3] = abgr;
            if (uvs != null) { u = uvs[k * 2]; w = uvs[k * 2 + 1]; }
            // kept off the sprite's very edge, so mipmaps never pick up the texture next to it in the atlas
            u = Math.max(0.03f, Math.min(0.97f, u)); w = Math.max(0.03f, Math.min(0.97f, w));
            v[o + 4] = Float.floatToRawIntBits(sp.getU(u * 16));
            v[o + 5] = Float.floatToRawIntBits(sp.getV(w * 16));
            v[o + 6] = 0;
            v[o + 7] = n;
        }
        return new BakedQuad(v, -1, face, sp, true, false);
    }

    // ---- the rest is the plain snow model's ----

    // flat lighting, lit by the light where the snow lies: ambient occlusion turns the bent faces and the overhangs
    // (which sit partly outside the block) black
    @Override public boolean useAmbientOcclusion() { return false; }
    @Override public boolean useAmbientOcclusion(BlockState state) { return false; }
    @Override public boolean useAmbientOcclusion(BlockState state, RenderType type) { return false; }
    @Override public boolean isGui3d() { return base.isGui3d(); }
    @Override public boolean usesBlockLight() { return base.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return false; }
    @Override public TextureAtlasSprite getParticleIcon() { return base.getParticleIcon(); }
    @Override public TextureAtlasSprite getParticleIcon(ModelData data) { return base.getParticleIcon(data); }
    @Override public ItemOverrides getOverrides() { return base.getOverrides(); }
    @Override public ItemTransforms getTransforms() { return base.getTransforms(); }
    @Override public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) { return base.getRenderTypes(state, rand, data); }
}
