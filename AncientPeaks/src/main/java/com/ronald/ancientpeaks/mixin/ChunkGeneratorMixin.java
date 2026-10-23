package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.world.GorgeDecorFeature;
import com.ronald.ancientpeaks.world.GorgeRiverFeature;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Island water and gorge decorations run after every biome feature, outside the feature system. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void ancientpeaks$islandWater(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, CallbackInfo ci) {
        var cp = chunk.getPos();
        RandomSource r = new WorldgenRandom(new XoroshiroRandomSource(level.getSeed() ^ cp.toLong() * 0x9E3779B97F4A7C15L));
        GorgeDecorFeature.run(level, cp, r);
        GorgeRiverFeature.run(level, cp, r);
        try { com.ronald.ancientpeaks.world.StreamDecor.run(level, cp); }
        catch (RuntimeException e) { com.ronald.ancientpeaks.AncientPeaks.LOGGER.error("Stream dressing failed in {}", cp, e); }
        try { com.ronald.ancientpeaks.world.StreamEnds.run(level, cp); }
        catch (RuntimeException e) { com.ronald.ancientpeaks.AncientPeaks.LOGGER.error("Stream ends failed in {}", cp, e); }
        try { com.ronald.ancientpeaks.world.PlainsBloom.run(level, cp); }
        catch (RuntimeException e) { com.ronald.ancientpeaks.AncientPeaks.LOGGER.error("Plains bloom failed in {}", cp, e); }
        for (var hook : com.ronald.ancientpeaks.AncientPeaks.DECORATION_HOOKS) {
            try { hook.accept(level, cp); }
            catch (RuntimeException e) { com.ronald.ancientpeaks.AncientPeaks.LOGGER.error("Decoration hook failed in {}", cp, e); }
        }
    }
}
