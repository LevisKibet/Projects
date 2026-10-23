package com.ronald.ancientpeaks_lore.client;

import com.ronald.ancientpeaks_lore.AncientPeaksLore;
import com.ronald.ancientpeaks_lore.LoreBooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The compendium, opened: an old book on parchment. First spread is the contents (what you've found, and blank lines
 * for what you haven't, with a hint where to look); each found chronicle has its picture on the left and its words
 * on the right, running on over further spreads when it's long.
 */
public class CompendiumScreen extends Screen {
    private static final ResourceLocation BOOK = new ResourceLocation(AncientPeaksLore.MODID, "textures/gui/compendium.png");
    private static final int W = 256, H = 180, TEX_H = 196;           // on screen (the texture is twice that; the ribbon hangs below)
    private static final int INK = 0x3A2817, FADED = 0x9C8A6A, RED = 0x7A1E12;
    private static final int LX = 22, RX = 138, TOP = 22, PAGE_W = 98, LINE = 10, LINES = 14;
    private static final int PIC_H = 65;                              // pictures are 3:2

    private static final Set<Integer> KNOWN = new HashSet<>();
    public static void known(int[] ids) { KNOWN.clear(); for (int i : ids) KNOWN.add(i); }
    public static int knownCount() { return KNOWN.size(); }
    public static void open() { Minecraft.getInstance().setScreen(new CompendiumScreen()); }

    private int entry = 0, spread = 0;        // entry 0 = the contents
    private int left, top;
    private PageButton next, prev;

    public CompendiumScreen() { super(Component.literal("Compendium")); }

    @Override
    protected void init() {
        left = (width - W) / 2; top = (height - H) / 2;
        // page arrows sit below the book with the contents button, so the pages are all for writing
        next = addRenderableWidget(new PageButton(left + W / 2 + 40, top + H + 6, true, b -> turn(1), true));
        prev = addRenderableWidget(new PageButton(left + W / 2 - 63, top + H + 6, false, b -> turn(-1), true));
        addRenderableWidget(Button.builder(Component.literal("Contents"), b -> { entry = 0; spread = 0; refresh(); })
                .bounds(left + W / 2 - 30, top + H + 4, 60, 18).build());
        refresh();
    }

    private void refresh() {
        next.visible = entry == 0 ? nextKnown(0) > 0 : (spread + 1 < spreads(entry) || nextKnown(entry) > 0);
        prev.visible = entry != 0;
    }

    private int nextKnown(int from) {
        for (int n = from + 1; n <= LoreBooks.ALL.size(); n++) if (KNOWN.contains(n)) return n;
        return 0;
    }
    private int prevKnown(int from) {
        for (int n = from - 1; n >= 1; n--) if (KNOWN.contains(n)) return n;
        return 0;
    }

    private void turn(int d) {
        if (d > 0) {
            if (entry != 0 && spread + 1 < spreads(entry)) spread++;
            else { int n = nextKnown(entry); if (n > 0) { entry = n; spread = 0; } }
        } else {
            if (entry != 0 && spread > 0) spread--;
            else { int n = prevKnown(entry); entry = n; spread = n == 0 ? 0 : spreads(n) - 1; }
        }
        refresh();
    }

    // ------------------------------------------------------------------ text
    private List<FormattedCharSequence> text(int n) {
        LoreBooks.Book b = LoreBooks.byNumber(n);
        List<FormattedCharSequence> out = new ArrayList<>();
        for (String p : b.pages()) {
            for (String para : p.split("\n")) out.addAll(font.split(Component.literal(para), PAGE_W));
            out.add(FormattedCharSequence.EMPTY);
        }
        return out;
    }
    /** Spread 0 holds the picture and the first page of text; every later spread holds two pages of text. */
    private int spreads(int n) {
        int lines = text(n).size();
        return lines <= LINES ? 1 : 1 + (int) Math.ceil((lines - LINES) / (double) (LINES * 2));
    }

    // ------------------------------------------------------------------ drawing
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.blit(BOOK, left, top, W, TEX_H, 0, 0, 512, 392, 512, 392);
        if (entry == 0) contents(g, mx, my); else page(g);
        super.render(g, mx, my, pt);
    }

    private void contents(GuiGraphics g, int mx, int my) {
        int total = LoreBooks.ALL.size();
        centred(g, "The Chronicles", left + LX + PAGE_W / 2, top + TOP, RED);
        centred(g, "of Logres", left + LX + PAGE_W / 2, top + TOP + 10, RED);
        divider(g, left + LX + PAGE_W / 2, top + TOP + 22);
        centred(g, KNOWN.size() + " of " + total + " found", left + LX + PAGE_W / 2, top + TOP + 28, FADED);
        for (int i = 0; i < total; i++) {
            int n = i + 1;
            int[] at = slot(i);
            if (at == null) break;
            int x = at[0], y = at[1];
            boolean has = KNOWN.contains(n);
            String label = roman(n) + ". " + (has ? LoreBooks.byNumber(n).title() : "...");
            label = font.plainSubstrByWidth(label, PAGE_W);
            boolean hover = has && mx >= x && mx < x + PAGE_W && my >= y && my < y + 9;
            g.drawString(font, label, x, y, has ? (hover ? RED : INK) : FADED, false);
            if (!has && mx >= x && mx < x + PAGE_W && my >= y && my < y + 8)
                g.renderTooltip(font, Component.literal(hint(LoreBooks.byNumber(n).group())), mx, my);
        }
    }

    private void page(GuiGraphics g) {
        LoreBooks.Book b = LoreBooks.byNumber(entry);
        List<FormattedCharSequence> lines = text(entry);
        if (spread == 0) {
            // the picture, in a double ink rule
            int px = left + LX, py = top + TOP, pw = PAGE_W, ph = PIC_H;
            g.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF6B5236);
            g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFFE5D6B4);
            g.blit(new ResourceLocation(AncientPeaksLore.MODID, "textures/gui/compendium/book_" + entry + ".png"), px, py, pw, ph, 0, 0, 384, 256, 384, 256);
            int y = py + ph + 7;
            for (FormattedCharSequence l : font.split(Component.literal(b.title()), PAGE_W)) { centred(g, l, px + PAGE_W / 2, y, RED); y += LINE; }
            String by = font.plainSubstrByWidth("by " + b.author(), PAGE_W);
            centred(g, by, px + PAGE_W / 2, y + 1, FADED);
            divider(g, px + PAGE_W / 2, y + 14);
            centred(g, roman(entry), px + PAGE_W / 2, top + TOP + LINES * LINE - 8, FADED);
            draw(g, lines, 0, LINES, left + RX);
        } else {
            int from = LINES + (spread - 1) * LINES * 2;
            draw(g, lines, from, LINES, left + LX);
            draw(g, lines, from + LINES, LINES, left + RX);
        }
    }

    private void draw(GuiGraphics g, List<FormattedCharSequence> lines, int from, int count, int x) {
        for (int i = 0; i < count && from + i < lines.size(); i++) g.drawString(font, lines.get(from + i), x, top + TOP + i * LINE, INK, false);
    }

    private void centred(GuiGraphics g, String s, int cx, int y, int colour) { g.drawString(font, s, cx - font.width(s) / 2, y, colour, false); }
    private void centred(GuiGraphics g, FormattedCharSequence s, int cx, int y, int colour) { g.drawString(font, s, cx - font.width(s) / 2, y, colour, false); }

    /** A short ink rule with a diamond in the middle. */
    private static void divider(GuiGraphics g, int cx, int y) {
        int c = 0xB07A5C3A;
        g.fill(cx - 30, y, cx - 4, y + 1, c);
        g.fill(cx + 4, y, cx + 30, y + 1, c);
        g.fill(cx - 1, y - 2, cx + 1, y + 3, c);
        g.fill(cx - 2, y - 1, cx + 2, y + 2, c);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (entry == 0 && button == 0) {
            for (int i = 0; i < LoreBooks.ALL.size(); i++) {
                int[] at = slot(i);
                if (at == null) break;
                int x = at[0], y = at[1];
                if (KNOWN.contains(i + 1) && mx >= x && mx < x + PAGE_W && my >= y && my < y + 8) {
                    entry = i + 1; spread = 0; refresh();
                    Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1f));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    /** Where line i of the contents goes: 13 under the title on the left page, then 18 on the right. */
    private int[] slot(int i) {
        if (i < 13) return new int[]{left + LX, top + TOP + 40 + i * 8};
        if (i < 31) return new int[]{left + RX, top + TOP + (i - 13) * 8};
        return null;
    }

    @Override public boolean isPauseScreen() { return false; }

    static String hint(String group) {
        return switch (group) {
            case "village" -> "Look in the village houses.";
            case "guard" -> "Look where the guards kept watch.";
            case "chapel" -> "Look in the chapels along the roads.";
            case "harbour" -> "Look by the harbour.";
            case "raider" -> "The raiders carry these.";
            case "merlin" -> "Merlin keeps this one himself.";
            case "court" -> "Look in the King's own rooms.";
            default -> "Somewhere in Logres.";
        };
    }

    static String roman(int n) {
        String[] r = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { sb.append(r[i]); n -= v[i]; }
        return sb.toString();
    }
}
