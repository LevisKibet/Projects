package com.palmtunes.media;

/**
 * Raised by backends. A <em>permanent</em> failure means this machine cannot provide media
 * control at all right now (tool missing, permission denied) so retrying every second is pointless.
 */
public class MediaException extends Exception {
    private final boolean permanent;

    public MediaException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public MediaException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }

    public static MediaException permanent(String message) {
        return new MediaException(message, true);
    }

    public static MediaException transientFailure(String message) {
        return new MediaException(message, false);
    }
}
