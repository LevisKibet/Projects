package com.ronald.ancientpeaks_lore.light;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

public class SettingsScreen extends Screen {
    private final Screen parent;
    public SettingsScreen(Screen parent) { super(Component.literal("Chroma Light")); this.parent = parent; }

    @Override
    protected void init() {
        int w = 260, x = width / 2 - w / 2, y = height / 6 + 8, step = 26;
        addRenderableWidget(toggle(x, y, w, "Coloured light", Settings.ENABLED)); y += step;
        addRenderableWidget(new Slider(x, y, w, "Strength", Settings.STRENGTH, 0, 100, "%")); y += step + 6;
        addRenderableWidget(toggle(x, y, w, "Light-giving blocks", Settings.BLOCKS)); y += step;
        addRenderableWidget(toggle(x, y, w, "Moving lights", Settings.DYNAMIC)); y += step;
        addRenderableWidget(toggle(x, y, w, "Moving lights brighten the dark", Settings.REAL_LIGHT)); y += step + 6;
        addRenderableWidget(new Slider(x, y, w, "Most lights at once", Settings.MAX_LIGHTS, 4, 64, "")); y += step;
        addRenderableWidget(new Slider(x, y, w, "Block search radius", Settings.SCAN_RADIUS, 8, 48, " blocks"));
        addRenderableWidget(Button.builder(Component.literal("Merlin's map trade..."), b -> { Settings.SPEC.save(); minecraft.setScreen(new com.ronald.ancientpeaks_lore.client.MapTradeScreen(this)); })
                .bounds(width / 2 - 154, height - 30, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 + 4, height - 30, 150, 20).build());
    }

    private static Button toggle(int x, int y, int w, String label, ForgeConfigSpec.BooleanValue v) {
        return Button.builder(Component.literal(label + ": " + (v.get() ? "On" : "Off")), b -> {
            v.set(!v.get());
            b.setMessage(Component.literal(label + ": " + (v.get() ? "On" : "Off")));
        }).bounds(x, y, w, 20).build();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal("Try /chroma test red  (and /chroma clear)"), width / 2, height - 44, 0x9A9A9A);
        super.render(g, mx, my, pt);
    }

    @Override
    public void onClose() { Settings.SPEC.save(); minecraft.setScreen(parent); }

    private static final class Slider extends AbstractSliderButton {
        private final String label, unit; private final ForgeConfigSpec.IntValue setting; private final int min, max;
        Slider(int x, int y, int w, String label, ForgeConfigSpec.IntValue setting, int min, int max, String unit) {
            super(x, y, w, 20, Component.empty(), 0);
            this.label = label; this.unit = unit; this.setting = setting; this.min = min; this.max = max;
            this.value = (setting.get() - min) / (double) (max - min);
            updateMessage();
        }
        private int current() { return (int) Math.round(min + value * (max - min)); }
        @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + current() + unit)); }
        @Override protected void applyValue() { setting.set(current()); }
    }
}
