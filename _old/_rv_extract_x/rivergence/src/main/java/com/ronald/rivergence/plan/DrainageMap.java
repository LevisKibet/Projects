package com.ronald.rivergence.plan;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** A picture of a drainage plan: the land shaded by height, the sea, the lakes, and the streams drawn by their size. */
public final class DrainageMap {
    private DrainageMap() {}

    public static BufferedImage draw(Drainage p, int px, double streamCells) {
        int n = p.n, W = n * px;
        BufferedImage img = new BufferedImage(W, W, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            int k = i + j * n;
            Color c;
            if (p.sea(k)) c = new Color(28, 62, 110);
            else if (p.lake[k] >= 0) c = new Color(58, 120, 190);
            else c = land(p.h[k], shade(p, i, j));
            g.setColor(c);
            g.fillRect(i * px, j * px, px, px);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(70, 150, 230));
        for (int k = 0; k < n * n; k++) {
            if (p.acc[k] < streamCells || p.sea(k)) continue;
            int d = p.down(k);
            if (d < 0) continue;
            float w = (float) Math.max(1, px * 0.35 * (1 + Math.log(p.acc[k] / streamCells) / Math.log(2)));
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int i = k % n, j = k / n, di = d % n, dj = d / n;
            g.drawLine(i * px + px / 2, j * px + px / 2, di * px + px / 2, dj * px + px / 2);
        }
        g.dispose();
        return img;
    }

    /** Light from the north-west, for relief. */
    private static double shade(Drainage p, int i, int j) {
        int n = p.n;
        int a = Math.max(0, i - 1) + Math.max(0, j - 1) * n, b = Math.min(n - 1, i + 1) + Math.min(n - 1, j + 1) * n;
        double s = (p.h[a] - p.h[b]) / (p.cell * 2.0);
        return Math.max(0.55, Math.min(1.35, 1 + s * 2.5));
    }

    private static Color land(double h, double sh) {
        double t = Math.max(0, Math.min(1, (h - 63) / 140));
        int r, g, b;
        if (t < 0.35) { double u = t / 0.35; r = (int) (96 + 70 * u); g = (int) (150 + 20 * u); b = (int) (80 + 10 * u); }
        else if (t < 0.7) { double u = (t - 0.35) / 0.35; r = (int) (166 - 20 * u); g = (int) (170 - 40 * u); b = (int) (90 + 10 * u); }
        else { double u = (t - 0.7) / 0.3; r = (int) (146 + 100 * u); g = (int) (130 + 116 * u); b = (int) (100 + 146 * u); }
        return new Color(clamp(r * sh), clamp(g * sh), clamp(b * sh));
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }
}
