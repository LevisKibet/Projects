package com.ronald.rivergence.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ronald.rivergence.client.RainClouds;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With Luxium installed: its cloud shadows come from its own cloud pass. After it draws its clouds into the shadow
 * map, the rain clouds are drawn in too, so a shower's cloud shades the land under it like any other cloud.
 * Does nothing (and isn't even applied) without Luxium.
 */
@Pseudo
@Mixin(targets = "com.vinlanx.luxium.client.clouds.LuxiumCloudRenderer", remap = false)
public abstract class LuxiumCloudShadowMixin {
    @Inject(method = "renderCustomShadowCasters", at = @At("RETURN"), require = 0, remap = false)
    private void rivergence$rainCloudShadows(PoseStack pose, Matrix4f proj, Matrix4f lightRot, float radius, Vec3 toLight, float pt,
                                             double cx, double cy, double cz, int ticks, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        try { RainClouds.castShadows(pose, proj, cx, cy, cz); } catch (Throwable t) { /* never break Luxium's pass */ }
    }
}
