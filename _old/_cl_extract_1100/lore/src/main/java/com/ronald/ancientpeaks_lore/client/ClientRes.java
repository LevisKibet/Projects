package com.ronald.ancientpeaks_lore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Does a client resource exist? Remembered until the next resource reload (F3+T). Client side only. */
public final class ClientRes {
    private ClientRes() {}
    private static final Map<String, Boolean> SEEN = new ConcurrentHashMap<>();

    public static boolean exists(String id) {
        return SEEN.computeIfAbsent(id, k -> Minecraft.getInstance().getResourceManager().getResource(new ResourceLocation(k)).isPresent());
    }

    static void forget() { SEEN.clear(); }
}
