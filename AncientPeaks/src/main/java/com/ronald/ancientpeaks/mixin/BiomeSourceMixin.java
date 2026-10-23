package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.AncientPeaks;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

/**
 * The island biomes must count as "possible" in the overworld's biome source, or Minecraft skips their features
 * (trees, grass, flowers) entirely. The list is cached very early - sometimes before the island biomes are known -
 * so the island biomes are added on every read instead, once they are.
 */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin {
    @Unique private Set<Holder<Biome>> ancientpeaks$base;
    @Unique private Set<Holder<Biome>> ancientpeaks$withIsland;

    @Inject(method = "possibleBiomes", at = @At("RETURN"), cancellable = true)
    private void ancientpeaks$withIsland(CallbackInfoReturnable<Set<Holder<Biome>>> cir) {
        if (!((Object) this instanceof MultiNoiseBiomeSource)) return;
        Holder<Biome>[] ours = AncientPeaks.zoneBiomes;
        if (ours == null) return;
        Set<Holder<Biome>> base = cir.getReturnValue();
        if (base != ancientpeaks$base) {
            boolean overworld = base.stream().anyMatch(h -> h.is(Biomes.DEEP_OCEAN));
            Set<Holder<Biome>> s = base;
            if (overworld) {
                s = new HashSet<>(base);
                for (Holder<Biome> h : ours) if (h != null) s.add(h);
            }
            ancientpeaks$withIsland = s;
            ancientpeaks$base = base;
        }
        cir.setReturnValue(ancientpeaks$withIsland);
    }
}
