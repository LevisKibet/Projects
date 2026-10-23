package com.ronald.ancientpeaks_lore.client;

import com.ronald.ancientpeaks_lore.LoreContent;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vortigern's boss bar, drawn in place of the vanilla one: just his name, and a gold-framed health bar between two
 * gold diamonds - crimson to gold, a pale trail showing the damage just dealt, a sheen sweeping across it, and a red
 * pulse when he is nearly done.
 */
public final class EpicBossBar {
    private EpicBossBar() {}
    public static final String KEY = "bossbar.ancientpeaks_lore.vortigern";
    static final int MAX_HP = 400;
    private static final Map<UUID, float[]> TRAIL = new HashMap<>();   // shown trail, last seen progress

    public static void register() { MinecraftForge.EVENT_BUS.addListener(EpicBossBar::onBar); }

    private static void onBar(CustomizeGuiOverlayEvent.BossEventProgress e) {
        LerpingBossEvent b = e.getBossEvent();
        if (!(b.getName().getContents() instanceof TranslatableContents tc) || !KEY.equals(tc.getKey())) return;
        e.setCanceled(true);
        e.setIncrement(draw(e.getGuiGraphics(), b, e.getY()));
    }

    private static int argb(int a, int rgb) { return (Mth.clamp(a, 0, 255) << 24) | (rgb & 0xFFFFFF); }

    private static int mix(int c1, int c2, float k) {
        int r = (int) Mth.lerp(k, (c1 >> 16) & 255, (c2 >> 16) & 255), g = (int) Mth.lerp(k, (c1 >> 8) & 255, (c2 >> 8) & 255), bl = (int) Mth.lerp(k, c1 & 255, c2 & 255);
        return (r << 16) | (g << 8) | bl;
    }

    private static void text(GuiGraphics g, Font f, String s, float x, float y, float scale, int argb, boolean shadow) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0); g.pose().scale(scale, scale, 1);
        g.drawString(f, s, 0, 0, argb, shadow);
        g.pose().popPose();
    }

    private static float width(Font f, String s, float scale) { return f.width(s) * scale; }

    /** A diamond (a square turned 45 degrees) centred at x,y. */
    private static void diamond(GuiGraphics g, float x, float y, float r, int argb) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(45));
        g.fill((int) -r, (int) -r, (int) r, (int) r, argb);
        g.pose().popPose();
    }

    private static int draw(GuiGraphics g, LerpingBossEvent b, int topY) {
        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        int sw = g.guiWidth();
        int W = Math.min(sw - 80, 300), x0 = (sw - W) / 2;
        float p = Mth.clamp(b.getProgress(), 0, 1);
        float time = (Util.getMillis() % 100000L) / 1000f;

        // the name
        String name = "VORTIGERN THE USURPER";
        float scale = 1.2f;
        text(g, f, name, sw / 2f - width(f, name, scale) / 2, topY - 6, scale, argb(255, 0xF2E6CE), true);

        // the bar
        int bx = x0, by = topY + 8, bw = W, bh = 7;
        float[] tr = TRAIL.computeIfAbsent(b.getId(), k -> new float[]{p, p});
        if (p < tr[1]) tr[0] = Math.max(tr[0], tr[1]);
        tr[1] = p;
        tr[0] = Math.max(p, tr[0] - 0.004f);
        boolean critical = p < 0.25f;
        float pulse = critical ? (float) (0.5 + 0.5 * Math.sin(time * 6)) : 0;
        if (critical) g.fill(bx - 3, by - 3, bx + bw + 3, by + bh + 3, argb((int) (80 * pulse), 0xFF2020));
        g.fill(bx - 2, by - 2, bx + bw + 2, by + bh + 2, argb(255, 0x1A0A06));
        g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, argb(255, 0xC99A3A));
        g.fill(bx, by, bx + bw, by + bh, argb(255, 0x140406));
        g.fill(bx, by, bx + (int) (bw * tr[0]), by + bh, argb(190, 0xF4E3C0));
        int fillW = (int) (bw * p);
        for (int x = 0; x < fillW; x += 2) {
            float k = x / (float) bw;
            int c = k < 0.5f ? mix(0x7E0C12, 0xD8532A, k / 0.5f) : mix(0xD8532A, 0xF1C04C, (k - 0.5f) / 0.5f);
            g.fill(bx + x, by, bx + Math.min(x + 2, fillW), by + bh, argb(255, c));
        }
        g.fill(bx, by, bx + fillW, by + 2, argb(70, 0xFFFFFF));
        float sheen = (time * 0.35f) % 1.4f - 0.2f;
        int sxp = (int) (bx + bw * sheen);
        for (int i = 0; i < 14; i++) {
            int x = sxp + i;
            if (x < bx || x >= bx + fillW) continue;
            g.fill(x, by, x + 1, by + bh, argb((int) (60 * Math.sin(Math.PI * i / 14)), 0xFFF4D0));
        }
        // a gold diamond at each end
        diamond(g, bx - 4, by + bh / 2f, 3.5f, argb(255, 0xC99A3A));
        diamond(g, bx - 4, by + bh / 2f, 1.8f, argb(255, 0xFFE7A0));
        diamond(g, bx + bw + 4, by + bh / 2f, 3.5f, argb(255, 0xC99A3A));
        diamond(g, bx + bw + 4, by + bh / 2f, 1.8f, argb(255, 0xFFE7A0));
        return 30;
    }
}
