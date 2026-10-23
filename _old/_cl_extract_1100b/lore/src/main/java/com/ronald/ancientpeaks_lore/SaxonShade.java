package com.ronald.ancientpeaks_lore;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.level.Level;

/**
 * Vortigern's summons in his second phase: the shades of his Saxon host. A vex underneath (it flies through walls
 * and fades after a while), drawn as a smoke-black, ember-lit spirit trailing soul fire.
 */
public class SaxonShade extends Vex {
    public SaxonShade(EntityType<? extends Vex> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) setDeltaMovement(getDeltaMovement().scale(0.95));   // a little slower than a vex
        if (level().isClientSide && random.nextInt(3) == 0)
            level().addParticle(random.nextBoolean() ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.SMOKE,
                    getRandomX(0.4), getY() + 0.3 + random.nextDouble() * 0.4, getRandomZ(0.4), 0, 0.01, 0);
    }
}
