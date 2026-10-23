package com.palmtunes.media;

/**
 * Immutable view of the media world, published by the {@link MediaManager} for the render thread.
 */
public record MediaSnapshot(Kind kind, String message, MediaInfo info, long polledAtNanos) {

    public enum Kind {
        /** No poll has completed yet. */
        STARTING,
        /** A session exists; {@link #info()} is non-null. */
        ACTIVE,
        /** The backend works but nothing is playing. */
        NO_SESSION,
        /** The backend cannot work on this machine; {@link #message()} says why. */
        UNAVAILABLE
    }

    public static MediaSnapshot starting() {
        return new MediaSnapshot(Kind.STARTING, "", null, System.nanoTime());
    }

    public static MediaSnapshot active(MediaInfo info, long nowNanos) {
        return new MediaSnapshot(Kind.ACTIVE, "", info, nowNanos);
    }

    public static MediaSnapshot noSession(long nowNanos) {
        return new MediaSnapshot(Kind.NO_SESSION, "", null, nowNanos);
    }

    public static MediaSnapshot unavailable(String message, long nowNanos) {
        return new MediaSnapshot(Kind.UNAVAILABLE, message == null ? "" : message, null, nowNanos);
    }

    /**
     * Playback position right now, extrapolated from the last poll while playing.
     *
     * @return milliseconds, or -1 if unknown
     */
    public long positionNowMs(long nowNanos) {
        if (info == null || info.positionMs() < 0) {
            return -1;
        }
        long pos = info.positionMs();
        if (info.state() == PlaybackState.PLAYING) {
            pos += Math.max(0, (nowNanos - polledAtNanos) / 1_000_000L);
        }
        if (info.hasKnownDuration()) {
            pos = Math.min(pos, info.durationMs());
        }
        return Math.max(0, pos);
    }
}
