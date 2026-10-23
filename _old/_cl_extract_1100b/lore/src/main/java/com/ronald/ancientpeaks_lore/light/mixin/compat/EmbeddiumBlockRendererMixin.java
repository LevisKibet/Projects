package com.ronald.ancientpeaks_lore.light.mixin.compat;

import com.ronald.ancientpeaks_lore.light.ColorLight;
import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Embeddium (and Rubidium, which is Embeddium underneath) builds the world's faces itself instead of through vanilla,
 * so the colour goes in here: just before each finished face is handed to its buffer, its corners are coloured by
 * the light falling on them. Only loaded when Embeddium is installed.
 */
@Pseudo
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class EmbeddiumBlockRendererMixin {
    @Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;

    private final float[] logreslight$corners = new float[12];

    @Inject(method = "writeGeometry", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;)V"),
            require = 0)
    private void logreslight$tint(BlockRenderContext ctx, ChunkModelBuilder builder, Vec3 offset, Material material, BakedQuadView quad,
                                  int[] colors, QuadLightData light, CallbackInfo ci) {
        Direction face = quad.getLightFace();
        if (face == null) return;
        BlockPos pos = ctx.pos();
        Vector3fc o = ctx.origin();
        // vertex positions are relative to the chunk section; pos - origin is the section's corner in the world
        float sx = pos.getX() - o.x(), sy = pos.getY() - o.y(), sz = pos.getZ() - o.z();
        float[] c = logreslight$corners;
        for (int i = 0; i < 4; i++) {
            ChunkVertexEncoder.Vertex v = vertices[i];
            c[i * 3] = sx + v.x; c[i * 3 + 1] = sy + v.y; c[i * 3 + 2] = sz + v.z;
        }
        float[] t = ColorLight.tints(c, face);
        if (t == null) return;
        for (int i = 0; i < 4; i++) {
            ChunkVertexEncoder.Vertex v = vertices[i];
            int col = v.color;                                          // bytes: r, g, b, a (lowest first)
            int r = Math.round((col & 255) * t[i * 3]);
            int g = Math.round(((col >> 8) & 255) * t[i * 3 + 1]);
            int b = Math.round(((col >> 16) & 255) * t[i * 3 + 2]);
            v.color = (col & 0xFF000000) | (b << 16) | (g << 8) | r;
        }
    }
}
