package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.world.IslandSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With Streams Reflowing installed, its streams water the island (ours stand down - see Island.OWN_STREAMS). It vetoes
 * features where it expects water; on the island's plains and round its tarns, where it carves no streams (on the
 * plains they go underground, see StreamEnds), that left the ground bare, so there it reports "no stream here".
 * Only applied when Streams Reflowing is present (AncientPeaksMixinPlugin).
 */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.core.RiverEngine", remap = false)
public class StreamsVetoMixin {
    private static final long NO_STREAM = ((long) Integer.MIN_VALUE << 32) | (Integer.MIN_VALUE & 0xFFFFFFFFL);

    @Inject(method = "vetoProbeAt(II)J", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ancientpeaks$calmGround(int x, int z, CallbackInfoReturnable<Long> cir) {
        IslandSampler s = IslandSampler.ACTIVE;
        if (s != null && s.calmGround(x, z)) cir.setReturnValue(NO_STREAM);
    }
}
