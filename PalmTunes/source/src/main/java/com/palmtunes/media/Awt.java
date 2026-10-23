package com.palmtunes.media;

/**
 * We only use Java2D to draw into off-screen images. Running AWT in headless mode keeps it from
 * touching the windowing system, which matters on macOS (AWT and GLFW fight over the main thread)
 * and on Wayland-only Linux desktops.
 */
public final class Awt {
    private Awt() {
    }

    public static void ensureHeadless() {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }
}
