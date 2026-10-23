package com.ronald.rivergence.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;

/** A soft, slow drift of mist (or a quick puff of spray), fading in and out. */
public class MistParticle extends TextureSheetParticle {
    private final float peak;
    private final boolean spray;

    MistParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites, boolean spray) {
        super(level, x, y, z, vx, vy, vz);
        this.spray = spray;
        pickSprite(sprites);
        xd = vx; yd = vy; zd = vz;
        hasPhysics = false;
        if (spray) {
            lifetime = 14 + random.nextInt(16);
            quadSize = 0.12f + random.nextFloat() * 0.18f;
            gravity = 0.04f;
            peak = 0.55f;
        } else {
            lifetime = 50 + random.nextInt(40);
            quadSize = 0.6f + random.nextFloat() * 0.6f;
            gravity = 0;
            peak = 0.32f + random.nextFloat() * 0.18f;
        }
        float g = 0.92f + random.nextFloat() * 0.08f;
        setColor(g, g, Math.min(1, g + 0.03f));
        alpha = 0;
    }

    @Override
    public void tick() {
        super.tick();
        float f = age / (float) lifetime;
        alpha = peak * Math.min(1, f * (spray ? 6 : 4)) * Math.min(1, (1 - f) * (spray ? 2 : 3));
        if (spray) { xd *= 0.92; zd *= 0.92; }
        else { xd *= 0.985; zd *= 0.985; yd *= 0.9; quadSize *= 1.006f; }
    }

    /** Lit like the sky round it, never darker than dusk: mist and spray stay pale, not grey. */
    @Override
    protected int getLightColor(float pt) {
        int l = super.getLightColor(pt);
        int sky = Math.max((l >> 20) & 15, 9), block = Math.max((l >> 4) & 15, 6);
        return sky << 20 | block << 4;
    }

    @Override public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

    public static class Mist implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public Mist(SpriteSet sprites) { this.sprites = sprites; }
        @Override public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z, double vx, double vy, double vz) {
            return new MistParticle(l, x, y, z, vx, vy, vz, sprites, false);
        }
    }

    public static class Spray implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public Spray(SpriteSet sprites) { this.sprites = sprites; }
        @Override public Particle createParticle(SimpleParticleType t, ClientLevel l, double x, double y, double z, double vx, double vy, double vz) {
            return new MistParticle(l, x, y, z, vx, vy, vz, sprites, true);
        }
    }
}
