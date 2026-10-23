package com.ronald.rivergence;

import com.ronald.rivergence.snow.SnowConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/** Mods > Rivergence > Config > Snow. */
public class SnowSettingsScreen extends Screen {
    private final Screen parent;
    private boolean redraw;
    public SnowSettingsScreen(Screen parent) { super(Component.literal("Rivergence: Snow")); this.parent = parent; }

    @Override
    protected void init() {
        int w = 260, x = width / 2 - w / 2, y = height / 6 + 8;
        addRenderableWidget(toggle(x, y, w, "Rivergence snow", SnowConfig.ENABLED, true));
        addRenderableWidget(toggle(x, y + 24, w, "Smooth snow surface", SnowConfig.SMOOTH, true));
        addRenderableWidget(Button.builder(detailLabel(), b -> {
            SnowConfig.Detail[] all = SnowConfig.Detail.values();
            SnowConfig.DETAIL.set(all[(SnowConfig.DETAIL.get().ordinal() + 1) % all.length]);
            b.setMessage(detailLabel());
            redraw = true;
        }).bounds(x, y + 48, w, 20).build());
        addRenderableWidget(toggle(x, y + 72, w, "Snow hangs over edges", SnowConfig.OVERHANGS, true));
        addRenderableWidget(toggle(x, y + 96, w, "Trails through the snow", SnowConfig.TRAILS, true));
        addRenderableWidget(toggle(x, y + 120, w, "Animals leave trails", SnowConfig.MOB_TRAILS, false));
        addRenderableWidget(toggle(x, y + 144, w, "Wading through deep snow", SnowConfig.WADING, false));
        addRenderableWidget(toggle(x, y + 168, w, "Drifts (new terrain)", SnowConfig.DRIFTS, false));
        addRenderableWidget(toggle(x, y + 192, w, "Snow on and under trees", SnowConfig.TREE_SNOW, false));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private static Component detailLabel() {
        String d = SnowConfig.DETAIL.get().name();
        return Component.literal("Snow detail: " + d.charAt(0) + d.substring(1).toLowerCase());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        super.render(g, mx, my, pt);
    }

    @Override
    public void onClose() {
        SnowConfig.SPEC.save();
        if (redraw && minecraft.level != null) minecraft.levelRenderer.allChanged();
        minecraft.setScreen(parent);
    }

    private Button toggle(int x, int y, int w, String label, ForgeConfigSpec.BooleanValue v, boolean visual) {
        return Button.builder(Component.literal(label + ": " + (v.get() ? "On" : "Off")), b -> {
            v.set(!v.get());
            b.setMessage(Component.literal(label + ": " + (v.get() ? "On" : "Off")));
            if (visual) redraw = true;
        }).bounds(x, y, w, 20).build();
    }
}
