package com.ronald.rivergence;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;

/** Mods > Rivergence > Config. */
public class RivergenceSettingsScreen extends Screen {
    private final Screen parent;
    public RivergenceSettingsScreen(Screen parent) { super(Component.literal("Rivergence")); this.parent = parent; }

    static void register() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new RivergenceSettingsScreen(parent)));
    }

    @Override
    protected void init() {
        int w = 260, x = width / 2 - w / 2, y = height / 6 + 8;
        addRenderableWidget(toggle(x, y, w, "Rivergence water", RivergenceConfig.ENABLED));
        addRenderableWidget(toggle(x, y + 24, w, "Structures avoid rivers and lakes", RivergenceConfig.STRUCTURES_AVOID));
        addRenderableWidget(toggle(x, y + 48, w, "Replace vanilla rivers", RivergenceConfig.REPLACE_RIVERS));
        addRenderableWidget(toggle(x, y + 72, w, "Remove vanilla water springs", RivergenceConfig.NO_SPRINGS));
        addRenderableWidget(toggle(x, y + 96, w, "Plants by the water", RivergenceConfig.FLORA));
        addRenderableWidget(toggle(x, y + 120, w, "Fish and axolotls", RivergenceConfig.FAUNA));
        addRenderableWidget(toggle(x, y + 144, w, "Bays and coves", RivergenceConfig.BAYS));
        addRenderableWidget(toggle(x, y + 168, w, "Mist, spray and local showers", RivergenceConfig.WEATHER));
        addRenderableWidget(toggle(x, y + 192, w, "Canals by villages", RivergenceConfig.CANALS));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        super.render(g, mx, my, pt);
    }

    @Override
    public void onClose() { RivergenceConfig.SPEC.save(); minecraft.setScreen(parent); }

    private static Button toggle(int x, int y, int w, String label, ForgeConfigSpec.BooleanValue v) {
        return Button.builder(Component.literal(label + ": " + (v.get() ? "On" : "Off")), b -> {
            v.set(!v.get());
            b.setMessage(Component.literal(label + ": " + (v.get() ? "On" : "Off")));
        }).bounds(x, y, w, 20).build();
    }
}
