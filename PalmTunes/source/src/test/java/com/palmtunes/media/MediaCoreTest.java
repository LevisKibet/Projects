package com.palmtunes.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palmtunes.client.PalmLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MediaCoreTest {

    @Test
    void friendlyNames() {
        assertEquals("Spotify", SourceNames.friendly("Spotify.exe"));
        assertEquals("Apple Music", SourceNames.friendly("AppleInc.AppleMusicWin_nzyj5cx40ttqa!App"));
        assertEquals("Apple Music", SourceNames.friendly("Music"));
        assertEquals("Chrome", SourceNames.friendly("chrome.exe"));
        assertEquals("Firefox", SourceNames.friendly("firefox.instance4242"));
        assertEquals("Deezer", SourceNames.friendly("com.deezer.Deezer"));
        assertEquals("Mymusicplayer", SourceNames.friendly("Mymusicplayer.exe"));
        assertEquals("", SourceNames.friendly(""));
    }

    @Test
    void playbackStateParsing() {
        assertEquals(PlaybackState.PLAYING, PlaybackState.parse("Playing"));
        assertEquals(PlaybackState.PAUSED, PlaybackState.parse(" paused "));
        assertEquals(PlaybackState.STOPPED, PlaybackState.parse("Closed"));
        assertEquals(PlaybackState.STOPPED, PlaybackState.parse(null));
    }

    @Test
    void positionExtrapolatesOnlyWhilePlaying() {
        long t0 = 1_000_000_000L;
        MediaInfo playing = new MediaInfo("t", "a", "b", "s", PlaybackState.PLAYING, 10_000, 100_000, null, null);
        MediaSnapshot snap = new MediaSnapshot(MediaSnapshot.Kind.ACTIVE, "", playing, t0);
        assertEquals(12_500, snap.positionNowMs(t0 + 2_500_000_000L));
        // clamped to the track length
        assertEquals(100_000, snap.positionNowMs(t0 + 999_000_000_000L));

        MediaInfo paused = new MediaInfo("t", "a", "b", "s", PlaybackState.PAUSED, 10_000, 100_000, null, null);
        MediaSnapshot snap2 = new MediaSnapshot(MediaSnapshot.Kind.ACTIVE, "", paused, t0);
        assertEquals(10_000, snap2.positionNowMs(t0 + 5_000_000_000L));

        MediaInfo unknown = new MediaInfo("t", "a", "b", "s", PlaybackState.PLAYING, -1, -1, null, null);
        assertEquals(-1, new MediaSnapshot(MediaSnapshot.Kind.ACTIVE, "", unknown, t0).positionNowMs(t0 + 1));
    }

    @Test
    void layoutHitTestAndTime() {
        assertEquals(PalmLayout.Hit.PLAY_PAUSE, PalmLayout.hitTest(PalmLayout.PLAY_X, PalmLayout.BUTTON_Y));
        assertEquals(PalmLayout.Hit.PREVIOUS, PalmLayout.hitTest(PalmLayout.PREV_X, PalmLayout.BUTTON_Y));
        assertEquals(PalmLayout.Hit.NEXT, PalmLayout.hitTest(PalmLayout.NEXT_X, PalmLayout.BUTTON_Y));
        assertEquals(PalmLayout.Hit.SEEK, PalmLayout.hitTest(PalmLayout.BAR_X + 50, PalmLayout.BAR_Y + 1));
        assertEquals(PalmLayout.Hit.CLOSE, PalmLayout.hitTest(PalmLayout.CLOSE_X, PalmLayout.CLOSE_Y));
        assertEquals(PalmLayout.Hit.NONE, PalmLayout.hitTest(-5, 10));
        assertEquals(PalmLayout.Hit.NONE, PalmLayout.hitTest(PalmLayout.ART_X + 5, PalmLayout.ART_Y + 5));
        assertEquals(0f, PalmLayout.barFraction(-100f));
        assertEquals(1f, PalmLayout.barFraction(10_000f));
        assertEquals("3:05", PalmLayout.formatTime(185_000));
        assertEquals("1:02:03", PalmLayout.formatTime(3_723_000));
        assertEquals("--:--", PalmLayout.formatTime(-1));
    }

    @Test
    void artDecoderCropsRoundsAndPicksAccent() throws Exception {
        BufferedImage src = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 400; x++) {
                src.setRGB(x, y, 0xE03030); // vivid red
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(src, "png", png);

        DecodedArt art = ArtDecoder.decode(png.toByteArray());
        assertEquals(ArtDecoder.SIZE, art.size());
        int s = art.size();
        assertEquals(0, art.argb()[0] >>> 24, "corner must be transparent");
        assertEquals(255, art.argb()[(s / 2) * s + s / 2] >>> 24, "centre must be opaque");
        int r = (art.accentRgb() >> 16) & 0xFF;
        int g = (art.accentRgb() >> 8) & 0xFF;
        assertTrue(r > g + 60, "accent should stay reddish");
    }

    @Test
    void artDecoderRejectsGarbage() {
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> ArtDecoder.decode(new byte[]{1, 2, 3}));
    }

    // ---- manager state machine with a fake backend ----------------------------------------------

    private static final class FakeBackend implements MediaBackend {
        final List<MediaCommand> commands = new ArrayList<>();
        final AtomicReference<Object> next = new AtomicReference<>(Optional.empty());

        @Override
        public String name() {
            return "fake";
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<MediaInfo> poll() throws MediaException {
            Object o = next.get();
            if (o instanceof MediaException e) throw e;
            return (Optional<MediaInfo>) o;
        }

        @Override
        public synchronized void send(MediaCommand command, long argMs) {
            commands.add(command);
        }
    }

    private static MediaSnapshot await(MediaManager m, MediaSnapshot.Kind kind) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (m.snapshot().kind() == kind) return m.snapshot();
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + kind + " but was " + m.snapshot().kind());
    }

    @Test
    void managerPublishesSessionsAndCommands() throws Exception {
        FakeBackend fake = new FakeBackend();
        MediaManager manager = new MediaManager(() -> fake);
        manager.configure(250, false);
        manager.setActive(true);

        await(manager, MediaSnapshot.Kind.NO_SESSION);

        fake.next.set(Optional.of(new MediaInfo("Song", "Band", "LP", "Spotify", PlaybackState.PLAYING, 0, 1000, null, null)));
        MediaSnapshot active = await(manager, MediaSnapshot.Kind.ACTIVE);
        assertEquals("Song", active.info().title());

        manager.send(MediaCommand.NEXT, 0);
        for (int i = 0; i < 40 && fake.commands.isEmpty(); i++) Thread.sleep(50);
        assertEquals(List.of(MediaCommand.NEXT), fake.commands);

        fake.next.set(Optional.empty());
        await(manager, MediaSnapshot.Kind.NO_SESSION);
        manager.setActive(false);
        manager.shutdownBlocking();
    }

    @Test
    void ambientPollingWorksWithoutThePanelAndStopsWhenBothAreOff() throws Exception {
        FakeBackend fake = new FakeBackend();
        fake.next.set(Optional.of(new MediaInfo("Song", "Band", "LP", "Spotify", PlaybackState.PLAYING, 0, 1000, null, null)));
        MediaManager manager = new MediaManager(() -> fake);
        manager.configure(250, false);
        manager.setAmbient(true);
        await(manager, MediaSnapshot.Kind.ACTIVE);

        // opening and closing the panel on top of the ambient poll must not drop the session
        manager.setActive(true);
        Thread.sleep(300);
        manager.setActive(false);
        Thread.sleep(300);
        assertEquals(MediaSnapshot.Kind.ACTIVE, manager.snapshot().kind());

        manager.setAmbient(false);
        Thread.sleep(200);
        fake.next.set(Optional.empty());
        Thread.sleep(2000); // longer than the ambient interval: nothing may poll any more
        assertEquals(MediaSnapshot.Kind.ACTIVE, manager.snapshot().kind());
        manager.shutdownBlocking();
    }

    @Test
    void permanentFailureBecomesUnavailableWithMessage() throws Exception {
        FakeBackend fake = new FakeBackend();
        fake.next.set(MediaException.permanent("playerctl not found"));
        MediaManager manager = new MediaManager(() -> fake);
        manager.configure(250, false);
        manager.setActive(true);
        MediaSnapshot snap = await(manager, MediaSnapshot.Kind.UNAVAILABLE);
        assertEquals("playerctl not found", snap.message());
        assertNull(snap.info());
        manager.setActive(false);
        manager.shutdownBlocking();
    }

    @Test
    void artFromBytesReachesTheUi() throws Exception {
        BufferedImage src = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(src, "png", png);

        FakeBackend fake = new FakeBackend();
        fake.next.set(Optional.of(new MediaInfo("Song", "Band", "LP", "Spotify", PlaybackState.PLAYING, 0, 1000,
                null, png.toByteArray())));
        MediaManager manager = new MediaManager(() -> fake);
        manager.configure(250, false);
        manager.setActive(true);
        MediaManager.ArtState art = null;
        for (int i = 0; i < 100; i++) {
            art = manager.art();
            if (art.art() != null) break;
            Thread.sleep(50);
        }
        assertNotNull(art);
        assertNotNull(art.art(), "decoded art should be published");
        manager.setActive(false);
        manager.shutdownBlocking();
    }
}
