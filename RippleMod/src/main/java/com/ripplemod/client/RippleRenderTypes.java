package com.ripplemod.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/** Render type for the ripple surface: flat-coloured translucent quads that do not write depth. */
public class RippleRenderTypes extends RenderType {
    private RippleRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                              boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static final RenderType SURFACE = create(
            "ripplemod_surface",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            262144,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false));
}
