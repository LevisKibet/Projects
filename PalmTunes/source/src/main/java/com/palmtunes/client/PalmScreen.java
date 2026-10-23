package com.palmtunes.client;

import com.palmtunes.media.MediaCommand;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * An invisible screen. Its only jobs are to release the mouse cursor and to receive clicks; the
 * actual panel is drawn in the 3D world, above the player's palm.
 */
public final class PalmScreen extends Screen {
    /** Key repeat would instantly close the panel if the opening key is still held down. */
    private boolean toggleArmed;

    public PalmScreen() {
        super(Component.translatable("screen.palmtunes.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // music keeps playing and so does the world
    }

    @Override
    protected void init() {
        PalmInteraction.reset();
    }

    @Override
    public void removed() {
        PalmInteraction.reset();
        PalmController.onScreenClosed();
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // Deliberately empty: no dimming, the player and the panel stay fully visible.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        PalmInteraction.mouseMoved(mouseX, mouseY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        PalmInteraction.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        PalmInteraction.mouseMoved(mouseX, mouseY);
        return PalmInteraction.mouseDown(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        PalmInteraction.mouseMoved(mouseX, mouseY);
        return PalmInteraction.mouseDrag(mouseX, mouseY) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return PalmInteraction.mouseUp() || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (PalmClient.TOGGLE.matches(keyCode, scanCode)) {
            toggleArmed = true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // The same key that opened the panel closes it (once it has been released, so key repeat cannot close it).
        if (PalmClient.TOGGLE.matches(keyCode, scanCode)) {
            if (toggleArmed) {
                onClose();
            }
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE -> PalmInteraction.togglePlayPause();
            case GLFW.GLFW_KEY_RIGHT -> PalmInteraction.run(PalmLayout.Hit.NEXT, MediaCommand.NEXT);
            case GLFW.GLFW_KEY_S -> PalmInteraction.run(PalmLayout.Hit.SHUFFLE, MediaCommand.TOGGLE_SHUFFLE);
            case GLFW.GLFW_KEY_R -> PalmInteraction.run(PalmLayout.Hit.REPEAT, MediaCommand.CYCLE_REPEAT);
            case GLFW.GLFW_KEY_LEFT -> PalmInteraction.run(PalmLayout.Hit.PREVIOUS, MediaCommand.PREVIOUS);
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }
}
