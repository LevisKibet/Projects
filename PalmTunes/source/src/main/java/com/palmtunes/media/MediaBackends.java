package com.palmtunes.media;

import com.palmtunes.media.backend.LinuxBackend;
import com.palmtunes.media.backend.MacBackend;
import com.palmtunes.media.backend.WindowsBackend;
import java.util.Locale;

/** Picks the backend that matches the operating system. */
public final class MediaBackends {
    private MediaBackends() {
    }

    public static MediaBackend forCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsBackend();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new MacBackend();
        }
        return new LinuxBackend();
    }
}
