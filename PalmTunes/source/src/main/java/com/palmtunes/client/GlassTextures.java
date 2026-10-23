package com.palmtunes.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.palmtunes.PalmTunes;
import com.palmtunes.media.Awt;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Every texture the panel needs, drawn with Java2D at start-up. Nothing to ship, nothing to
 * keep in sync with a resource pack, and the curves are properly anti-aliased.
 * <p>
 * Panel pieces are painted in grey/white so the renderer can tint them with the album's colour.
 */
public final class GlassTextures {
    /** Panel body texture is this many pixels per virtual pixel. */
    private static final int PANEL_SCALE = 3;
    private static final int PANEL_W = (int) PalmLayout.W * PANEL_SCALE;
    private static final int PANEL_H = (int) PalmLayout.H * PANEL_SCALE;
    private static final float PANEL_RADIUS = 14f * PANEL_SCALE;

    public static ResourceLocation PANEL_BODY;
    public static ResourceLocation PANEL_EDGE;
    public static ResourceLocation GLOW;
    public static ResourceLocation DISC;
    public static ResourceLocation WHITE;
    public static ResourceLocation ICON_PLAY;
    public static ResourceLocation ICON_PAUSE;
    public static ResourceLocation ICON_NEXT;
    public static ResourceLocation ICON_PREVIOUS;
    public static ResourceLocation ICON_CLOSE;
    public static ResourceLocation ICON_SHUFFLE;
    public static ResourceLocation ICON_REPEAT;
    public static ResourceLocation ICON_REPEAT_ONE;
    public static ResourceLocation ART_PLACEHOLDER;

    private static boolean ready;

    private GlassTextures() {
    }

    /** Must be called on the render thread. Cheap after the first call. */
    public static void ensure() {
        if (ready) {
            return;
        }
        ready = true;
        Awt.ensureHeadless();

        PANEL_BODY = register("panel_body", panelBody());
        PANEL_EDGE = register("panel_edge", panelEdge());
        GLOW = register("glow", glow(128));
        DISC = register("disc", disc(128));
        WHITE = register("white", solid(8, Color.WHITE));
        ICON_PLAY = register("icon_play", icon(g -> g.fill(roundedTriangle(40, 24, 40, 104, 104, 64, 10))));
        ICON_PAUSE = register("icon_pause", icon(g -> {
            g.fill(new RoundRectangle2D.Float(34, 24, 22, 80, 12, 12));
            g.fill(new RoundRectangle2D.Float(72, 24, 22, 80, 12, 12));
        }));
        ICON_NEXT = register("icon_next", icon(g -> {
            g.fill(roundedTriangle(26, 28, 26, 100, 84, 64, 8));
            g.fill(new RoundRectangle2D.Float(88, 28, 14, 72, 8, 8));
        }));
        ICON_PREVIOUS = register("icon_previous", icon(g -> {
            g.fill(roundedTriangle(102, 28, 102, 100, 44, 64, 8));
            g.fill(new RoundRectangle2D.Float(26, 28, 14, 72, 8, 8));
        }));
        ICON_CLOSE = register("icon_close", icon(g -> {
            g.setStroke(new BasicStroke(13f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(34, 34, 94, 94));
            g.draw(new Line2D.Float(94, 34, 34, 94));
        }));
        ICON_SHUFFLE = register("icon_shuffle", icon(g -> {
            g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float a = new Path2D.Float();
            a.moveTo(16, 40);
            a.lineTo(44, 40);
            a.curveTo(66, 40, 66, 88, 88, 88);
            a.lineTo(96, 88);
            g.draw(a);
            Path2D.Float b = new Path2D.Float();
            b.moveTo(16, 88);
            b.lineTo(44, 88);
            b.curveTo(66, 88, 66, 40, 88, 40);
            b.lineTo(96, 40);
            g.draw(b);
            g.fill(roundedTriangle(94, 26, 94, 54, 116, 40, 4));
            g.fill(roundedTriangle(94, 74, 94, 102, 116, 88, 4));
        }));
        ICON_REPEAT = register("icon_repeat", icon(GlassTextures::paintRepeat));
        ICON_REPEAT_ONE = register("icon_repeat_one", icon(g -> {
            paintRepeat(g);
            g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(64, 50, 64, 80));
            g.draw(new Line2D.Float(55, 58, 64, 50));
        }));
        ART_PLACEHOLDER = register("art_placeholder", placeholderArt());
    }

    // ------------------------------------------------------------------------------------------
    // Painters
    // ------------------------------------------------------------------------------------------

    private static BufferedImage panelBody() {
        BufferedImage img = new BufferedImage(PANEL_W, PANEL_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);
        RoundRectangle2D shape = new RoundRectangle2D.Float(3, 3, PANEL_W - 6, PANEL_H - 6, PANEL_RADIUS * 2, PANEL_RADIUS * 2);

        // Smoked glass: slightly lighter at the top, denser towards the bottom.
        g.setPaint(new LinearGradientPaint(0, 0, 0, PANEL_H, new float[]{0f, 1f},
                new Color[]{new Color(132, 132, 132, 150), new Color(78, 78, 78, 190)}));
        g.fill(shape);

        // Soft light sweeping in from the top-left corner.
        g.setClip(shape);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(PANEL_W * 0.18f, -PANEL_H * 0.15f), PANEL_W * 0.75f,
                new float[]{0f, 1f}, new Color[]{new Color(255, 255, 255, 64), new Color(255, 255, 255, 0)}));
        g.fillRect(0, 0, PANEL_W, PANEL_H);
        g.setClip(null);
        g.dispose();

        // A little grain sells the frosted look.
        Random rnd = new Random(7);
        for (int y = 0; y < PANEL_H; y++) {
            for (int x = 0; x < PANEL_W; x++) {
                int argb = img.getRGB(x, y);
                int a = argb >>> 24;
                if (a == 0) continue;
                int n = rnd.nextInt(13) - 6;
                int r = clamp(((argb >> 16) & 0xFF) + n);
                int gg = clamp(((argb >> 8) & 0xFF) + n);
                int b = clamp((argb & 0xFF) + n);
                img.setRGB(x, y, (clamp(a + n / 2) << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return img;
    }

    private static BufferedImage panelEdge() {
        BufferedImage img = new BufferedImage(PANEL_W, PANEL_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);
        float inset = 3f + 2f;
        RoundRectangle2D shape = new RoundRectangle2D.Float(inset, inset, PANEL_W - 2 * inset, PANEL_H - 2 * inset,
                PANEL_RADIUS * 2 - 4, PANEL_RADIUS * 2 - 4);
        g.setStroke(new BasicStroke(4.5f));
        g.setPaint(new LinearGradientPaint(0, 0, PANEL_W, PANEL_H, new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(255, 255, 255, 235), new Color(255, 255, 255, 70), new Color(255, 255, 255, 150)}));
        g.draw(shape);
        g.dispose();
        return img;
    }

    private static BufferedImage glow(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);
        float c = size / 2f;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(c, c), c, new float[]{0f, 0.35f, 1f},
                new Color[]{new Color(255, 255, 255, 255), new Color(255, 255, 255, 110), new Color(255, 255, 255, 0)}));
        g.fillRect(0, 0, size, size);
        g.dispose();
        return img;
    }

    private static BufferedImage disc(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(2, 2, size - 4, size - 4));
        g.dispose();
        return img;
    }

    private static BufferedImage solid(int size, Color color) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, size, size);
        g.dispose();
        return img;
    }

    private static BufferedImage icon(Consumer<Graphics2D> painter) {
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);
        g.setColor(Color.WHITE);
        painter.accept(g);
        g.dispose();
        return img;
    }

    /** A triangle with softened corners, drawn as fill + round-joined outline. */
    private static java.awt.Shape roundedTriangle(float x1, float y1, float x2, float y2, float x3, float y3, float round) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        p.closePath();
        java.awt.geom.Area area = new java.awt.geom.Area(p);
        java.awt.Shape stroked = new BasicStroke(round, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(p);
        area.add(new java.awt.geom.Area(stroked));
        return area;
    }

    /** Two arrows chasing round a rounded loop. */
    private static void paintRepeat(Graphics2D g) {
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new RoundRectangle2D.Float(22, 34, 84, 60, 36, 36));
        g.setComposite(AlphaComposite.Clear);
        g.fill(new java.awt.geom.Rectangle2D.Float(80, 24, 26, 22));
        g.fill(new java.awt.geom.Rectangle2D.Float(22, 82, 26, 22));
        g.setComposite(AlphaComposite.SrcOver);
        g.fill(roundedTriangle(82, 20, 82, 48, 106, 34, 4));
        g.fill(roundedTriangle(46, 80, 46, 108, 22, 94, 4));
    }

    private static BufferedImage placeholderArt() {
        int s = 128;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);
        RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, s, s, 36, 36);
        g.setPaint(new LinearGradientPaint(0, 0, s, s, new float[]{0f, 1f},
                new Color[]{new Color(88, 96, 140), new Color(38, 40, 66)}));
        g.fill(shape);

        // A quarter note.
        g.setColor(new Color(255, 255, 255, 215));
        java.awt.geom.AffineTransform old = g.getTransform();
        g.rotate(Math.toRadians(-22), 52, 90);
        g.fill(new Ellipse2D.Float(34, 78, 36, 26));
        g.setTransform(old);
        g.fill(new RoundRectangle2D.Float(62, 32, 7, 62, 4, 4));
        Path2D.Float flag = new Path2D.Float();
        flag.moveTo(68, 32);
        flag.curveTo(80, 42, 98, 46, 94, 66);
        flag.curveTo(92, 54, 80, 52, 68, 50);
        flag.closePath();
        g.fill(flag);
        g.dispose();
        return img;
    }

    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setComposite(AlphaComposite.SrcOver);
        return g;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ------------------------------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------------------------------

    private static ResourceLocation register(String name, BufferedImage img) {
        ResourceLocation id = new ResourceLocation(PalmTunes.MOD_ID, "generated/" + name);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(toNative(img)));
        return id;
    }

    static NativeImage toNative(BufferedImage img) {
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, img.getWidth(), img.getHeight(), false);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                out.setPixelRGBA(x, y, argbToAbgr(img.getRGB(x, y)));
            }
        }
        return out;
    }

    static int argbToAbgr(int argb) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
