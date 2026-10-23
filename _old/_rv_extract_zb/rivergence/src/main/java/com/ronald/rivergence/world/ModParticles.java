package com.ronald.rivergence.world;

import com.ronald.rivergence.Rivergence;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Mist drifting over still water, spray at the foot of falls. */
public final class ModParticles {
    private ModParticles() {}
    public static final DeferredRegister<ParticleType<?>> REG = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, Rivergence.MODID);
    public static final RegistryObject<SimpleParticleType> MIST = REG.register("mist", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> SPRAY = REG.register("spray", () -> new SimpleParticleType(false));
}
