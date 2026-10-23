package com.ronald.ancientpeaks;

import net.minecraftforge.fml.ModList;

/**
 * Highland Gateways are optional: they need the Immersive Gateways mod, and even with it installed a server can turn
 * them off in config/ancientpeaks-common.toml (see RuinsConfig). Without them there are no gateway ruins: the island is reached by sea.
 */
public final class Gateways {
    private Gateways() {}
    public static final String MOD = "immersive_gateways";

    /** Is Immersive Gateways installed? */
    public static boolean modPresent() { return ModList.get() != null && ModList.get().isLoaded(MOD); }

    /** Do Highland Gateways exist in this game (mod installed and not switched off)? */
    public static boolean enabled() {
        if (!modPresent()) return false;
        return RuinsConfig.on(RuinsConfig.GATEWAYS, true);
    }
}
