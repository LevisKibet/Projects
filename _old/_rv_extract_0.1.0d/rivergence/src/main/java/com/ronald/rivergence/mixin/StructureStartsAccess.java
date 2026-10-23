package com.ronald.rivergence.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the stretch where a chunk picks its structures (as opposed to /locate or maps asking where they'd be). */
@Mixin(ChunkGenerator.class)
public abstract class StructureStartsAccess {
    @Inject(method = "createStructures", at = @At("HEAD"))
    private void rivergence$begin(RegistryAccess ra, ChunkGeneratorStructureState st, StructureManager sm, ChunkAccess chunk, StructureTemplateManager tm, CallbackInfo ci) { com.ronald.rivergence.world.CarveDensity.STARTING.set(true); }

    @Inject(method = "createStructures", at = @At("RETURN"))
    private void rivergence$end(RegistryAccess ra, ChunkGeneratorStructureState st, StructureManager sm, ChunkAccess chunk, StructureTemplateManager tm, CallbackInfo ci) { com.ronald.rivergence.world.CarveDensity.STARTING.set(false); }
}
