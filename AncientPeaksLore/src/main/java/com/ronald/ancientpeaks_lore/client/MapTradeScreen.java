package com.ronald.ancientpeaks_lore.client;

import com.ronald.ancientpeaks_lore.LoreConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/** The Map to Merlin's Cabin: who sells it, how often and for how much. */
public class MapTradeScreen extends Screen {
    private final Screen parent;
    public MapTradeScreen(Screen parent) { super(Component.literal("Merlin's map trade")); this.parent = parent; }

    @Override
    protected void init() {
        int w = 260, x = width / 2 - w / 2, y = height / 6 + 8, step = 24;
        addRenderableWidget(toggle(x, y, w, "Wandering trader", LoreConfig.TRADER)); y += step;
        addRenderableWidget(new Percent(x, y, w, "Chance", LoreConfig.TRADER_CHANCE)); y += step;
        addRenderableWidget(new Slider(x, y, w, "Price", LoreConfig.TRADER_PRICE, 1, 64, " emeralds")); y += step + 6;
        addRenderableWidget(toggle(x, y, w, "Cartographer", LoreConfig.CARTOGRAPHER)); y += step;
        addRenderableWidget(new Percent(x, y, w, "Chance", LoreConfig.CARTOGRAPHER_CHANCE)); y += step;
        addRenderableWidget(new Slider(x, y, w, "Price", LoreConfig.CARTOGRAPHER_PRICE, 1, 64, " emeralds")); y += step + 6;
        addRenderableWidget(new Slider(x, y, w, "Maps each", LoreConfig.USES, 1, 12, ""));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 100, height - 30, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        super.render(g, mx, my, pt);
    }

    @Override
    public void onClose() { LoreConfig.SPEC.save(); minecraft.setScreen(parent); }

    private static Button toggle(int x, int y, int w, String label, ForgeConfigSpec.BooleanValue v) {
        return Button.builder(Component.literal(label + ": " + (v.get() ? "On" : "Off")), b -> {
            v.set(!v.get());
            b.setMessage(Component.literal(label + ": " + (v.get() ? "On" : "Off")));
        }).bounds(x, y, w, 20).build();
    }

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

    private static final class Percent extends AbstractSliderButton {
        private final String label; private final ForgeConfigSpec.DoubleValue setting;
        Percent(int x, int y, int w, String label, ForgeConfigSpec.DoubleValue setting) {
            super(x, y, w, 20, Component.empty(), setting.get());
            this.label = label; this.setting = setting;
            updateMessage();
        }
        private double current() { return Math.round(value * 100) / 100.0; }
        @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + Math.round(current() * 100) + "%")); }
        @Override protected void applyValue() { setting.set(current()); }
    }
}
