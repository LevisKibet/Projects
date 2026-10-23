package com.ronald.ancientpeaks;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;

/** Mods > Ruins of Logres > Config: the island map trade on the left, the gateways and the island's distance on the right. */
public class RuinsSettingsScreen extends Screen {
    private final Screen parent;
    public RuinsSettingsScreen(Screen parent) { super(Component.literal("Ruins of Logres")); this.parent = parent; }

    static void register() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new RuinsSettingsScreen(parent)));
    }

    @Override
    protected void init() {
        int w = 170, gap = 10, left = width / 2 - w - gap / 2, right = width / 2 + gap / 2, top = 46, step = 24;
        int y = top;
        addRenderableWidget(toggle(left, y, w, "Wandering trader", RuinsConfig.TRADER)); y += step;
        addRenderableWidget(new Percent(left, y, w, "Chance", RuinsConfig.TRADER_CHANCE)); y += step;
        addRenderableWidget(new Slider(left, y, w, "Price", RuinsConfig.TRADER_PRICE, 1, 64, 1, " emeralds")); y += step + 6;
        addRenderableWidget(toggle(left, y, w, "Cartographer", RuinsConfig.CARTOGRAPHER)); y += step;
        addRenderableWidget(new Percent(left, y, w, "Chance", RuinsConfig.CARTOGRAPHER_CHANCE)); y += step;
        addRenderableWidget(new Slider(left, y, w, "Price", RuinsConfig.CARTOGRAPHER_PRICE, 1, 64, 1, " emeralds")); y += step + 6;
        addRenderableWidget(new Slider(left, y, w, "Maps each", RuinsConfig.USES, 1, 12, 1, ""));
        y = top;
        addRenderableWidget(toggle(right, y, w, "Highland Gateways", RuinsConfig.GATEWAYS)); y += step + 6 + 14;
        addRenderableWidget(new Slider(right, y, w, "Nearest", RuinsConfig.DISTANCE_MIN, 2000, 29000, 500, " blocks")); y += step;
        addRenderableWidget(new Slider(right, y, w, "Farthest", RuinsConfig.DISTANCE_MAX, 3000, 30000, 500, " blocks"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        int w = 170, left = width / 2 - w - 5, right = width / 2 + 5;
        g.drawString(font, "Island map trade", left, 32, 0xE0C060);
        g.drawString(font, "World", right, 32, 0xE0C060);
        g.drawString(font, "Island distance from spawn", right, 46 + 24 + 10, 0xA0A0A0);
        g.drawString(font, "(new worlds only)", right, 46 + 24 * 3 + 16, 0x808080);
        super.render(g, mx, my, pt);
    }

    @Override
    public void onClose() { RuinsConfig.SPEC.save(); minecraft.setScreen(parent); }

    private static Button toggle(int x, int y, int w, String label, ForgeConfigSpec.BooleanValue v) {
        return Button.builder(Component.literal(label + ": " + (v.get() ? "On" : "Off")), b -> {
            v.set(!v.get());
            b.setMessage(Component.literal(label + ": " + (v.get() ? "On" : "Off")));
        }).bounds(x, y, w, 20).build();
    }

    private static final class Slider extends AbstractSliderButton {
        private final String label, unit; private final ForgeConfigSpec.IntValue setting; private final int min, max, step;
        Slider(int x, int y, int w, String label, ForgeConfigSpec.IntValue setting, int min, int max, int step, String unit) {
            super(x, y, w, 20, Component.empty(), 0);
            this.label = label; this.unit = unit; this.setting = setting; this.min = min; this.max = max; this.step = step;
            this.value = (setting.get() - min) / (double) (max - min);
            updateMessage();
        }
        private int current() { return min + (int) Math.round(value * (max - min) / step) * step; }
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
