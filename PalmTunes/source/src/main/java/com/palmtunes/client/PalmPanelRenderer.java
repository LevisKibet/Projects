package com.palmtunes.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.palmtunes.PalmConfig;
import com.palmtunes.media.MediaInfo;
import com.palmtunes.media.MediaManager;
import com.palmtunes.media.MediaSnapshot;
import com.palmtunes.media.PlaybackState;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the glass panel in camera space, directly above the palm.
 * <p>
 * Working in camera space makes the panel face the viewer exactly (a billboard), so mouse picking
 * only needs a rectangle: we project the panel's corners with the real projection matrix and
 * publish them to {@link PalmInteraction}.
 */
final class PalmPanelRenderer {
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    /** Distance between stacked layers, in blocks, so coplanar quads never z-fight. */
    private static final float LAYER_EPS = 0.0007f;

    // Smoothed accent colour so the glass fades between albums instead of snapping.
    private static float accentR = 0.47f;
    private static float accentG = 0.82f;
    private static float accentB = 1.0f;

    private PalmPanelRenderer() {
    }

    /**
     * @param palm  position of the palm in camera space (blocks)
     * @param anim  eased 0..1 open animation
     */
    static void render(MultiBufferSource buffers, Vector3f palm, float anim) {
        Minecraft mc = Minecraft.getInstance();
        GlassTextures.ensure();
        PalmConfig cfg = PalmConfig.CLIENT;
        MediaSnapshot snap = MediaManager.get().snapshot();

        smoothAccent(ArtTextures.accent());

        float pop = 0.9f + 0.1f * anim;
        float k = (float) (cfg.panelWidth.get() / PalmLayout.W) * pop;
        float width = PalmLayout.W * k;
        float height = PalmLayout.H * k;
        float cx = palm.x + (float) (double) cfg.panelOffsetX.get();
        float cy = palm.y + (float) (double) cfg.panelGap.get() + height / 2f;
        float cz = palm.z - (float) (double) cfg.panelPush.get();
        float yaw = (float) Math.toRadians(cfg.panelYaw.get());
        float depthScale = 1f - (float) (double) cfg.panelDepthBias.get();

        Ctx c = new Ctx(buffers, cx, cy, cz, palm.z, yaw, k, depthScale, anim);

        publishHitQuad(c, width, height);

        // Tell the head where the panel is in the world so the player can look at it.
        net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
        Vector3f look = cam.getLookVector();
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        PalmPose.setPanelWorld(cam.getPosition().add(
                -left.x * cx + up.x * cy - look.x * cz,
                -left.y * cx + up.y * cy - look.y * cz,
                -left.z * cx + up.z * cy - look.z * cz));

        // ---- glass ----------------------------------------------------------------------
        float tr = Mth.lerp(0.6f, 1f, accentR);
        float tg = Mth.lerp(0.6f, 1f, accentG);
        float tb = Mth.lerp(0.6f, 1f, accentB);
        c.quad(GlassTextures.PANEL_BODY, 0, 0, PalmLayout.W, PalmLayout.H, tr, tg, tb, anim, 0);
        c.quad(GlassTextures.PANEL_EDGE, 0, 0, PalmLayout.W, PalmLayout.H, 1f, 1f, 1f, 0.9f * anim, 1);

        Font font = mc.font;
        boolean active = snap.kind() == MediaSnapshot.Kind.ACTIVE && snap.info() != null;
        MediaInfo info = snap.info();

        // ---- cover art ------------------------------------------------------------------
        c.quad(ArtTextures.texture(), PalmLayout.ART_X, PalmLayout.ART_Y,
                PalmLayout.ART_X + PalmLayout.ART_SIZE, PalmLayout.ART_Y + PalmLayout.ART_SIZE,
                1f, 1f, 1f, anim, 2);

        // ---- text -----------------------------------------------------------------------
        float textW = PalmLayout.TEXT_RIGHT - PalmLayout.TEXT_X;
        if (active) {
            String title = info.title().isBlank() ? Component.translatable("palmtunes.ui.unknown_title").getString() : info.title();
            String artist = info.artist().isBlank() ? Component.translatable("palmtunes.ui.unknown_artist").getString() : info.artist();
            c.text(font, fit(font, Component.literal(title).withStyle(ChatFormatting.BOLD), (int) ((textW - 12f) / 0.67f)),
                    PalmLayout.TITLE_Y, 0.67f, 0xFFFFFF);
            c.text(font, fit(font, Component.literal(artist), (int) (textW / 0.6f)), PalmLayout.ARTIST_Y, 0.6f, 0xD8DCE0);
            if (!info.album().isBlank()) {
                c.text(font, fit(font, Component.literal(info.album()), (int) (textW / 0.54f)), PalmLayout.ALBUM_Y, 0.54f, 0xA0A6AD);
            }
            boolean playing = PalmInteraction.isPlayingForDisplay(snap);
            Component status;
            if (info.sourceApp().isBlank()) {
                status = Component.literal(playing ? "Playing" : "Paused");
            } else {
                status = Component.translatable(playing ? "palmtunes.ui.playing_in" : "palmtunes.ui.paused_in", info.sourceApp());
            }
            c.text(font, fit(font, status, (int) (textW / 0.54f)), PalmLayout.SOURCE_Y, 0.54f, 0xFF000000 | accentRgb());
        } else {
            Component headline;
            Component detail;
            switch (snap.kind()) {
                case UNAVAILABLE -> {
                    headline = Component.translatable("palmtunes.ui.unavailable").withStyle(ChatFormatting.BOLD);
                    detail = Component.literal(snap.message());
                }
                case NO_SESSION -> {
                    headline = Component.translatable("palmtunes.ui.nothing_playing").withStyle(ChatFormatting.BOLD);
                    detail = Component.translatable("palmtunes.ui.nothing_hint");
                }
                default -> {
                    // Still starting: show the empty glass rather than a "connecting" message.
                    headline = Component.empty();
                    detail = Component.empty();
                }
            }
            c.text(font, fit(font, headline, (int) ((textW - 12f) / 0.67f)), PalmLayout.TITLE_Y, 0.67f, 0xFFFFFF);
            List<FormattedCharSequence> lines = font.split(detail, (int) (textW / 0.54f));
            float y = PalmLayout.ARTIST_Y;
            for (int i = 0; i < Math.min(4, lines.size()); i++) {
                c.text(font, lines.get(i), y, 0.54f, 0xB8BEC5);
                y += 7f;
            }
        }

        // ---- progress -------------------------------------------------------------------
        long durationMs = active ? info.durationMs() : -1;
        long positionMs = active ? snap.positionNowMs(System.nanoTime()) : -1;
        float fraction = 0f;
        if (PalmInteraction.isScrubbing() && durationMs > 0) {
            fraction = PalmInteraction.scrubFraction();
            positionMs = Math.round(fraction * durationMs);
        } else if (durationMs > 0 && positionMs >= 0) {
            fraction = Mth.clamp(positionMs / (float) durationMs, 0f, 1f);
        }
        float bx = PalmLayout.BAR_X;
        float by = PalmLayout.BAR_Y;
        c.quad(GlassTextures.WHITE, bx, by, bx + PalmLayout.BAR_W, by + PalmLayout.BAR_H, 1f, 1f, 1f, 0.22f * anim, 2);
        if (fraction > 0f) {
            c.quad(GlassTextures.WHITE, bx, by, bx + PalmLayout.BAR_W * fraction, by + PalmLayout.BAR_H,
                    accentR, accentG, accentB, anim, 3);
        }
        if (durationMs > 0) {
            boolean hot = PalmInteraction.hover() == PalmLayout.Hit.SEEK || PalmInteraction.isScrubbing();
            float kr = hot ? 4.6f : 3.4f;
            float kx = bx + PalmLayout.BAR_W * fraction;
            c.quad(GlassTextures.DISC, kx - kr, by + PalmLayout.BAR_H / 2f - kr, kx + kr, by + PalmLayout.BAR_H / 2f + kr,
                    1f, 1f, 1f, anim, 4);
        }
        if (active) {
            String left1 = PalmLayout.formatTime(positionMs);
            String right1 = PalmLayout.formatTime(durationMs);
            c.text(font, Component.literal(left1).getVisualOrderText(), PalmLayout.TIME_Y, 0.5f, 0xB8BEC5, bx);
            float rw = font.width(right1) * 0.5f;
            c.text(font, Component.literal(right1).getVisualOrderText(), PalmLayout.TIME_Y, 0.5f, 0xB8BEC5,
                    bx + PalmLayout.BAR_W - rw);
        }

        // ---- transport buttons ----------------------------------------------------------
        boolean playing = PalmInteraction.isPlayingForDisplay(snap);
        float enabled = active ? 1f : 0.35f;
        button(c, PalmLayout.Hit.PREVIOUS, PalmLayout.PREV_X, PalmLayout.SMALL_R, GlassTextures.ICON_PREVIOUS, enabled);
        button(c, PalmLayout.Hit.PLAY_PAUSE, PalmLayout.PLAY_X, PalmLayout.BIG_R,
                playing ? GlassTextures.ICON_PAUSE : GlassTextures.ICON_PLAY, enabled);
        button(c, PalmLayout.Hit.NEXT, PalmLayout.NEXT_X, PalmLayout.SMALL_R, GlassTextures.ICON_NEXT, enabled);

        // shuffle / repeat: lit in the album colour when on, dim when the source cannot do it
        int shuffle = active ? info.shuffle() : -1;
        int repeat = active ? info.repeat() : -1;
        toggle(c, PalmLayout.Hit.SHUFFLE, PalmLayout.SHUFFLE_X, GlassTextures.ICON_SHUFFLE, shuffle == 1, shuffle >= 0);
        toggle(c, PalmLayout.Hit.REPEAT, PalmLayout.REPEAT_X,
                repeat == 2 ? GlassTextures.ICON_REPEAT_ONE : GlassTextures.ICON_REPEAT, repeat > 0, repeat >= 0);

        // ---- up next --------------------------------------------------------------------
        c.quad(GlassTextures.WHITE, PalmLayout.BAR_X, PalmLayout.QUEUE_RULE_Y, PalmLayout.BAR_X + PalmLayout.BAR_W,
                PalmLayout.QUEUE_RULE_Y + 0.7f, 1f, 1f, 1f, 0.16f * anim, 2);
        c.text(font, Component.translatable("palmtunes.ui.up_next").withStyle(ChatFormatting.BOLD).getVisualOrderText(),
                PalmLayout.QUEUE_LABEL_Y, 0.47f, 0xFF000000 | accentRgb(), PalmLayout.BAR_X);
        float rowW = PalmLayout.BAR_W / 0.54f;
        if (active && !info.queue().isEmpty()) {
            int n = Math.min(PalmLayout.QUEUE_ROWS, info.queue().size());
            for (int i = 0; i < n; i++) {
                float y = PalmLayout.QUEUE_ROW_Y + i * PalmLayout.QUEUE_ROW_STEP;
                c.text(font, fit(font, Component.literal((i + 1) + "  " + info.queue().get(i)), (int) rowW), y, 0.54f,
                        i == 0 ? 0xE6E9EC : 0xA9AFB6, PalmLayout.BAR_X);
            }
        } else if (active) {
            Component none = Component.translatable(shuffle == 1 ? "palmtunes.ui.queue_shuffled" : "palmtunes.ui.queue_unavailable");
            c.text(font, fit(font, none, (int) rowW), PalmLayout.QUEUE_ROW_Y, 0.54f, 0x8E949B, PalmLayout.BAR_X);
        }

        // close "x"
        boolean closeHot = PalmInteraction.hover() == PalmLayout.Hit.CLOSE;
        if (closeHot) {
            c.disc(PalmLayout.CLOSE_X, PalmLayout.CLOSE_Y, PalmLayout.CLOSE_R + 3f, 1f, 1f, 1f, 0.2f * anim, 3);
        }
        c.icon(GlassTextures.ICON_CLOSE, PalmLayout.CLOSE_X, PalmLayout.CLOSE_Y, PalmLayout.CLOSE_R * 2f,
                1f, 1f, 1f, (closeHot ? 1f : 0.6f) * anim, 4);
    }

    private static void button(Ctx c, PalmLayout.Hit id, float cx, float r, ResourceLocation icon, float enabled) {
        boolean hot = PalmInteraction.hover() == id;
        boolean flash = PalmInteraction.isFlashing(id);
        if (id == PalmLayout.Hit.PLAY_PAUSE) {
            // The main button always wears a soft accent disc.
            c.disc(cx, PalmLayout.BUTTON_Y, r, accentR, accentG, accentB, (flash ? 0.55f : hot ? 0.42f : 0.30f) * c.anim * enabled, 3);
        } else if (hot || flash) {
            c.disc(cx, PalmLayout.BUTTON_Y, r, 1f, 1f, 1f, (flash ? 0.30f : 0.16f) * c.anim * enabled, 3);
        }
        float size = r * 2f * (flash ? 0.92f : 1f);
        c.icon(icon, cx, PalmLayout.BUTTON_Y, size, 1f, 1f, 1f, enabled * c.anim, 4);
    }

    private static void toggle(Ctx c, PalmLayout.Hit id, float cx, ResourceLocation icon, boolean on, boolean supported) {
        boolean hot = supported && PalmInteraction.hover() == id;
        boolean flash = PalmInteraction.isFlashing(id);
        float r = PalmLayout.SIDE_R;
        if (on) {
            c.disc(cx, PalmLayout.BUTTON_Y, r + 1.5f, accentR, accentG, accentB, 0.28f * c.anim, 3);
        } else if (hot || flash) {
            c.disc(cx, PalmLayout.BUTTON_Y, r + 1.5f, 1f, 1f, 1f, (flash ? 0.28f : 0.14f) * c.anim, 3);
        }
        float a = (!supported ? 0.22f : on ? 1f : hot ? 0.9f : 0.6f) * c.anim;
        float tr = on ? Mth.lerp(0.35f, accentR, 1f) : 1f;
        float tg = on ? Mth.lerp(0.35f, accentG, 1f) : 1f;
        float tb = on ? Mth.lerp(0.35f, accentB, 1f) : 1f;
        c.icon(icon, cx, PalmLayout.BUTTON_Y, r * 1.7f * (flash ? 0.9f : 1f), tr, tg, tb, a, 4);
    }

    // ------------------------------------------------------------------------------------------

    private static void smoothAccent(int rgb) {
        float tr = ((rgb >> 16) & 0xFF) / 255f;
        float tg = ((rgb >> 8) & 0xFF) / 255f;
        float tb = (rgb & 0xFF) / 255f;
        accentR += (tr - accentR) * 0.12f;
        accentG += (tg - accentG) * 0.12f;
        accentB += (tb - accentB) * 0.12f;
    }

    private static int accentRgb() {
        return (Mth.clamp((int) (accentR * 255), 0, 255) << 16)
                | (Mth.clamp((int) (accentG * 255), 0, 255) << 8)
                | Mth.clamp((int) (accentB * 255), 0, 255);
    }

    /** Fits text into {@code maxPx} font pixels, adding an ellipsis if it must be cut. */
    private static FormattedCharSequence fit(Font font, Component text, int maxPx) {
        if (font.width(text) <= maxPx) {
            return text.getVisualOrderText();
        }
        FormattedText head = font.substrByWidth(text, Math.max(0, maxPx - font.width("…")));
        return Language.getInstance().getVisualOrder(
                FormattedText.composite(head, Component.literal("…").withStyle(text.getStyle())));
    }

    /** Projects the panel's four corners to GUI pixels so the mouse can be mapped onto the slanted panel. */
    private static void publishHitQuad(Ctx c, float width, float height) {
        Minecraft mc = Minecraft.getInstance();
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        float hw = width / 2f;
        float hh = height / 2f;
        float[][] pts = new float[4][];
        float[][] local = {{-hw, hh}, {hw, hh}, {hw, -hh}, {-hw, -hh}}; // TL, TR, BR, BL
        for (int i = 0; i < 4; i++) {
            Vector3f v = c.toView(local[i][0], local[i][1], 0f);
            pts[i] = project(proj, v.x, v.y, v.z, mc);
            if (pts[i] == null) {
                PalmInteraction.invalidateRect();
                return;
            }
        }
        PalmInteraction.publishQuad(pts);
    }

    private static float[] project(Matrix4f proj, float x, float y, float z, Minecraft mc) {
        Vector4f v = new Vector4f(x, y, z, 1f);
        proj.transform(v);
        if (v.w <= 0.0001f) {
            return null;
        }
        float nx = v.x / v.w;
        float ny = v.y / v.w;
        float sx = (nx * 0.5f + 0.5f) * mc.getWindow().getGuiScaledWidth();
        float sy = (1f - (ny * 0.5f + 0.5f)) * mc.getWindow().getGuiScaledHeight();
        return new float[]{sx, sy};
    }

    // ------------------------------------------------------------------------------------------

    /** Drawing context: maps virtual panel pixels to (slanted) camera space. */
    private static final class Ctx {
        final MultiBufferSource buffers;
        final float k;
        final float palmZ;
        final float depthScale;
        final float anim;
        /** Panel centre + yaw: panel-local blocks to camera-space blocks, before the depth bias. */
        final Matrix4f toView;
        /** The same with the depth bias folded in; what actually gets drawn. */
        final Matrix4f draw;

        Ctx(MultiBufferSource buffers, float cx, float cy, float cz, float palmZ, float yaw, float k,
            float depthScale, float anim) {
            this.buffers = buffers;
            this.k = k;
            this.palmZ = palmZ;
            this.depthScale = depthScale;
            this.anim = anim;
            this.toView = new Matrix4f().translate(cx, cy, cz).rotateY(yaw);
            this.draw = new Matrix4f().scale(depthScale).mul(toView);
        }

        Vector3f toView(float lx, float ly, float lz) {
            return toView.transformPosition(new Vector3f(lx, ly, lz));
        }

        private float lx(float vx) {
            return (vx - PalmLayout.W / 2f) * k;
        }

        private float ly(float vy) {
            return (PalmLayout.H / 2f - vy) * k;
        }

        /** Quad in virtual panel pixels. */
        void quad(ResourceLocation tex, float vx0, float vy0, float vx1, float vy1,
                  float r, float g, float b, float a, int layer) {
            if (a <= 0.003f) {
                return;
            }
            VertexConsumer vc = buffers.getBuffer(PalmRenderTypes.quad(tex));
            float xl = lx(vx0), xr = lx(vx1), yt = ly(vy0), yb = ly(vy1);
            float zz = layer * LAYER_EPS;
            Vector3f p = new Vector3f();
            draw.transformPosition(xl, yt, zz, p);
            vc.vertex(p.x, p.y, p.z).color(r, g, b, a).uv(0f, 0f).uv2(FULL_BRIGHT).endVertex();
            draw.transformPosition(xl, yb, zz, p);
            vc.vertex(p.x, p.y, p.z).color(r, g, b, a).uv(0f, 1f).uv2(FULL_BRIGHT).endVertex();
            draw.transformPosition(xr, yb, zz, p);
            vc.vertex(p.x, p.y, p.z).color(r, g, b, a).uv(1f, 1f).uv2(FULL_BRIGHT).endVertex();
            draw.transformPosition(xr, yt, zz, p);
            vc.vertex(p.x, p.y, p.z).color(r, g, b, a).uv(1f, 0f).uv2(FULL_BRIGHT).endVertex();
        }

        void disc(float cx, float cy, float radius, float r, float g, float b, float a, int layer) {
            quad(GlassTextures.DISC, cx - radius, cy - radius, cx + radius, cy + radius, r, g, b, a, layer);
        }

        void icon(ResourceLocation tex, float cx, float cy, float size, float r, float g, float b, float a, int layer) {
            float h = size / 2f;
            quad(tex, cx - h, cy - h, cx + h, cy + h, r, g, b, a, layer);
        }

        /** Unrotated quad in camera-space blocks at the palm's depth ((x0,y0) top-left, y up). Used for the palm glow. */
        void rawQuad(ResourceLocation tex, float x0, float y0, float x1, float y1,
                     float r, float g, float b, float a, int layer) {
            if (a <= 0.003f) {
                return;
            }
            VertexConsumer vc = buffers.getBuffer(PalmRenderTypes.quad(tex));
            float zz = palmZ * depthScale + layer * LAYER_EPS;
            float xl = x0 * depthScale;
            float xr = x1 * depthScale;
            float yt = y0 * depthScale;
            float yb = y1 * depthScale;
            vc.vertex(xl, yt, zz).color(r, g, b, a).uv(0f, 0f).uv2(FULL_BRIGHT).endVertex();
            vc.vertex(xl, yb, zz).color(r, g, b, a).uv(0f, 1f).uv2(FULL_BRIGHT).endVertex();
            vc.vertex(xr, yb, zz).color(r, g, b, a).uv(1f, 1f).uv2(FULL_BRIGHT).endVertex();
            vc.vertex(xr, yt, zz).color(r, g, b, a).uv(1f, 0f).uv2(FULL_BRIGHT).endVertex();
        }

        void text(Font font, FormattedCharSequence text, float vy, float scale, int rgb) {
            text(font, text, vy, scale, rgb, PalmLayout.TEXT_X);
        }

        void text(Font font, FormattedCharSequence text, float vy, float scale, int rgb, float vx) {
            // Font alpha below ~4/255 is treated as opaque by the font renderer, so never go that low.
            int alpha = Math.max(8, Math.min(255, (int) (anim * 255f)));
            int argb = (alpha << 24) | (rgb & 0xFFFFFF);
            Matrix4f m = new Matrix4f(draw)
                    .translate(lx(vx), ly(vy), 6 * LAYER_EPS)
                    .scale(k * scale, -k * scale, 1f);
            font.drawInBatch(text, 0f, 0f, argb, false, m, buffers, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        }
    }
}
