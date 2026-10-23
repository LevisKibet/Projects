package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carvers pick their caves and ravines from the biome at y=0, which under the island is still the deep ocean: its
 * ravines cut water-filled trenches with floating rock through the island. No carving inside the island.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseCarverMixin {
    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void ancientpeaks$noCarversOnIsland(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomes,
                                               StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        IslandSampler s = ((HighlandsAccess) (Object) randomState).ancientpeaks$sampler();
        if (s == null || !s.ready()) return;
        var cp = chunk.getPos();
        IslandSampler.Column c = new IslandSampler.Column();
        for (int[] o : new int[][]{{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}}) {
            int x = cp.getMinBlockX() + o[0], z = cp.getMinBlockZ() + o[1];
            if (s.islandAt(x, z) == null) continue;
            s.fill(c, x, z);
            if (c.m > 0.05) { ci.cancel(); return; }
        }
    }
}
