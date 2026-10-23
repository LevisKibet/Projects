package com.ronald.freshpowder.client;

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
    private record Shape(int g, float[] h, boolean[] open) {}

    private final BakedModel base;

    public SmoothSnowModel(BakedModel base) { this.base = base; }

    // ---- the shape, from the neighbourhood ----

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int g = Grooves.detailed(pos) ? SnowSurface.FINE : SnowSurface.COARSE;
        float[] h = SnowSurface.grid(level, pos, state.getValue(SnowLayerBlock.LAYERS), g);
        boolean[] open = new boolean[4];                                         // N, S, W, E
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (int k = 0; k < 4; k++) {
            BlockPos np = m.setWithOffset(pos, dirs[k]).immutable();
            BlockState n = level.getBlockState(np);
            open[k] = !n.is(Blocks.SNOW) && !n.isFaceSturdy(level, np, dirs[k].getOpposite());
        }
        return ModelData.builder().with(SHAPE, new Shape(g, h, open)).build();
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
        List<BakedQuad> out = new ArrayList<>(G * G + 8);
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
        // skirts down to the base on open sides (toward air or bare ground), so the edge has no gap under it
        if (sh.open[0]) for (int i = 0; i < G; i++) skirt(out, sp, Direction.NORTH, (float) (i + 1) / G, h[i + 1], 0, (float) i / G, h[i], 0);
        if (sh.open[1]) for (int i = 0; i < G; i++) skirt(out, sp, Direction.SOUTH, (float) i / G, h[G * W + i], 1, (float) (i + 1) / G, h[G * W + i + 1], 1);
        if (sh.open[2]) for (int j = 0; j < G; j++) skirt(out, sp, Direction.WEST, 0, h[j * W], (float) j / G, 0, h[(j + 1) * W], (float) (j + 1) / G);
        if (sh.open[3]) for (int j = 0; j < G; j++) skirt(out, sp, Direction.EAST, 1, h[(j + 1) * W + G], (float) (j + 1) / G, 1, h[j * W + G], (float) j / G);
        return out;
    }

    /** Brighter facing up, a touch darker on slopes (and a little more on those facing away from the light). */
    private static int shade(float ny, float nx) {
        float s = 0.80f + 0.20f * ny * ny - 0.05f * nx;
        int g = Math.max(0, Math.min(255, (int) (255 * s)));
        return 0xFF000000 | g << 16 | g << 8 | g;
    }

    private static void skirt(List<BakedQuad> out, TextureAtlasSprite sp, Direction d, float xa, float ha, float za, float xb, float hb, float zb) {
        if (ha <= 0.001f && hb <= 0.001f) return;
        out.add(quad(sp, d, 0xFFE0E0E0, d.getStepX(), 0, d.getStepZ(), xa, ha, za, xa, 0, za, xb, 0, zb, xb, hb, zb));
    }

    private static BakedQuad quad(TextureAtlasSprite sp, Direction face, int color, float nx, float ny, float nz, float... p) {
        int[] v = new int[32];
        int n = (Math.round(nx * 127) & 255) | (Math.round(ny * 127) & 255) << 8 | (Math.round(nz * 127) & 255) << 16;
        // vertex colour is stored ABGR
        int abgr = (color & 0xFF000000) | (color & 0xFF) << 16 | (color & 0xFF00) | (color >> 16 & 0xFF);
        for (int k = 0; k < 4; k++) {
            float x = p[k * 3], y = p[k * 3 + 1], z = p[k * 3 + 2];
            float u, w;
            if (face == Direction.UP) { u = x; w = z; }
            else if (face.getAxis() == Direction.Axis.Z) { u = x; w = 1 - y; }
            else { u = z; w = 1 - y; }
            int o = k * 8;
            v[o] = Float.floatToRawIntBits(x);
            v[o + 1] = Float.floatToRawIntBits(y);
            v[o + 2] = Float.floatToRawIntBits(z);
            v[o + 3] = abgr;
            v[o + 4] = Float.floatToRawIntBits(sp.getU(u * 16));
            v[o + 5] = Float.floatToRawIntBits(sp.getV(w * 16));
            v[o + 6] = 0;
            v[o + 7] = n;
        }
        return new BakedQuad(v, -1, face, sp, true);
    }

    // ---- the rest is the plain snow model's ----

    @Override public boolean useAmbientOcclusion() { return base.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return base.isGui3d(); }
    @Override public boolean usesBlockLight() { return base.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return false; }
    @Override public TextureAtlasSprite getParticleIcon() { return base.getParticleIcon(); }
    @Override public TextureAtlasSprite getParticleIcon(ModelData data) { return base.getParticleIcon(data); }
    @Override public ItemOverrides getOverrides() { return base.getOverrides(); }
    @Override public ItemTransforms getTransforms() { return base.getTransforms(); }
    @Override public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) { return base.getRenderTypes(state, rand, data); }
}
