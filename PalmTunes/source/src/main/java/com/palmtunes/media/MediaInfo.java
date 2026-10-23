package com.palmtunes.media;

/**
 * One poll's worth of information about the active media session.
 *
 * @param title      track title, never null (may be empty)
 * @param artist     artist, never null (may be empty)
 * @param album      album, never null (may be empty)
 * @param sourceApp  friendly name of the app/browser playing it, never null (may be empty)
 * @param state      playing / paused / stopped
 * @param positionMs playback position at the moment of the poll, or -1 if unknown
 * @param durationMs track length, or -1 if unknown (live streams)
 * @param artUrl     http(s) or file URL of the cover art, or null
 * @param artBytes   raw encoded cover art (PNG/JPEG...) if the backend handed it over directly, or null
 * @param shuffle    -1 unknown / unsupported, 0 off, 1 on
 * @param repeat     -1 unknown / unsupported, 0 off, 1 repeat all, 2 repeat one
 * @param queue      the next few tracks ("Title - Artist"), empty if the source does not expose a queue
 */
public record MediaInfo(
        String title,
        String artist,
        String album,
        String sourceApp,
        PlaybackState state,
        long positionMs,
        long durationMs,
        String artUrl,
        byte[] artBytes,
        int shuffle,
        int repeat,
        java.util.List<String> queue) {

    /** Without shuffle, repeat or queue information. */
    public MediaInfo(String title, String artist, String album, String sourceApp, PlaybackState state,
                     long positionMs, long durationMs, String artUrl, byte[] artBytes) {
        this(title, artist, album, sourceApp, state, positionMs, durationMs, artUrl, artBytes, -1, -1, java.util.List.of());
    }

    /** A copy with new art fields. */
    public MediaInfo withArt(String url, byte[] bytes) {
        return new MediaInfo(title, artist, album, sourceApp, state, positionMs, durationMs, url, bytes, shuffle, repeat, queue);
    }

    public MediaInfo {
        title = title == null ? "" : title;
        artist = artist == null ? "" : artist;
        album = album == null ? "" : album;
        sourceApp = sourceApp == null ? "" : sourceApp;
        state = state == null ? PlaybackState.STOPPED : state;
        queue = queue == null ? java.util.List.of() : java.util.List.copyOf(queue);
    }

    /** Identity of the track, ignoring playback state and position. */
    public String trackKey() {
        return title + '\u001f' + artist + '\u001f' + album + '\u001f' + sourceApp;
    }

    public boolean hasKnownDuration() {
        return durationMs > 0;
    }
}
