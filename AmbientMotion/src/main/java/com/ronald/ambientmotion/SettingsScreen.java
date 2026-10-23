package com.ronald.ambientmotion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/** A small settings page: strengths as sliders, the depth of field style and lantern swing as buttons. */
public class SettingsScreen extends Screen {
    private final Screen parent;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Ambient Motion"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = 260, x = width / 2 - w / 2, y = height / 6 + 16, step = 26;
        addRenderableWidget(new Slider(x, y, w, "Motion blur (when turning)", Settings.MOTION_BLUR, 0, 100, 1, "%"));
        y += step + 10;
        addRenderableWidget(Button.builder(dofLabel(), b -> {
            Settings.DOF_MODE.set(Settings.dofMode().next());
            b.setMessage(dofLabel());
        }).bounds(x, y, w, 20).build());
        y += step;
        addRenderableWidget(new Slider(x, y, w, "Background blur", Settings.DOF_STRENGTH, 0, 100, 1, "%"));
        y += step;
        addRenderableWidget(new RangeSlider(x, y, w));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 100, height - 30, 200, 20).build());
    }

    private static Component dofLabel() { return Component.literal("Depth of field: " + Settings.dofMode().label); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal("Off while an Oculus shader pack is on"), width / 2, height - 44, 0x9A9A9A);
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        Settings.SPEC.save();
        minecraft.setScreen(parent);
    }

    /** A slider bound straight to an integer setting. */
    private static final class Slider extends AbstractSliderButton {
        private final String label, unit;
        private final ForgeConfigSpec.IntValue setting;
        private final int min, max;

        Slider(int x, int y, int w, String label, ForgeConfigSpec.IntValue setting, int min, int max, int step, String unit) {
            super(x, y, w, 20, Component.empty(), 0);
            this.label = label; this.unit = unit; this.setting = setting; this.min = min; this.max = max;
            this.value = Math.max(0, Math.min(1, (setting.get() - min) / (double) (max - min)));
            updateMessage();
        }

        private int current() { return (int) Math.round(min + value * (max - min)); }
        @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + current() + unit)); }
        @Override protected void applyValue() { setting.set(current()); }
    }

    /** Close-up focus range, in quarter blocks. */
    private static final class RangeSlider extends AbstractSliderButton {
        RangeSlider(int x, int y, int w) {
            super(x, y, w, 20, Component.empty(), 0);
            this.value = (Settings.focusRange() - 0.25) / 3.75;
            updateMessage();
        }
        private double current() { return Math.round((0.25 + value * 3.75) * 4) / 4.0; }
        @Override protected void updateMessage() { setMessage(Component.literal("Focus range: " + current() + (current() == 1.0 ? " block" : " blocks"))); }
        @Override protected void applyValue() { Settings.FOCUS_RANGE.set(current()); }
    }
}
