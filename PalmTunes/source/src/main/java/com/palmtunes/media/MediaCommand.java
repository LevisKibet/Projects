package com.palmtunes.media;

/** Things the UI can ask the active media session to do. */
public enum MediaCommand {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    /** Jump to an absolute position; the argument is in milliseconds. */
    SEEK,
    /** Switch shuffle on or off. */
    TOGGLE_SHUFFLE,
    /** Cycle repeat: off, all, one. */
    CYCLE_REPEAT
}
