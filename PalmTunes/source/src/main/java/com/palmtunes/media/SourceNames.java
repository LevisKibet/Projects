package com.palmtunes.media;

import java.util.Locale;

/** Turns raw app identifiers ("Spotify.exe", "chromium.instance4242") into names people recognise. */
public final class SourceNames {
    private SourceNames() {
    }

    public static String friendly(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT);

        // Dedicated music apps first: they are the interesting ones.
        if (s.contains("spotify")) return "Spotify";
        if (s.contains("applemusic") || s.contains("apple music") || s.equals("music") || s.contains("itunes")) return "Apple Music";
        if (s.contains("deezer")) return "Deezer";
        if (s.contains("youtubemusic") || s.contains("youtube-music") || s.contains("youtube music") || s.contains("th-ch")) return "YouTube Music";
        if (s.contains("tidal")) return "TIDAL";
        if (s.contains("amazonmusic") || s.contains("amazon music")) return "Amazon Music";
        if (s.contains("soundcloud")) return "SoundCloud";
        if (s.contains("vlc")) return "VLC";
        if (s.contains("foobar")) return "foobar2000";
        if (s.contains("musicbee")) return "MusicBee";
        if (s.contains("rhythmbox")) return "Rhythmbox";
        if (s.contains("strawberry")) return "Strawberry";
        if (s.contains("amberol")) return "Amberol";
        if (s.contains("lollypop")) return "Lollypop";
        if (s.contains("mpd")) return "MPD";

        // Browsers: could be YouTube Music, Deezer web, SoundCloud... we cannot tell which tab.
        if (s.contains("chrome")) return "Chrome";
        if (s.contains("msedge") || s.contains("edge")) return "Edge";
        if (s.contains("firefox")) return "Firefox";
        if (s.contains("brave")) return "Brave";
        if (s.contains("vivaldi")) return "Vivaldi";
        if (s.contains("opera")) return "Opera";
        if (s.contains("chromium")) return "Chromium";
        if (s.contains("safari")) return "Safari";
        if (s.equals("zen") || s.startsWith("zen.")) return "Zen";

        return prettify(raw);
    }

    /** "SomeVendor.Player.exe!App" -> "Player". Best effort for apps we do not know by name. */
    private static String prettify(String raw) {
        String s = raw;
        int bang = s.indexOf('!');
        if (bang > 0) s = s.substring(0, bang);
        if (s.toLowerCase(Locale.ROOT).endsWith(".exe")) s = s.substring(0, s.length() - 4);
        int underscore = s.indexOf('_');
        if (underscore > 0) s = s.substring(0, underscore);
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot < s.length() - 1) s = s.substring(dot + 1);
        // instance suffixes from MPRIS: "vlc.instance1234"
        s = s.replaceAll("(?i)instance\\d+$", "");
        s = s.replaceAll("[._-]+$", "");
        if (s.isBlank()) return raw;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
