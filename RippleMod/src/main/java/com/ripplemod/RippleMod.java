package com.ripplemod;

import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Water ripples are purely visual and handled on the client, so the server does no work at all
 * and the mod can be used on servers that do not have it.
 */
@Mod(RippleMod.MOD_ID)
public class RippleMod {
    public static final String MOD_ID = "ripplemod";

    public RippleMod() {
        ModLoadingContext ctx = ModLoadingContext.get();
        ctx.registerConfig(ModConfig.Type.CLIENT, RippleConfig.SPEC);
        ctx.registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY, (remote, isServer) -> true));
    }
}
