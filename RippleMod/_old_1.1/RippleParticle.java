package com.ripplemod.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A flat ring that lies on the water surface, expands and fades out. */
public class RippleParticle extends TextureSheetParticle {
    private static final float BASE_ALPHA = 0.7F;

    private final float startSize;
    private final float endSize;
    private int delay;

    protected RippleParticle(ClientLevel level, double x, double y, double z, float size, int delay, SpriteSet sprites) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        this.gravity = 0.0F;
        this.hasPhysics = false;
        this.lifetime = 26;
        this.delay = delay;
        this.startSize = 0.1F;
        this.endSize = 0.3F + size * 0.9F;
        this.quadSize = this.startSize;
        this.alpha = BASE_ALPHA;
        this.rCol = 0.9F;
        this.gCol = 0.97F;
        this.bCol = 1.0F;
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.delay > 0) {
            this.delay--;
            return;
        }
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        float t = (float) this.age / (float) this.lifetime;
        // Ease out so the ring expands quickly, then slows.
        float eased = 1.0F - (1.0F - t) * (1.0F - t);
        this.quadSize = Mth.lerp(eased, this.startSize, this.endSize);
        this.alpha = BASE_ALPHA * (1.0F - t) * (1.0F - t * 0.3F);
    }

    @Override
    public void render(VertexConsumer consumer, Camera camera, float partialTicks) {
        if (this.delay > 0) {
            return;
        }
        Vec3 cam = camera.getPosition();
        float px = (float) (Mth.lerp(partialTicks, this.xo, this.x) - cam.x());
        float py = (float) (Mth.lerp(partialTicks, this.yo, this.y) - cam.y());
        float pz = (float) (Mth.lerp(partialTicks, this.zo, this.z) - cam.z());
        float s = this.getQuadSize(partialTicks);

        float u0 = this.getU0();
        float u1 = this.getU1();
        float v0 = this.getV0();
        float v1 = this.getV1();
        int light = this.getLightColor(partialTicks);

        // Top face
        vertex(consumer, px - s, py, pz - s, u1, v1, light);
        vertex(consumer, px - s, py, pz + s, u1, v0, light);
        vertex(consumer, px + s, py, pz + s, u0, v0, light);
        vertex(consumer, px + s, py, pz - s, u0, v1, light);
        // Bottom face so it is visible from underwater too
        vertex(consumer, px + s, py, pz - s, u0, v1, light);
        vertex(consumer, px + s, py, pz + s, u0, v0, light);
        vertex(consumer, px - s, py, pz + s, u1, v0, light);
        vertex(consumer, px - s, py, pz - s, u1, v1, light);
    }

    private void vertex(VertexConsumer c, float x, float y, float z, float u, float v, int light) {
        c.vertex(x, y, z).uv(u, v).color(this.rCol, this.gCol, this.bCol, this.alpha).uv2(light).endVertex();
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double size, double delay, double unused) {
            return new RippleParticle(level, x, y, z, (float) size, (int) delay, this.sprites);
        }
    }
}
