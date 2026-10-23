package com.palmtunes.media.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palmtunes.media.PlaybackState;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserTabsTest {
    private static final String US = "\u001f";
    private static final BrowserTabs.Browser CHROME = BrowserTabs.BROWSERS.get(0);

    @Test
    void parsesPlayingTab() {
        String line = "1" + US + "3" + US + "Song - YouTube Music" + US + "https://music.youtube.com/watch?v=x" + US
                + "playing" + US + "Song" + US + "Artist" + US + "Album" + US + "200.5" + US + "12.25" + US + "https://img/x.jpg";
        List<BrowserTabs.Tab> tabs = BrowserTabs.parse(CHROME, line + "\n");
        assertEquals(1, tabs.size());
        var t = tabs.get(0);
        assertEquals(3, t.tab());
        assertEquals("YouTube Music", t.info().sourceApp());
        assertEquals(PlaybackState.PLAYING, t.info().state());
        assertEquals("Artist", t.info().artist());
        assertEquals(200500, t.info().durationMs());
        assertEquals(12250, t.info().positionMs());
        assertEquals("https://img/x.jpg", t.info().artUrl());
    }

    @Test
    void flagsDisabledJavascriptAndSkipsEmptyTabs() {
        String denied = "1" + US + "1" + US + "x" + US + "https://www.youtube.com/watch?v=1" + US
                + "ERR:Executing JavaScript through AppleScript is turned off. Allow JavaScript from Apple Events";
        String empty = "1" + US + "2" + US + "x" + US + "https://soundcloud.com/a" + US;
        List<BrowserTabs.Tab> tabs = BrowserTabs.parse(CHROME, denied + "\n" + empty + "\n");
        assertEquals(1, tabs.size());
        assertTrue(tabs.get(0).jsDenied());
    }

    @Test
    void parsesShuffleRepeatAndQueue() {
        String line = "1" + US + "1" + US + "x" + US + "https://music.youtube.com/watch?v=x" + US
                + "playing" + US + "Song" + US + "Artist" + US + "" + US + "100" + US + "1" + US + "" + US
                + "1" + US + "2" + US + "A - B\u001eC - D";
        var info = BrowserTabs.parse(CHROME, line).get(0).info();
        assertEquals(1, info.shuffle());
        assertEquals(2, info.repeat());
        assertEquals(List.of("A - B", "C - D"), info.queue());
    }
}
