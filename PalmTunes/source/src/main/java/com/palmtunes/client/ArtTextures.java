package com.palmtunes.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.palmtunes.PalmTunes;
import com.palmtunes.media.DecodedArt;
import com.palmtunes.media.MediaManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Keeps one GPU texture in sync with the cover art the media layer last decoded. */
public final class ArtTextures {
    public static final int DEFAULT_ACCENT = 0x78D2FF;

    private static long uploadedVersion = -1;
    private static ResourceLocation current;
    public static final int[] DEFAULT_PALETTE = {0x78D2FF, 0xA98BFF, 0xFF8FD0};

    private static int accent = DEFAULT_ACCENT;
    private static int[] palette = DEFAULT_PALETTE;

    private ArtTextures() {
    }

    /** Client thread. Uploads new art if there is any. */
    public static void tick() {
        MediaManager.ArtState state = MediaManager.get().art();
        if (state.version() == uploadedVersion) {
            return;
        }
        uploadedVersion = state.version();
        release();

        DecodedArt art = state.art();
        if (art == null) {
            accent = DEFAULT_ACCENT;
            palette = DEFAULT_PALETTE;
            return;
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, art.size(), art.size(), false);
        for (int y = 0; y < art.size(); y++) {
            for (int x = 0; x < art.size(); x++) {
                image.setPixelRGBA(x, y, GlassTextures.argbToAbgr(art.argb()[y * art.size() + x]));
            }
        }
        current = new ResourceLocation(PalmTunes.MOD_ID, "generated/art_" + uploadedVersion);
        DynamicTexture texture = new DynamicTexture(image);
        texture.setFilter(true, false); // smooth when the small toast scales the art down
        Minecraft.getInstance().getTextureManager().register(current, texture);
        accent = art.accentRgb();
        palette = art.palette();
    }

    /** The cover art texture, or the placeholder if the player gave us none. */
    public static ResourceLocation texture() {
        return current != null ? current : GlassTextures.ART_PLACEHOLDER;
    }

    /** Three 0xRRGGBB colours from the cover art, strongest first (a default blue/violet/pink without art). */
    public static int[] palette() {
        return palette;
    }

    public static boolean hasArt() {
        return current != null;
    }

    /** 0xRRGGBB */
    public static int accent() {
        return accent;
    }

    public static void release() {
        if (current != null) {
            Minecraft.getInstance().getTextureManager().release(current);
            current = null;
        }
    }
}
