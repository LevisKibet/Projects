package com.ronald.ancientpeaks_lore;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Where Streams Reflowing will run its streams, so the castle and the houses can stand clear of them (inside a
 * structure the stream is not carved, so one crossing a footprint would stop dead at the wall and spill).
 * Optional: without Streams Reflowing, or before its engine is up, nothing counts as a stream.
 */
final class Streams {
    interface Corridors { boolean at(int x, int z); }
    static final Corridors NONE = (x, z) -> false;

    private static boolean looked;
    private static Method active, readyNear, inCorridor;

    private static synchronized void look() {
        if (looked) return;
        looked = true;
        try {
            Class<?> wg = Class.forName("dev.streamsreflowing.worldgen.StreamsWorldgen");
            Class<?> eng = Class.forName("dev.streamsreflowing.core.RiverEngine");
            active = wg.getMethod("active");
            readyNear = eng.getMethod("whenPlatesReadyNear", double.class, double.class, double.class);
            inCorridor = eng.getMethod("inCorridor", int.class, int.class);
        } catch (Throwable t) {
            active = null;
        }
    }

    /** The stream corridors (channel plus banks) within r blocks of (x, z), waiting for that ground to be worked out. */
    static Corridors near(int x, int z, double r) {
        look();
        if (active == null) return NONE;
        try {
            Object e = active.invoke(null);
            if (e == null) return NONE;
            ((CompletableFuture<?>) readyNear.invoke(e, (double) x, (double) z, r)).get(120, TimeUnit.SECONDS);
            return (px, pz) -> {
                try { return (Boolean) inCorridor.invoke(e, px, pz); } catch (Throwable t) { return false; }
            };
        } catch (Throwable t) {
            AncientPeaksLore.LOGGER.warn("[ancientpeaks_lore] couldn't ask Streams Reflowing where its streams run near {},{}: {}", x, z, t.toString());
            return NONE;
        }
    }
}
