package com.palmtunes.media;

/**
 * Cover art, already cropped to a square, scaled, and given rounded corners.
 *
* @param size      width and height in pixels
 * @param argb      straight-alpha ARGB pixels, row-major, {@code size * size} entries
* @param accentRgb a vivid colour pulled from the art (0xRRGGBB), used to tint the glass
 * @param palette   three distinct, bright colours (0xRRGGBB) from the art, strongest first, for gradients
 */
public record DecodedArt(int size, int[] argb, int accentRgb, int[] palette) {
}
