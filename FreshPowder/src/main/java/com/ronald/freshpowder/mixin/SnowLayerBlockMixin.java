package com.ronald.freshpowder.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Soft snow: you sink into it, three layers deeper than vanilla's one, so you wade instead of walking on top. */
@Mixin(SnowLayerBlock.class)
public abstract class SnowLayerBlockMixin {
    @Unique
    private static final VoxelShape[] FRESHPOWDER$SOFT = new VoxelShape[9];

    static {
        for (int n = 0; n <= 8; n++) {
            int solid = n >= 8 ? 8 : Math.max(0, n - 3);
            FRESHPOWDER$SOFT[n] = solid == 0 ? Shapes.empty() : Block.box(0, 0, 0, 16, solid * 2, 16);
        }
    }

    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void freshpowder$soft(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx, CallbackInfoReturnable<VoxelShape> cir) {
        cir.setReturnValue(FRESHPOWDER$SOFT[state.getValue(SnowLayerBlock.LAYERS)]);
    }
}
