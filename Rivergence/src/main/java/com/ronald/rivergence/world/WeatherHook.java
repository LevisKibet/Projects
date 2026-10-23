package com.ronald.rivergence.world;

/**
 * The rain of a passing local shower, as the client sees it. Fed into the client world's rain level, so the game
 * itself rains there - its own drops, splashes, sound, grey sky and clouds - and so do shader packs, texture packs
 * and other mods that look at the weather. Never touches the server's weather.
 */
public final class WeatherHook {
    private WeatherHook() {}

    /** How hard the local shower rains where the player is (0..1), set each tick by the client. */
    public static volatile float local;
    /** The world's own rain level, before the local shower is added. */
    public static volatile float vanilla;

    public static boolean vanillaRaining() { return vanilla > 0.2f; }
}
