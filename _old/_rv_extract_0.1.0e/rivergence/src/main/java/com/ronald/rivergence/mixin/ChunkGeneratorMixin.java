package com.ronald.rivergence.mixin;

import com.ronald.rivergence.Rivergence;
import com.ronald.rivergence.world.WaterPlacer;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The water goes in before the chunk's plants and trees, so they grow round it rather than in it. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void rivergence$water(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, CallbackInfo ci) {
        try { WaterPlacer.run(level, chunk.getPos()); }
        catch (RuntimeException e) { Rivergence.LOGGER.error("Placing water failed in {}", chunk.getPos(), e); }
    }
}
