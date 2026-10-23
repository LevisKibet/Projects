package com.ronald.rivergence.mixin;

import com.ronald.rivergence.client.WaterWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Under (or near) a passing shower the sky greys over and the day dims, as it does in vanilla's rain. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelWeatherMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void rivergence$greySky(Vec3 pos, float pt, CallbackInfoReturnable<Vec3> cir) {
        float g = WaterWeather.gloom();
        if (g < 0.01f) return;
        Vec3 c = cir.getReturnValue();
        double lum = (c.x * 0.3 + c.y * 0.59 + c.z * 0.11) * 0.6;
        double k = g * 0.75;
        cir.setReturnValue(new Vec3(c.x + (lum - c.x) * k, c.y + (lum - c.y) * k, c.z + (lum - c.z) * k));
    }

    @Inject(method = "getCloudColor", at = @At("RETURN"), cancellable = true)
    private void rivergence$greyClouds(float pt, CallbackInfoReturnable<Vec3> cir) {
        float g = WaterWeather.gloom();
        if (g < 0.01f) return;
        Vec3 c = cir.getReturnValue();
        double lum = (c.x * 0.3 + c.y * 0.59 + c.z * 0.11) * 0.7;
        double k = g * 0.8;
        cir.setReturnValue(new Vec3(c.x + (lum - c.x) * k, c.y + (lum - c.y) * k, c.z + (lum - c.z) * k));
    }

    @Inject(method = "getSkyDarken", at = @At("RETURN"), cancellable = true)
    private void rivergence$dim(float pt, CallbackInfoReturnable<Float> cir) {
        float g = WaterWeather.gloom();
        if (g < 0.01f) return;
        cir.setReturnValue(cir.getReturnValue() * (1 - 0.4f * g));
    }
}
