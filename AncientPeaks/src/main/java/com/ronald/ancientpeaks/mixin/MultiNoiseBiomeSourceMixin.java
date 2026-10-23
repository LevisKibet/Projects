package com.ronald.ancientpeaks.mixin;

import com.ronald.ancientpeaks.AncientPeaks;
import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
    @org.spongepowered.asm.mixin.Unique private static final ThreadLocal<IslandSampler.Column[]> COLS = ThreadLocal.withInitial(() -> {
        IslandSampler.Column[] a = new IslandSampler.Column[16];
        for (int i = 0; i < 16; i++) a[i] = new IslandSampler.Column();
        return a;
    });
    @Inject(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;", at = @At("HEAD"), cancellable = true)
    private void ancientpeaks$islands(int qx, int qy, int qz, Climate.Sampler climate, CallbackInfoReturnable<Holder<Biome>> cir) {
        Holder<Biome>[] biomes = AncientPeaks.zoneBiomes;
        if (biomes == null || qy < 8) return;          // below y=32 keep the ocean's own biomes
        IslandSampler s = ((HighlandsAccess) (Object) climate).ancientpeaks$sampler();
        if (s == null) return;
        int x = qx << 2, z = qz << 2;
        if (s.islandAt(x, z) == null) return;
        IslandSampler.Column[] cache = COLS.get();
        IslandSampler.Column c = cache[((qx & 3) << 2) | (qz & 3)];
        if (c.x != x || c.z != z || c.is == null) s.fill(c, x, z);
        IslandSampler.Zone zone = s.zone(c, qy << 2);
        Holder<Biome> b = biomes[zone.ordinal()];
        if (b != null) cir.setReturnValue(b);
    }
}
