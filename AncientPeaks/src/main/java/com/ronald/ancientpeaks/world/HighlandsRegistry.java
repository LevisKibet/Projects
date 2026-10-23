package com.ronald.ancientpeaks.world;

import net.minecraft.world.level.levelgen.NoiseRouter;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Maps a world's noise router to its island sampler (weak, so old worlds are forgotten). */
public final class HighlandsRegistry {
    private static final Map<NoiseRouter, IslandSampler> MAP = Collections.synchronizedMap(new WeakHashMap<>());
    public static void put(NoiseRouter router, IslandSampler s) { MAP.put(router, s); }
    public static IslandSampler get(NoiseRouter router) { return MAP.get(router); }
}
