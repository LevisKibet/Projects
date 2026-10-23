package com.ronald.ancientpeaks_lore.client;

import com.ronald.ancientpeaks_lore.AncientPeaksLore;
import com.ronald.ancientpeaks_lore.LoreBooks;
import com.ronald.ancientpeaks_lore.LoreContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/** "Compendium updated", slid in at the top right on parchment, with the enchanting table's chime. */
public class CompendiumToast implements Toast {
    private static final ResourceLocation BG = new ResourceLocation(AncientPeaksLore.MODID, "textures/gui/compendium_toast.png");
    private final String title;
    private final String count;

    private CompendiumToast(String title, String count) { this.title = title; this.count = count; }

    public static void show(int book, int found) {
        var mc = Minecraft.getInstance();
        LoreBooks.Book b = LoreBooks.byNumber(book);
        if (b == null) return;
        mc.getToasts().addToast(new CompendiumToast(b.title(), found + " / " + LoreBooks.ALL.size()));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ENCHANTMENT_TABLE_USE, 1.0f, 0.9f));
    }

    @Override
    public Visibility render(GuiGraphics g, ToastComponent toasts, long time) {
        g.blit(BG, 0, 0, width(), height(), 0, 0, 320, 64, 320, 64);
        g.renderFakeItem(new ItemStack(LoreContent.COMPENDIUM.get()), 8, 8);
        var font = toasts.getMinecraft().font;
        g.drawString(font, "Compendium updated", 30, 7, 0x7A1E12, false);
        String t = font.plainSubstrByWidth(title, width() - 34 - font.width(count) - 4);
        if (!t.equals(title)) t = font.plainSubstrByWidth(title, width() - 34 - font.width(count) - 4 - font.width("...")) + "...";
        g.drawString(font, t, 30, 18, 0x3A2817, false);
        g.drawString(font, count, width() - 6 - font.width(count), 18, 0x9C8A6A, false);
        return time >= 5000L ? Visibility.HIDE : Visibility.SHOW;
    }
}
