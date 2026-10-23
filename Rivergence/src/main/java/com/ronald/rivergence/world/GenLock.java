package com.ronald.rivergence.world;

import com.ronald.rivergence.Rivergence;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Keeps a world's water the same from its first day to its last. The first time a world is opened with Rivergence,
 * the generation version and every setting that shapes the land are written into the world folder
 * (rivergence-generation.properties); after that the world always generates with those, whatever the config file or
 * a later version of the mod says, so new chunks keep meeting old ones without seams.
 *
 * For future updates: any change to how water or terrain is generated must keep the old behaviour for worlds whose
 * {@link #version()} is lower than {@link #CURRENT}, and only use the new behaviour for worlds made with it.
 */
@Mod.EventBusSubscriber(modid = Rivergence.MODID)
public final class GenLock {
    private GenLock() {}

    /** The generation this build makes for new worlds. Bump it (and branch on version()) whenever generation changes. */
    public static final int CURRENT = 1;

    private static volatile Properties locked;

    /** The generation version of the world being played (CURRENT when none is open). */
    public static int version() {
        Properties p = locked;
        if (p == null) return CURRENT;
        try { return Integer.parseInt(p.getProperty("generation_version", "1")); } catch (NumberFormatException e) { return 1; }
    }

    /** A setting fixed for this world, or the given live value when no world is open or it wasn't recorded. */
    public static boolean get(String key, boolean live) {
        Properties p = locked;
        if (p == null || !p.containsKey(key)) return live;
        return Boolean.parseBoolean(p.getProperty(key));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void start(ServerAboutToStartEvent e) {
        locked = null;
        try {
            Path root = e.getServer().getWorldPath(LevelResource.ROOT);
            Path file = root.resolve("rivergence-generation.properties");
            Properties p = new Properties();
            if (Files.exists(file)) {
                try (var in = Files.newBufferedReader(file)) { p.load(in); }
                Rivergence.LOGGER.info("Rivergence: this world keeps generation version {} and its own water settings", p.getProperty("generation_version"));
            } else {
                // a new world (or one from before 1.0): fix today's settings for it
                p.setProperty("generation_version", String.valueOf(CURRENT));
                com.ronald.rivergence.RivergenceConfig.recordGeneration(p);
                try (var out = Files.newBufferedWriter(file)) {
                    p.store(out, "Rivergence: how this world's water is generated. Fixed when the world was first opened so that\n"
                            + "new chunks always match old ones. Edit only if you accept seams where old and new terrain meet.");
                }
                Rivergence.LOGGER.info("Rivergence: generation settings fixed for this world (version {})", CURRENT);
            }
            locked = p;
        } catch (Exception ex) {
            Rivergence.LOGGER.warn("Rivergence: couldn't read or write the world's generation settings; using the config", ex);
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppedEvent e) { locked = null; }
}
