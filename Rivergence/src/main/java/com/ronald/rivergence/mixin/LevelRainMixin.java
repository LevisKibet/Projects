package com.ronald.rivergence.mixin;

import com.ronald.rivergence.world.WeatherHook;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A local shower is rain: on the client the player's own rain level, and everywhere, rain where it falls (see Showers). */
@Mixin(Level.class)
public abstract class LevelRainMixin {
    @Shadow public abstract boolean isClientSide();
    @Shadow public abstract boolean isRaining();

    /** A passing shower is real rain where it falls: fires go out, burning things are doused, the ground gets wet. */
    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void rivergence$showerAt(net.minecraft.core.BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (isRaining()) return;
        if (com.ronald.rivergence.world.Showers.rainingAt((Level) (Object) this, pos)) cir.setReturnValue(true);
    }

    @Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
    private void rivergence$localRain(float pt, CallbackInfoReturnable<Float> cir) {
        if (!isClientSide()) return;
        float v = cir.getReturnValue();
        WeatherHook.vanilla = v;
        float l = WeatherHook.local;
        if (l > v) cir.setReturnValue(l);
    }
}
