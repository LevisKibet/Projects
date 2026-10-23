package com.ronald.ambientmotion.mixin;

import com.ronald.ambientmotion.AmbientMotion;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Embeddium draws the world with its own terrain shader, so the sway is written into it as it loads: the shared sway
 * code goes in after its uniforms, and each vertex is nudged just before it is projected.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
public class EmbeddiumShaderSourceMixin {
    private static final String ANCHOR = "uniform vec3 u_RegionOffset;";
    private static final String PROJECT = "gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);";

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void ambientmotion$sway(ResourceLocation id, CallbackInfoReturnable<String> cir) {
        if (id == null || !id.getPath().endsWith("blocks/block_layer_opaque.vsh")) return;
        String src = cir.getReturnValue();
        if (src == null || !src.contains(ANCHOR) || !src.contains(PROJECT) || src.contains("ambient_sway")) return;
        String sway = swaySource();
        if (sway == null) return;
        src = src.replace(ANCHOR, ANCHOR + "\n\n" + sway + "\n");
        src = src.replace(PROJECT, "position += ambient_sway(_vert_position + _get_draw_translation(_draw_id), _vert_color, "
                + "_vert_tex_diffuse_coord, gl_VertexID, _material_alpha_cutoff(_material_params) > 0.0);\n    " + PROJECT);
        cir.setReturnValue(src);
        AmbientMotion.LOGGER.info("Ambient Motion: sway added to Embeddium's terrain shader");
    }

    private static String swaySource() {
        try (InputStream in = EmbeddiumShaderSourceMixin.class.getResourceAsStream("/assets/minecraft/shaders/include/ambientmotion_sway.glsl")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
