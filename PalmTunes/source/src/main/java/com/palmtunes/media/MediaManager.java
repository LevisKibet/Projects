package com.palmtunes.media;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the backend, polls it while the palm panel is open (fast) or the HUD pulse is on (slow), and publishes immutable snapshots that
 * the render thread can read without locking.
 */
public final class MediaManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("PalmTunes/Media");
    private static final MediaManager INSTANCE = new MediaManager(MediaBackends::forCurrentOs);

    public static MediaManager get() {
        return INSTANCE;
    }

    /** Art as the UI sees it. {@code version} changes whenever the picture should be re-uploaded. */
    public record ArtState(long version, DecodedArt art) {
    }

    private final Supplier<MediaBackend> backendFactory;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(daemon("PalmTunes-Media"));
    private final ExecutorService artWorker = Executors.newSingleThreadExecutor(daemon("PalmTunes-Art"));

    private volatile MediaSnapshot snapshot = MediaSnapshot.starting();
    private volatile ArtState artState = new ArtState(0, null);
    /** Background poll rate while only the HUD pulse is watching. */
    private static final long AMBIENT_INTERVAL_MS = 1500;

    private volatile long pollIntervalMs = 1000;
    private volatile boolean remoteArtAllowed = true;

    // Worker-thread state
    private MediaBackend backend;
    private ScheduledFuture<?> pollTask;
    private boolean active;
    private boolean ambient;
    private boolean polledBefore;
    private long scheduledInterval;
    private boolean permanentlyUnavailable;
    private String artTrackKey = "";
    private boolean artResolved;
    private boolean artInFlight;
    private long artVersion;

    MediaManager(Supplier<MediaBackend> backendFactory) {
        this.backendFactory = backendFactory;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------------------------------
    // Public API (any thread)
    // ------------------------------------------------------------------------------------------

    public MediaSnapshot snapshot() {
        return snapshot;
    }

    public ArtState art() {
        return artState;
    }

    public void configure(long pollIntervalMs, boolean remoteArtAllowed) {
        this.pollIntervalMs = Math.max(250, pollIntervalMs);
        this.remoteArtAllowed = remoteArtAllowed;
    }

    /**
     * The palm panel is open: poll at the configured (fast) rate. Opening it again also counts as the
     * user's "retry" after a permanent failure such as a missing helper program.
     */
    public void setActive(boolean value) {
        worker.execute(() -> {
            if (value == active) {
                return;
            }
            active = value;
            if (value) {
                permanentlyUnavailable = false;
            }
            reschedule();
        });
    }

    /**
     * Called once while the game is loading. Starting the helper now means the first real poll is
     * instant (no "connecting" moment), and on macOS the system's permission prompt for controlling
     * Spotify / Music appears at start-up instead of the first time the player opens the panel in-game.
     */
    public void warmUp() {
        worker.execute(() -> {
            pollGuarded();
            polledBefore = true; // later polls refresh this snapshot in place instead of resetting it
        });
    }

    /** The top-of-screen pulse wants to know what is playing: poll slowly in the background. */
    public void setAmbient(boolean value) {
        worker.execute(() -> {
            if (value == ambient) {
                return;
            }
            ambient = value;
            reschedule();
        });
    }

    /** Worker thread. Makes the poll task match what is currently wanted. */
    private void reschedule() {
        long wantedInterval = active ? pollIntervalMs : AMBIENT_INTERVAL_MS;
        boolean wanted = active || ambient;
        if (pollTask != null && (!wanted || wantedInterval != scheduledInterval)) {
            pollTask.cancel(false);
            pollTask = null;
        }
        if (!wanted) {
            polledBefore = false;
        }
        if (wanted && pollTask == null && !permanentlyUnavailable) {
            if (!polledBefore) {
                snapshot = MediaSnapshot.starting();
                polledBefore = true;
            }
            scheduledInterval = wantedInterval;
            pollTask = worker.scheduleWithFixedDelay(this::pollGuarded, 0, wantedInterval, TimeUnit.MILLISECONDS);
        }
    }

    public void send(MediaCommand command, long argMs) {
        worker.execute(() -> {
            if (backend == null) {
                return;
            }
            try {
                backend.send(command, argMs);
            } catch (MediaException e) {
                LOGGER.warn("Media command {} failed: {}", command, e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.warn("Media command {} crashed", command, e);
            }
            // Players apply the change asynchronously; look again shortly so the UI follows.
            worker.schedule(this::pollGuarded, 250, TimeUnit.MILLISECONDS);
            worker.schedule(this::pollGuarded, 900, TimeUnit.MILLISECONDS);
        });
    }

    /** Stops helper processes and waits briefly for it. Called from a JVM shutdown hook. */
    public void shutdownBlocking() {
        try {
            worker.submit(() -> {
                if (pollTask != null) {
                    pollTask.cancel(false);
                }
                if (backend != null) {
                    backend.close();
                    backend = null;
                }
            }).get(1500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // exiting anyway; the helper process ends when its stdin closes
        }
    }

    // ------------------------------------------------------------------------------------------
    // Worker thread
    // ------------------------------------------------------------------------------------------

    private void pollGuarded() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            LOGGER.warn("Unexpected error while polling media session", e);
        }
    }

    private void pollOnce() {
        if (permanentlyUnavailable) {
            return;
        }
        try {
            if (backend == null) {
                backend = backendFactory.get();
                LOGGER.info("Using media backend: {}", backend.name());
            }
            Optional<MediaInfo> info = backend.poll();
            long now = System.nanoTime();
            if (info.isPresent()) {
                snapshot = MediaSnapshot.active(info.get(), now);
                updateArt(info.get());
            } else {
                snapshot = MediaSnapshot.noSession(now);
                resetArt("");
            }
        } catch (MediaException e) {
            if (e.isPermanent()) {
                LOGGER.warn("Media backend unavailable: {}", e.getMessage());
                permanentlyUnavailable = true;
                snapshot = MediaSnapshot.unavailable(e.getMessage(), System.nanoTime());
                if (pollTask != null) {
                    pollTask.cancel(false);
                    pollTask = null;
                }
                if (backend != null) {
                    backend.close();
                    backend = null;
                }
            } else {
                LOGGER.debug("Transient media error: {}", e.getMessage());
            }
        }
    }

    private void updateArt(MediaInfo info) {
        String key = info.trackKey() + '\u001f' + (info.artUrl() == null ? "" : info.artUrl());
        if (!key.equals(artTrackKey)) {
            resetArt(key);
        }
        if (artResolved || artInFlight) {
            return;
        }
        boolean hasBytes = info.artBytes() != null && info.artBytes().length > 0;
        if (!hasBytes && info.artUrl() == null) {
            return; // nothing yet; the backend may supply art on a later poll
        }
        artInFlight = true;
        final String forKey = key;
        final byte[] bytes = info.artBytes();
        final String url = info.artUrl();
        artWorker.execute(() -> {
            DecodedArt decoded = null;
            try {
                byte[] data = bytes;
                if (data == null || data.length == 0) {
                    data = ArtFetcher.fetch(url, remoteArtAllowed);
                }
                decoded = ArtDecoder.decode(data);
            } catch (IOException e) {
                LOGGER.debug("Could not load cover art: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOGGER.debug("Could not decode cover art", e);
            }
            final DecodedArt result = decoded;
            worker.execute(() -> finishArt(forKey, result));
        });
    }

    private void finishArt(String key, DecodedArt decoded) {
        artInFlight = false;
        if (!key.equals(artTrackKey)) {
            return; // the track changed while we were decoding
        }
        artResolved = true;
        artState = new ArtState(++artVersion, decoded);
    }

    private void resetArt(String newKey) {
        boolean changed = !newKey.equals(artTrackKey);
        artTrackKey = newKey;
        artResolved = false;
        if (changed && artState.art() != null) {
            artState = new ArtState(++artVersion, null);
        }
    }
}
