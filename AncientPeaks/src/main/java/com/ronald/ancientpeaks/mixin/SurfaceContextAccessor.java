package com.ronald.ancientpeaks.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public interface SurfaceContextAccessor {
    @Accessor("chunk") ChunkAccess ancientpeaks$chunk();
    @Accessor("blockX") int ancientpeaks$blockX();
    @Accessor("blockZ") int ancientpeaks$blockZ();
}
