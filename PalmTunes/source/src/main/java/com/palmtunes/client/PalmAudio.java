package com.palmtunes.client;

import com.palmtunes.PalmConfig;
import com.palmtunes.media.MediaManager;
import com.palmtunes.media.MediaSnapshot;
import com.palmtunes.media.PlaybackState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.client.event.sound.PlaySoundEvent;

/**
 * Keeps Minecraft's own background music quiet while something else is playing on the computer.
 * It never touches the user's volume sliders: it just stops the game from starting music tracks.
 */
final class PalmAudio {
    /** External music must be playing this long before we stop the game's music (ignores brief blips). */
    private static final long ENGAGE_MS = 600;
    /** ...and must have been silent this long before the game's music may come back (covers track gaps). */
    private static final long RELEASE_MS = 4000;

    private static boolean muting;
    private static long playingSince = -1;
    private static long silentSince = -1;

    private PalmAudio() {
    }

    static boolean isMuting() {
        return muting;
    }

    /** Client tick. */
    static void tick() {
        if (!PalmConfig.CLIENT.muteGameMusic.get()) {
            muting = false;
            playingSince = silentSince = -1;
            return;
        }
        MediaSnapshot snap = MediaManager.get().snapshot();
        boolean playing = snap.kind() == MediaSnapshot.Kind.ACTIVE && snap.info() != null
                && snap.info().state() == PlaybackState.PLAYING;
        long now = System.currentTimeMillis();
        if (playing) {
            silentSince = -1;
            if (playingSince < 0) {
                playingSince = now;
            }
            if (!muting && now - playingSince >= ENGAGE_MS) {
                muting = true;
                // Fade-less but immediate: stop whatever game track is currently running.
                Minecraft.getInstance().getMusicManager().stopPlaying();
            }
        } else {
            playingSince = -1;
            if (silentSince < 0) {
                silentSince = now;
            }
            if (muting && now - silentSince >= RELEASE_MS) {
                muting = false;
            }
        }
    }

    /** Cancels game music (the MUSIC sound category) while muting. Jukebox discs and effects are untouched. */
    static void onPlaySound(PlaySoundEvent event) {
        if (!muting) {
            return;
        }
        SoundInstance sound = event.getSound();
        if (sound != null && sound.getSource() == SoundSource.MUSIC) {
            event.setSound(null);
        }
    }
}
