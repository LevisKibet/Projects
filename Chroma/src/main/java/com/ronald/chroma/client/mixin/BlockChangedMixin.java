package com.ronald.chroma.client.mixin;

import com.ronald.chroma.client.BlockChanges;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells BlockChanges about every block the client sees change. Always on (it isn't part of the lighting). */
@Mixin(LevelRenderer.class)
public abstract class BlockChangedMixin {
    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void chroma$noteChange(BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (oldState != newState) BlockChanges.changed(pos);
    }
}
