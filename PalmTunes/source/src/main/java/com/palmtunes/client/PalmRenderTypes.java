package com.palmtunes.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Render types for the panel. Extending {@link RenderType} is the usual way to reach the protected
 * render-state constants without an access transformer.
 */
public final class PalmRenderTypes extends RenderType {

    private PalmRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                            boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
        throw new UnsupportedOperationException("not instantiable");
    }

    private static final Function<ResourceLocation, RenderType> QUAD = Util.memoize(texture ->
            RenderType.create("palmtunes_quad",
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_TEXT_SHADER)
                            // blur = true: linear filtering, so the generated panel scales down smoothly
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, true, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setCullState(NO_CULL)
                            .createCompositeState(false)));

    /** A translucent, unlit, double-sided textured quad. */
    public static RenderType quad(ResourceLocation texture) {
        return QUAD.apply(texture);
    }
}
