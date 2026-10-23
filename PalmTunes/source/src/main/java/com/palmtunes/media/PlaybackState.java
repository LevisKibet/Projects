package com.palmtunes.media;

/** Coarse playback state shared by every backend. */
public enum PlaybackState {
    PLAYING,
    PAUSED,
    STOPPED;

    /** Maps the many spellings backends use ("Playing", "playing", "PLAYING", "paused"...) onto the enum. */
    public static PlaybackState parse(String raw) {
        if (raw == null) {
            return STOPPED;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "playing", "play", "started" -> PLAYING;
            case "paused", "pause" -> PAUSED;
            default -> STOPPED;
        };
    }
}
