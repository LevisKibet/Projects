package com.palmtunes.media.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palmtunes.media.MediaException;
import com.palmtunes.media.MediaInfo;
import com.palmtunes.media.PlaybackState;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackendParsingTest {
    private static final String US = "\u001f";

    // ---- Linux ------------------------------------------------------------------------------

    @Test
    void linuxParsesPlayerctlLine() {
        String out = String.join(US, "spotify", "Playing", "Blinding Lights", "The Weeknd", "After Hours",
                "https://i.scdn.co/image/abc", "200040000", "61500000") + "\n";
        List<LinuxBackend.Candidate> list = LinuxBackend.parse(out);
        assertEquals(1, list.size());
        MediaInfo info = list.get(0).info();
        assertEquals("Blinding Lights", info.title());
        assertEquals("The Weeknd", info.artist());
        assertEquals("Spotify", info.sourceApp());
        assertEquals(PlaybackState.PLAYING, info.state());
        assertEquals(200040, info.durationMs());
        assertEquals(61500, info.positionMs());
        assertEquals("https://i.scdn.co/image/abc", info.artUrl());
    }

    @Test
    void linuxKeepsEmptyFieldsAndUnicode() {
        String out = String.join(US, "chromium.instance123", "Paused", "夜に駆ける", "", "", "", "", "") + "\n";
        List<LinuxBackend.Candidate> list = LinuxBackend.parse(out);
        assertEquals(1, list.size());
        MediaInfo info = list.get(0).info();
        assertEquals("夜に駆ける", info.title());
        assertEquals("", info.artist());
        assertEquals("Chromium", info.sourceApp());
        assertEquals(-1, info.durationMs());
        assertNull(info.artUrl());
    }

    @Test
    void linuxPrefersPlayingOverPaused() {
        String paused = String.join(US, "vlc", "Paused", "A", "x", "y", "", "1000000", "0");
        String playing = String.join(US, "firefox", "Playing", "B", "x", "y", "", "1000000", "0");
        var picked = LinuxBackend.pick(LinuxBackend.parse(paused + "\n" + playing + "\n"));
        assertTrue(picked.isPresent());
        assertEquals("firefox", picked.get().instance());
    }

    @Test
    void linuxIgnoresMalformedLines() {
        assertTrue(LinuxBackend.parse("No players found\n").isEmpty());
    }

    // ---- macOS ------------------------------------------------------------------------------

    @Test
    void macSpotifyDurationIsMilliseconds() {
        String line = String.join(US, "playing", "Song", "Artist", "Album", "215000", "12.5", "https://i.scdn.co/image/x");
        MediaInfo info = MacBackend.parseAppLine("Spotify", line);
        assertNotNull(info);
        assertEquals(215000, info.durationMs());
        assertEquals(12500, info.positionMs());
        assertEquals("Spotify", info.sourceApp());
        assertEquals(PlaybackState.PLAYING, info.state());
    }

    @Test
    void macMusicDurationIsSecondsAndDecimalCommaWorks() {
        String line = String.join(US, "paused", "Song", "Artist", "Album", "215,5", "3,25", "");
        MediaInfo info = MacBackend.parseAppLine("Music", line);
        assertNotNull(info);
        assertEquals(215500, info.durationMs());
        assertEquals(3250, info.positionMs());
        assertEquals("Apple Music", info.sourceApp());
        assertEquals(PlaybackState.PAUSED, info.state());
        assertNull(info.artUrl());
    }

    @Test
    void macEmptyScriptOutputMeansNothingPlaying() {
        assertNull(MacBackend.parseAppLine("Spotify", ""));
    }

    @Test
    void macNowPlayingCli() {
        MediaInfo info = MacBackend.parseNowPlaying("Title\nArtist\nAlbum\n180.2\n42.5\n1\n");
        assertNotNull(info);
        assertEquals("Title", info.title());
        assertEquals(180200, info.durationMs());
        assertEquals(42500, info.positionMs());
        assertEquals(PlaybackState.PLAYING, info.state());
        assertNull(MacBackend.parseNowPlaying("null\nnull\nnull\nnull\nnull\nnull\n"));
    }

    // ---- Windows ----------------------------------------------------------------------------

    @Test
    void windowsParsesStateAndArt() throws Exception {
        String art = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        String json = "{\"title\":\"Song\",\"artist\":\"Band\",\"album\":\"LP\",\"app\":\"Spotify.exe\","
                + "\"status\":\"Playing\",\"positionMs\":1000,\"durationMs\":180000,\"seq\":3,\"artFinal\":true,\"art\":\""
                + art + "\"}";
        WindowsBackend.ParsedState parsed = WindowsBackend.parseState(json);
        assertNotNull(parsed.info());
        assertEquals("Spotify", parsed.info().sourceApp());
        assertEquals(PlaybackState.PLAYING, parsed.info().state());
        assertEquals(3, parsed.seq());
        assertTrue(parsed.artDelivered());
        assertEquals(3, parsed.info().artBytes().length);
    }

    @Test
    void windowsArtNotYetFinalIsNotMarkedDelivered() throws Exception {
        String json = "{\"title\":\"Song\",\"artist\":\"Band\",\"album\":\"\",\"app\":\"chrome.exe\","
                + "\"status\":\"Paused\",\"positionMs\":-1,\"durationMs\":-1,\"seq\":4,\"artFinal\":false,\"art\":\"\"}";
        WindowsBackend.ParsedState parsed = WindowsBackend.parseState(json);
        assertEquals("Chrome", parsed.info().sourceApp());
        assertEquals(PlaybackState.PAUSED, parsed.info().state());
        assertTrue(!parsed.artDelivered());
        assertNull(parsed.info().artBytes());
    }

    @Test
    void windowsNoSessionAndErrors() throws Exception {
        assertNull(WindowsBackend.parseState("{\"none\":true}").info());
        assertThrows(MediaException.class, () -> WindowsBackend.parseState("{\"error\":\"boom\"}"));
        assertThrows(MediaException.class, () -> WindowsBackend.parseState("not json"));
    }

    @Test
    void windowsStoppedWithoutMetadataIsNoSession() throws Exception {
        String json = "{\"title\":\"\",\"artist\":\"\",\"album\":\"\",\"app\":\"x\",\"status\":\"Stopped\","
                + "\"positionMs\":0,\"durationMs\":-1,\"seq\":1,\"artFinal\":true,\"art\":\"\"}";
        assertNull(WindowsBackend.parseState(json).info());
    }
}
