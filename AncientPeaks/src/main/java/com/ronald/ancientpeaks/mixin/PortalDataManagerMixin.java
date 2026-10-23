package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.compat.GatewayLink;
import net.conczin.immersive_gateways.data.PortalDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PortalDataManager.class, remap = false)
public abstract class PortalDataManagerMixin {
    @Inject(method = "search", at = @At("HEAD"), cancellable = true)
    private static void ancientpeaks$toIsland(ServerLevel level, BlockPos pos, boolean generate, CallbackInfoReturnable<PortalDataManager.PortalPair> cir) {
        if (!generate || !com.ronald.ancientpeaks.Gateways.enabled() || com.ronald.ancientpeaks.AncientPeaks.loreLoaded() || PortalDataManager.getState(level).search(pos) != null) return;
        PortalDataManager.PortalPair pair = GatewayLink.link(level, pos);
        if (pair != null) cir.setReturnValue(pair);
    }
}
