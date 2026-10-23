package com.ronald.rivergence.mixin;

import com.ronald.rivergence.world.CarveDensity;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;
import java.util.function.Predicate;

/** Height checks of single columns (structure placement, maps, /locate) never wait for a region's water to be planned. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "iterateNoiseColumn", at = @At("HEAD"))
    private void rivergence$peek(LevelHeightAccessor h, RandomState rs, int x, int z, MutableObject<NoiseColumn> col, Predicate<BlockState> stop, CallbackInfoReturnable<OptionalInt> cir) {
        CarveDensity.PEEK.set(true);
    }

    @Inject(method = "iterateNoiseColumn", at = @At("RETURN"))
    private void rivergence$done(LevelHeightAccessor h, RandomState rs, int x, int z, MutableObject<NoiseColumn> col, Predicate<BlockState> stop, CallbackInfoReturnable<OptionalInt> cir) {
        CarveDensity.PEEK.set(false);
    }
}
