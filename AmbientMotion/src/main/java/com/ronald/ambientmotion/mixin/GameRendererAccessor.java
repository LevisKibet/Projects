package com.ronald.ambientmotion.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    /** The field of view as the game uses it (sprinting, zoom and so on), without the view bobbing. */
    @Invoker("getFov")
    double ambientmotion$fov(Camera camera, float partialTick, boolean useFovSetting);
}
