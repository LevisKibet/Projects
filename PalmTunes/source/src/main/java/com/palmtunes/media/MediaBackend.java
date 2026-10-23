package com.palmtunes.media;

import java.util.Optional;

/**
 * Talks to whatever media-session service the operating system offers.
 * <p>
 * All methods are called from a single worker thread, one at a time, so implementations do not
 * need to be thread-safe.
 */
public interface MediaBackend {

    /** Short human-readable name, used in logs. */
    String name();

    /**
     * Looks up the session that should be shown (a playing one if several exist).
     *
     * @return the session, or empty if nothing is playing or paused anywhere
     */
    Optional<MediaInfo> poll() throws MediaException;

    /**
     * Sends a transport command to the same session {@link #poll()} would pick.
     *
     * @param argMs only used by {@link MediaCommand#SEEK}: the target position in milliseconds
     */
    void send(MediaCommand command, long argMs) throws MediaException;

    /** Releases helper processes. Must be safe to call more than once. */
    default void close() {
    }
}
