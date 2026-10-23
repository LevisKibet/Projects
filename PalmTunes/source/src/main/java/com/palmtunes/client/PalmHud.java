package com.palmtunes.client;

import com.palmtunes.PalmConfig;
import com.palmtunes.media.MediaInfo;
import com.palmtunes.media.MediaManager;
import com.palmtunes.media.MediaSnapshot;
import com.palmtunes.media.PlaybackState;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * The music pulse at the top of the screen and the small "now playing" toast under it.
 * <p>
 * Other programs' audio is not available to Minecraft, so the bars are not a real spectrum. They are
 * a rhythm animation that runs while the player reports "playing", settles when it is paused, and
 * falls back to a calm idle wave (plus a hint) when no music app can be found. Colours come from the
 * current cover art.
 */
final class PalmHud {
    // Visualizer geometry, in GUI pixels (before the user's scale).
    private static final int BARS = 61;
    private static final int BAR_W = 3;
    private static final int BAR_PITCH = 5;
    private static final int SEG_H = 3;
    private static final int SEG_PITCH = 4;
    private static final int MAX_SEGS = 7;           // segments per half bar
    private static final int HALF_H = MAX_SEGS * SEG_PITCH;
    private static final int TOP = 5;

    // Toast geometry
    private static final int ART = 22;
    private static final int TOAST_GAP = 6;

    private static final float[] level = new float[BARS];
    private static final float[] lastHit = new float[BARS];
    private static long lastNanos;
    private static float time;
    private static float playBlend;   // 0 = paused or idle, 1 = playing
    private static float idleBlend;   // 1 = nothing to show, play the idle wave
    private static final float[][] colour = {{0.47f, 0.82f, 1f}, {0.66f, 0.55f, 1f}, {1f, 0.56f, 0.82f}};
    private static float beatHz = 2f;
    private static long lastBeatIndex = -1;

    // Toast state
    private static String toastKey = "";
    private static String pendingKey = "";
    private static long pendingSince;
    private static long toastStart = Long.MIN_VALUE;
    private static String toastTitle = "";
    private static String toastArtist = "";

    private PalmHud() {
    }

    // ---- per client tick: spot new songs --------------------------------------------------------

    static void tick() {
        PalmConfig cfg = PalmConfig.CLIENT;
        MediaSnapshot snap = MediaManager.get().snapshot();
        MediaInfo info = snap.kind() == MediaSnapshot.Kind.ACTIVE ? snap.info() : null;
        if (info == null) {
            pendingKey = "";
            return;
        }
        String key = info.trackKey();
        if (!key.equals(toastKey) && !key.equals(pendingKey)) {
            pendingKey = key;
            pendingSince = System.currentTimeMillis();
            // A stable, track-specific tempo so every song gets its own pulse.
            beatHz = (92 + (key.hashCode() & 0x3F)) / 60f;
        }
        if (!pendingKey.isEmpty() && pendingKey.equals(key) && info.state() == PlaybackState.PLAYING) {
            // Wait a moment for the cover art so the toast does not flash the placeholder.
            boolean artReady = ArtTextures.hasArt();
            if (artReady || System.currentTimeMillis() - pendingSince > 1600) {
                toastKey = key;
                pendingKey = "";
                toastTitle = info.title().isBlank() ? Component.translatable("palmtunes.ui.unknown_title").getString() : info.title();
                toastArtist = info.artist().isBlank() ? "" : info.artist();
                if (cfg.hudSongToast.get()) {
                    toastStart = System.currentTimeMillis();
                }
            }
        }
    }

    // ---- overlay ----------------------------------------------------------------------------------

    static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        PalmConfig cfg = PalmConfig.CLIENT;
        // Cinematic cuts: a black fade over the world, under the visualizer.
        float fade = Cinematic.fadeAlpha();
        if (fade > 0.002f) {
            g.fill(0, 0, screenW, screenH, ((int) (Mth.clamp(fade, 0f, 1f) * 255f) << 24));
        }
        if (!cfg.hudEnabled.get() || mc.player == null || mc.options.hideGui) {
            return;
        }
        // Fade away as the camera starts to move for the palm panel; fade back once it has come home.
        float vis = 1f - PalmController.eased(partialTick, 0f, 0.45f);
        if (vis <= 0.01f) {
            lastNanos = 0;
            return;
        }

        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Mth.clamp((now - lastNanos) / 1.0e9f, 0f, 0.1f);
        lastNanos = now;
        time += dt;

        MediaSnapshot snap = MediaManager.get().snapshot();
        MediaInfo info = snap.kind() == MediaSnapshot.Kind.ACTIVE ? snap.info() : null;
        boolean playing = info != null && info.state() == PlaybackState.PLAYING;
        boolean idle = info == null;

        playBlend += ((playing ? 1f : 0f) - playBlend) * (1f - (float) Math.exp(-dt * 6f));
        idleBlend += ((idle ? 1f : 0f) - idleBlend) * (1f - (float) Math.exp(-dt * 4f));

        // Glide the three gradient colours towards the cover art's palette.
        int[] palette = ArtTextures.palette();
        float f = 1f - (float) Math.exp(-dt * 3f);
        for (int i = 0; i < 3; i++) {
            colour[i][0] += (((palette[i] >> 16) & 0xFF) / 255f - colour[i][0]) * f;
            colour[i][1] += (((palette[i] >> 8) & 0xFF) / 255f - colour[i][1]) * f;
            colour[i][2] += ((palette[i] & 0xFF) / 255f - colour[i][2]) * f;
        }

        updateBars(dt);

        float s = cfg.hudScale.get().floatValue();
        float width = BARS * BAR_PITCH - (BAR_PITCH - BAR_W);
        g.pose().pushPose();
        g.pose().translate(screenW / 2f, TOP * s, 0);
        g.pose().scale(s, s, 1f);

        drawBars(g, -width / 2f, vis);

        float below = HALF_H * 2 + 3f;
        Font font = mc.font;
        boolean toastShown = drawToast(g, font, screenW, s, below, vis);

        // Idle hint, but never on top of a toast.
        if (info == null && !toastShown && cfg.hudIdleHint.get() && snap.kind() != MediaSnapshot.Kind.STARTING) {
            Component hint = snap.kind() == MediaSnapshot.Kind.UNAVAILABLE
                    ? Component.literal(snap.message())
                    : Component.translatable("palmtunes.hud.idle_hint");
            float a = 0.85f * vis * idleBlend;
            List<FormattedCharSequence> lines = font.split(hint, 300);
            float y = below + 1f;
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                centered(g, font, lines.get(i), y, 0xC8D4E0, a);
                y += 9f;
            }
        }
        g.pose().popPose();
    }

    // ---- bars -------------------------------------------------------------------------------------

    private static void updateBars(float dt) {
        float center = (BARS - 1) / 2f;
        float beat = time * beatHz;
        float phase = beat - (float) Math.floor(beat);
        float kick = (float) Math.exp(-phase * 4.5f);
        long beatIndex = (long) Math.floor(beat * 2f);          // new random "hits" on every half beat
        boolean newHit = beatIndex != lastBeatIndex;
        lastBeatIndex = beatIndex;
        float attack = 1f - (float) Math.exp(-dt * 38f);
        float release = 1f - (float) Math.exp(-dt * 8f);

        for (int i = 0; i < BARS; i++) {
            if (newHit) {
                lastHit[i] = hash(i, beatIndex);
            }
            float d = Math.abs(i - center) / center;                    // 0 centre .. 1 edge
            // A slow travelling "phrase" shape gives the clustered peaks of a waveform.
            // Voice-memo style clusters: a few swelling bursts drift along the row with quiet gaps between them.
            float tt = i / (float) (BARS - 1);
            float env = 0f;
            for (int k = 0; k < 4; k++) {
                float pk = 0.5f + 0.36f * Mth.sin(time * (0.19f + 0.06f * k) + k * 2.1f);
                float wk = 0.065f + 0.035f * (k % 2);
                float z = (tt - pk) / wk;
                env += (float) Math.exp(-z * z);
            }
            env = Mth.clamp(env, 0f, 1f);
            float taper = smooth(0f, 0.14f, tt) * smooth(1f, 0.86f, tt);
            float body = (0.12f + 0.88f * env) * (0.3f + 0.7f * lastHit[i]) * taper;
            float playingLevel = Mth.clamp(body * (0.55f + 0.45f * kick) + 0.03f, 0.03f, 1f);

            float paused = 0.05f + 0.02f * Mth.sin(time * 0.8f + i * 0.5f);
            float idle = 0.12f + 0.10f * Mth.sin(time * 1.4f - i * 0.38f) * (1f - 0.5f * d)
                    + 0.04f * Mth.sin(time * 0.6f + i * 0.2f);

            float notIdle = Mth.lerp(playBlend, paused, playingLevel);
            float target = Mth.lerp(idleBlend, notIdle, idle);
            level[i] += (target - level[i]) * (target > level[i] ? attack : release);
        }
    }

    /** Segmented, vertically mirrored bars with a gradient taken from the cover art. */
    private static void drawBars(GuiGraphics g, float x0, float vis) {
        float live = Mth.lerp(idleBlend, Mth.lerp(playBlend, 0.55f, 1f), 0.4f) * vis;
        for (int i = 0; i < BARS; i++) {
            int segs = Mth.clamp(Math.round(level[i] * MAX_SEGS + 0.35f), 1, MAX_SEGS);
            float t = i / (float) (BARS - 1);
            // Palette gradient that very slowly drifts, so a still song still feels alive.
            float gp = (t * 2f + time * 0.04f) % 2f;
            float[] c = gradient(gp);
            // Light, slightly washed colour like the reference: tinted whites rather than saturated bars.
            float cr = Mth.lerp(0.28f, c[0], 1f);
            float cg = Mth.lerp(0.28f, c[1], 1f);
            float cb = Mth.lerp(0.28f, c[2], 1f);
            if (idleBlend > 0.01f) {
                float grey = (cr + cg + cb) / 3f;
                cr = Mth.lerp(idleBlend * 0.6f, cr, grey);
                cg = Mth.lerp(idleBlend * 0.6f, cg, grey);
                cb = Mth.lerp(idleBlend * 0.6f, cb, grey);
            }
            int x = Math.round(x0 + i * BAR_PITCH);
            // soft glow column behind the bar
            int glow = ((int) (0.09f * live * 255) << 24) | (((int) (cr * 255)) << 16) | (((int) (cg * 255)) << 8) | (int) (cb * 255);
            g.fill(x - 1, HALF_H - 1 - segs * SEG_PITCH, x + BAR_W + 1, HALF_H + 1 + segs * SEG_PITCH, glow);
            for (int sIdx = 0; sIdx < segs; sIdx++) {
                boolean tipSeg = sIdx == segs - 1 && segs > 1;
                float tip = 1f - 0.3f * (sIdx / (float) MAX_SEGS);
                int col = argb(0.95f * live * tip, tipSeg ? Mth.lerp(0.5f, cr, 1f) : cr,
                        tipSeg ? Mth.lerp(0.5f, cg, 1f) : cg, tipSeg ? Mth.lerp(0.5f, cb, 1f) : cb);
                int shadow = ((int) (0.22f * live * 255) << 24);
                int up = HALF_H - 1 - sIdx * SEG_PITCH - SEG_H;
                int dn = HALF_H + 1 + sIdx * SEG_PITCH;
                // rounded ends: the outermost segment of a bar is one pixel narrower on each side
                int in = sIdx == segs - 1 ? 1 : 0;
                g.fill(x + 1 + in, up + 1, x + BAR_W + 1 - in, up + SEG_H + 1, shadow);
                g.fill(x + 1 + in, dn + 1, x + BAR_W + 1 - in, dn + SEG_H + 1, shadow);
                g.fill(x + in, up, x + BAR_W - in, up + SEG_H, col);
                g.fill(x + in, dn, x + BAR_W - in, dn + SEG_H, col);
            }
        }
    }

    private static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Position along a 0..2 loop (palette 0 -> 1 -> 2 -> 0). */
    private static float[] gradient(float p) {
        int a = (int) Math.floor(p) % 3;
        float f = p - (float) Math.floor(p);
        // p in [0,2): 0..1 blends colour 0->1, 1..2 blends colour 1->2
        int i0 = Math.min(a, 2);
        int i1 = (i0 + 1) % 3;
        f = f * f * (3f - 2f * f);
        return new float[]{
                Mth.lerp(f, colour[i0][0], colour[i1][0]),
                Mth.lerp(f, colour[i0][1], colour[i1][1]),
                Mth.lerp(f, colour[i0][2], colour[i1][2])};
    }

    private static float hash(int i, long n) {
        long h = i * 374761393L + n * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 65535f;
    }

    // ---- new-song toast ----------------------------------------------------------------------------

    /**
     * A small rounded cover with the title and artist, which grows out of the cover, waits, then
     * folds back into it and disappears. Returns true while something is on screen.
     */
    private static boolean drawToast(GuiGraphics g, Font font, int screenW, float s, float y, float vis) {
        if (toastStart == Long.MIN_VALUE) {
            return false;
        }
        PalmConfig cfg = PalmConfig.CLIENT;
        float hold = cfg.hudToastSeconds.get().floatValue();
        float t = (System.currentTimeMillis() - toastStart) / 1000f;
        final float popIn = 0.35f, expand = 0.55f, collapse = 0.5f, popOut = 0.3f;
        float total = popIn + expand + hold + collapse + popOut;
        if (t > total) {
            toastStart = Long.MIN_VALUE;
            return false;
        }

        float artScale;      // 0..1 of the cover square
        float open;          // 0..1 how far the text has unfolded
        if (t < popIn) {
            artScale = easeOutBack(t / popIn);
            open = 0f;
        } else if (t < popIn + expand) {
            artScale = 1f;
            open = easeInOut((t - popIn) / expand);
        } else if (t < popIn + expand + hold) {
            artScale = 1f;
            open = 1f;
        } else if (t < popIn + expand + hold + collapse) {
            artScale = 1f;
            open = 1f - easeInOut((t - popIn - expand - hold) / collapse);
        } else {
            artScale = 1f - easeInOut((t - total + popOut) / popOut);
            open = 0f;
        }
        float alpha = Mth.clamp(artScale * 1.4f, 0f, 1f) * vis;
        if (alpha < 0.02f || artScale <= 0.01f) {
            return true;
        }

        float titleW = font.width(toastTitle) * 0.85f;
        float artistW = font.width(toastArtist) * 0.75f;
        float textW = Math.min(180f, Math.max(titleW, artistW) + 2f);
        float fullW = ART + TOAST_GAP + textW;
        float curW = ART + (fullW - ART) * open;
        float left = -curW / 2f;                      // stays centred while it unfolds
        float top = y + 1f;

        // Cover: rounded square (the corners are baked into the texture).
        ResourceLocation tex = ArtTextures.texture();
        Minecraft.getInstance().getTextureManager().getTexture(tex).setFilter(true, false);
        float size = ART * artScale;
        float ax = left + (ART - size) / 2f;
        float ay = top + (ART - size) / 2f;
        g.pose().pushPose();
        g.pose().translate(ax, ay, 0);
        g.pose().scale(size / 128f, size / 128f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.setColor(1f, 1f, 1f, alpha);
        g.blit(tex, 0, 0, 0, 0, 128, 128, 128, 128);
        g.setColor(1f, 1f, 1f, 1f);
        g.pose().popPose();

        if (open > 0.02f) {
            // Clip to the unfolded width (scissor wants absolute GUI pixels, so undo our transform).
            float cx = screenW / 2f;
            float sx0 = cx + (left + ART + TOAST_GAP * 0.5f) * s;
            float sx1 = cx + (left + curW) * s + 1f;
            float sy0 = (TOP + top) * s - 1f;
            float sy1 = (TOP + top + ART) * s + 1f;
            g.enableScissor(Math.round(sx0), Math.round(sy0), Math.round(sx1), Math.round(sy1));
            float tx = left + ART + TOAST_GAP;
            float ta = Mth.clamp((open - 0.15f) / 0.85f, 0f, 1f) * vis;
            drawText(g, font, Component.literal(toastTitle).withStyle(ChatFormatting.BOLD).getVisualOrderText(),
                    tx, top + 3f, 0.85f, 0xFFFFFF, ta);
            if (!toastArtist.isEmpty()) {
                drawText(g, font, Component.literal(toastArtist).getVisualOrderText(), tx, top + 13f, 0.75f, 0xD0D6DD, ta * 0.9f);
            }
            g.disableScissor();
        }
        return true;
    }

    private static float easeInOut(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float easeOutBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.70158f, c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    // ---- text helpers ----------------------------------------------------------------------------

    private static void drawText(GuiGraphics g, Font font, FormattedCharSequence text, float x, float y, float scale,
                                 int rgb, float alpha) {
        int a = Mth.clamp((int) (alpha * 255f), 0, 255);
        if (a < 8) {
            return; // the font renderer treats very small alpha as opaque
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, (a << 24) | (rgb & 0xFFFFFF), true);
        g.pose().popPose();
    }

    private static void centered(GuiGraphics g, Font font, FormattedCharSequence text, float y, int rgb, float alpha) {
        float scale = 0.75f;
        drawText(g, font, text, -font.width(text) * scale / 2f, y, scale, rgb, alpha);
    }

    private static int rgb(float r, float g, float b) {
        return (Mth.clamp((int) (r * 255), 0, 255) << 16) | (Mth.clamp((int) (g * 255), 0, 255) << 8)
                | Mth.clamp((int) (b * 255), 0, 255);
    }

    private static int argb(float a, float r, float g, float b) {
        return (Mth.clamp((int) (a * 255), 1, 255) << 24) | rgb(r, g, b);
    }
}
