package com.palmtunes.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Decodes encoded cover art into a small rounded square plus an accent colour. */
public final class ArtDecoder {
    public static final int SIZE = 128;
    private static final double CORNER_RADIUS = 18.0;
    private static final int MAX_SOURCE_PIXELS = 64_000_000;

    private ArtDecoder() {
    }

    public static DecodedArt decode(byte[] data) throws IOException {
        Awt.ensureHeadless();
        if (data == null || data.length == 0) {
            throw new IOException("Empty image");
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
        if (src == null) {
            throw new IOException("Unsupported image format");
        }
        if ((long) src.getWidth() * src.getHeight() > MAX_SOURCE_PIXELS) {
            throw new IOException("Image too large");
        }

        int side = Math.min(src.getWidth(), src.getHeight());
        int sx = (src.getWidth() - side) / 2;
        int sy = (src.getHeight() - side) / 2;
        BufferedImage square = src.getSubimage(sx, sy, side, side);

        // Area averaging gives clean downscales; bilinear alone shimmers on large covers.
        Image scaled = square.getScaledInstance(SIZE, SIZE, Image.SCALE_AREA_AVERAGING);
        BufferedImage dst = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(scaled, 0, 0, null);
        } finally {
            g.dispose();
        }

        int[] pixels = dst.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        int accent = accentColor(pixels);
        int[] palette = palette(pixels, accent);
        applyRoundedMask(pixels);
        return new DecodedArt(SIZE, pixels, accent, palette);
    }

    /** Saturation- and brightness-weighted average, lifted so it reads well on dark glass. */
    static int accentColor(int[] pixels) {
        double wr = 0, wg = 0, wb = 0, wsum = 0;
        float[] hsb = new float[3];
        for (int argb : pixels) {
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            Color.RGBtoHSB(r, g, b, hsb);
            double w = hsb[1] * hsb[2] + 0.02;
            wr += r * w;
            wg += g * w;
            wb += b * w;
            wsum += w;
        }
        if (wsum <= 0) {
            return 0x78D2FF;
        }
        int r = (int) Math.round(wr / wsum);
        int g = (int) Math.round(wg / wsum);
        int b = (int) Math.round(wb / wsum);
        Color.RGBtoHSB(r, g, b, hsb);
        float sat = Math.min(0.9f, hsb[1] * 1.1f);
        float bri = Math.max(hsb[2], 0.78f);
        return Color.HSBtoRGB(hsb[0], sat, bri) & 0xFFFFFF;
    }

    /**
     * Three distinct bright colours: the strongest hue families in the art (weighted by how vivid and
     * how common they are), each lifted so it reads well as a light bar on any background. If the art
     * has fewer than three colour families (black and white covers), the rest are shifted variants of the accent.
     */
    static int[] palette(int[] pixels, int accent) {
        final int buckets = 12;
        double[] weight = new double[buckets];
        double[][] sum = new double[buckets][3];
        float[] hsb = new float[3];
        for (int argb : pixels) {
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            Color.RGBtoHSB(r, g, b, hsb);
            double w = hsb[1] * hsb[1] * hsb[2];          // vivid and not dark
            if (w < 0.02) {
                continue;                                  // greys, near-black and near-white carry no colour
            }
            int k = Math.min(buckets - 1, (int) (hsb[0] * buckets));
            weight[k] += w;
            sum[k][0] += r * w;
            sum[k][1] += g * w;
            sum[k][2] += b * w;
        }
        int[] out = new int[3];
        float[] chosenHue = new float[3];
        int count = 0;
        boolean[] used = new boolean[buckets];
        while (count < 3) {
            int best = -1;
            for (int k = 0; k < buckets; k++) {
                if (used[k] || weight[k] <= 0) {
                    continue;
                }
                float hue = (k + 0.5f) / buckets;
                boolean close = false;
                for (int j = 0; j < count; j++) {
                    float d = Math.abs(hue - chosenHue[j]);
                    d = Math.min(d, 1f - d);
                    if (d < 0.11f) {                       // within ~40 degrees of one we already have
                        close = true;
                    }
                }
                if (!close && (best < 0 || weight[k] > weight[best])) {
                    best = k;
                }
            }
            if (best < 0) {
                break;
            }
            used[best] = true;
            int r = (int) Math.round(sum[best][0] / weight[best]);
            int g = (int) Math.round(sum[best][1] / weight[best]);
            int b = (int) Math.round(sum[best][2] / weight[best]);
            Color.RGBtoHSB(r, g, b, hsb);
            out[count] = lift(hsb[0], hsb[1], hsb[2]);
            chosenHue[count] = hsb[0];
            count++;
        }
        if (count == 0) {
            Color.RGBtoHSB((accent >> 16) & 0xFF, (accent >> 8) & 0xFF, accent & 0xFF, hsb);
            out[0] = lift(hsb[0], hsb[1], hsb[2]);
            chosenHue[0] = hsb[0];
            count = 1;
        }
        // Fill the gaps with neighbours of the first colour so there is always a three-stop gradient.
        float baseHue = chosenHue[0];
        float[] shifts = {0.06f, -0.06f};
        for (int i = count, s = 0; i < 3; i++, s++) {
            Color.RGBtoHSB((out[0] >> 16) & 0xFF, (out[0] >> 8) & 0xFF, out[0] & 0xFF, hsb);
            out[i] = lift((baseHue + shifts[s] + 1f) % 1f, Math.max(0.3f, hsb[1] * 0.85f), hsb[2]);
        }
        return out;
    }

    private static int lift(float hue, float sat, float bri) {
        return Color.HSBtoRGB(hue, Math.min(1f, Math.max(0.35f, sat)), Math.max(0.82f, bri)) & 0xFFFFFF;
    }

    /** Anti-aliased rounded corners via a signed-distance test. */
    static void applyRoundedMask(int[] pixels) {
        double half = SIZE / 2.0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dx = Math.abs(x + 0.5 - half) - (half - CORNER_RADIUS);
                double dy = Math.abs(y + 0.5 - half) - (half - CORNER_RADIUS);
                double outside = Math.hypot(Math.max(dx, 0), Math.max(dy, 0)) + Math.min(Math.max(dx, dy), 0) - CORNER_RADIUS;
                double coverage = Math.max(0, Math.min(1, 0.5 - outside));
                int i = y * SIZE + x;
                int a = (int) Math.round(coverage * 255);
                pixels[i] = (a << 24) | (pixels[i] & 0xFFFFFF);
            }
        }
    }
}
