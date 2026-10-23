package com.palmtunes.mixin;

import com.palmtunes.client.Cinematic;
import com.palmtunes.client.PalmCameraController;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the palm panel take over the camera after vanilla has placed it. Doing it here (rather than
 * in a Forge camera event) is what allows the position, not just the angles, to change.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("TAIL"))
    private void palmtunes$overrideCamera(BlockGetter level, Entity entity, boolean detached, boolean mirrored,
                                          float partialTick, CallbackInfo ci) {
        if (!detached) {
            return;
        }
        PalmCameraController.Result result = PalmCameraController.compute((Camera) (Object) this, entity, partialTick);
        if (result == null) {
            result = Cinematic.compute((Camera) (Object) this, entity, partialTick);
        }
        if (result != null) {
            this.setRotation(result.yRot(), result.xRot());
            this.setPosition(result.position());
        }
    }
}
