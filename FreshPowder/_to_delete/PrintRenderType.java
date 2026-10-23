package com.ronald.freshpowder.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

/** Flat-coloured translucent quads laid on the snow, writing no depth. */
public final class PrintRenderType extends RenderType {
    private PrintRenderType(String n, VertexFormat f, VertexFormat.Mode m, int s, boolean a, boolean b, Runnable c, Runnable d) { super(n, f, m, s, a, b, c, d); }

    public static final RenderType PRINTS = create("freshpowder_prints", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false));
}
