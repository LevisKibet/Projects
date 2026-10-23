package com.ronald.rivergence.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fire goes out under a passing shower as it does in the world's own rain (vanilla only looks for rain when the
 * whole world is raining, so a shower needs telling it).
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void rivergence$showerPutsOut(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (level.isRaining() || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) return;
        boolean wet = level.isRainingAt(pos);
        for (Direction d : Direction.Plane.HORIZONTAL) if (!wet && level.isRainingAt(pos.relative(d))) wet = true;
        if (!wet) return;
        int age = state.getValue(FireBlock.AGE);
        if (random.nextFloat() < 0.2f + age * 0.03f) {
            level.removeBlock(pos, false);
            ci.cancel();
        }
    }
}
