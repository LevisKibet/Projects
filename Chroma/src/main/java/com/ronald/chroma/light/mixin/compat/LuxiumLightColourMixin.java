package com.ronald.chroma.light.mixin.compat;

import com.ronald.chroma.light.LuxiumColours;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Luxium casts real shadows from nearby lights, but picks each light's colour from a short list of block names, so
 * everything else glows the same warm orange. Every one of its shadow-casting lights is coloured in this one method
 * (it writes red, green, blue and a 1 into the array at the offset), so once it's done we write our colour over its
 * own, kept at the brightness it chose. Its shadows then come out in the light's real colour. Only applied when
 * Luxium is installed; nothing here makes it a requirement.
 */
@Pseudo
@Mixin(targets = "com.vinlanx.luxium.client.shadows.GpuLocalLightResolver", remap = false)
public abstract class LuxiumLightColourMixin {
    @Inject(method = "resolve", at = @At("RETURN"), require = 0, remap = false)
    private static void chroma$colour(ClientLevel level, long pos, int light, float[] out, int at, CallbackInfo ci) {
        LuxiumColours.recolour(level, pos, light, out, at);
    }
}
