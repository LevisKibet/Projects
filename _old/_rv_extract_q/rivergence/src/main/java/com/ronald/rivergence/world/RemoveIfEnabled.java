package com.ronald.rivergence.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ronald.rivergence.Rivergence;
import com.ronald.rivergence.RivergenceConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/** Takes vanilla features out of biomes (its water springs), while the matching setting is on. */
public record RemoveIfEnabled(HolderSet<Biome> biomes, HolderSet<PlacedFeature> features, List<GenerationStep.Decoration> steps) implements BiomeModifier {
    public static final Codec<RemoveIfEnabled> CODEC = RecordCodecBuilder.create(i -> i.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(RemoveIfEnabled::biomes),
            PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(RemoveIfEnabled::features),
            GenerationStep.Decoration.CODEC.listOf().fieldOf("steps").forGetter(RemoveIfEnabled::steps)
    ).apply(i, RemoveIfEnabled::new));

    private static final DeferredRegister<Codec<? extends BiomeModifier>> SERIALIZERS = DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, Rivergence.MODID);
    static { SERIALIZERS.register("remove_if_enabled", () -> CODEC); }

    public static void register(IEventBus bus) { SERIALIZERS.register(bus); }

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.REMOVE || !biomes.contains(biome) || !RivergenceConfig.removeVanillaSprings()) return;
        for (GenerationStep.Decoration step : steps)
            builder.getGenerationSettings().getFeatures(step).removeIf(features::contains);
    }

    @Override public Codec<? extends BiomeModifier> codec() { return CODEC; }
}
