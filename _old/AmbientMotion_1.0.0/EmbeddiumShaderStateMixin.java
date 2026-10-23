package com.ronald.ambientmotion.mixin;

import com.ronald.ambientmotion.SwayUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each time Embeddium readies its terrain shader for drawing, the sway clock and strength go in alongside. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface", remap = false)
public class EmbeddiumShaderStateMixin {
    @Inject(method = "setupState", at = @At("TAIL"), remap = false, require = 0)
    private void ambientmotion$uniforms(CallbackInfo ci) {
        SwayUniforms.uploadToCurrentProgram();
    }
}
