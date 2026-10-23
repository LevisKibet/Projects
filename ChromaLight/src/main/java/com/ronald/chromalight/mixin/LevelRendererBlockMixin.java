package com.ronald.chromalight.mixin;

import com.ronald.chromalight.VoxelLight;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A light placed or broken, a wall opened or a pane of stained glass set: the colour volume is redone right away. */
@Mixin(LevelRenderer.class)
public class LevelRendererBlockMixin {
    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void chromalight$changed(BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (oldState == newState) return;
        if (oldState.getLightEmission() != newState.getLightEmission()
                || oldState.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) != newState.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                || oldState.getBlock() instanceof BeaconBeamBlock || newState.getBlock() instanceof BeaconBeamBlock
                || oldState.getBlock() != newState.getBlock() && newState.getLightEmission() > 0)
            VoxelLight.markDirty();
    }
}
