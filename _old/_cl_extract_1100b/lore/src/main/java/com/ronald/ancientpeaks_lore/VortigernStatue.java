package com.ronald.ancientpeaks_lore;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Vortigern turned to stone beside the sword, reaching for it (the pose is in the renderer). He can't be hurt,
 * moved or pushed. When the sword is drawn he wakes: the "vortigern" cutscene plays while the stone cracks; as soon as
 * it is over the stone bursts and the real Vortigern stands in his place, roars, and fights.
 */
public class VortigernStatue extends Mob {
    static final int CRACK = 150, SCENE = 320;
    private int awaken = -1;
    private UUID drawer;

    public VortigernStatue(EntityType<? extends VortigernStatue> type, Level level) {
        super(type, level);
        setNoAi(true);
        setPersistenceRequired();
        setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 100).add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override public boolean hurt(DamageSource s, float a) { return s.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && super.hurt(s, a); }
    @Override public boolean isPushable() { return false; }
    @Override public void push(Entity e) { }
    @Override protected void doPush(Entity e) { }
    @Override public boolean removeWhenFarAway(double d) { return false; }
    @Override public boolean requiresCustomPersistence() { return true; }
    @Override public boolean canBeLeashed(Player p) { return false; }
    @Override public boolean isPickable() { return true; }

    public boolean awakening() { return awaken >= 0; }

    /** The sword is out: begin. */
    public void awaken(Player by) {
        if (awaken >= 0 || !(level() instanceof ServerLevel sl)) return;
        awaken = 0;
        drawer = by == null ? null : by.getUUID();
        LoreNet.playCutscene(sl, "vortigern", position(), getYRot(), 48);
        sl.playSound(null, blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 2.0f, 0.5f);
    }

    @Override
    public void tick() {
        super.tick();
        setYBodyRot(getYRot()); setYHeadRot(getYRot());
        if (level().isClientSide || awaken < 0) return;
        ServerLevel sl = (ServerLevel) level();
        awaken++;
        if (awaken > 20 && awaken < CRACK && awaken % 6 == 0) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                    getX(), getY() + 1.4, getZ(), 6 + awaken / 10, 0.4, 0.9, 0.4, 0.05);
            sl.playSound(null, blockPosition(), SoundEvents.STONE_HIT, SoundSource.HOSTILE, 1.0f + awaken / 100f, 0.5f + random.nextFloat() * 0.2f);
        }
        if (awaken == CRACK - 30) sl.playSound(null, blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 3.0f, 0.6f);
        if (awaken == CRACK) {                                       // the stone splits: he is waking inside it
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), getX(), getY() + 1.4, getZ(), 160, 0.6, 1.1, 0.6, 0.25);
            sl.playSound(null, blockPosition(), SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 3.0f, 0.5f);
        }
        if (awaken > CRACK && awaken % 4 == 0)
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), getX(), getY() + 1.2, getZ(), 10, 0.4, 0.9, 0.4, 0.1);
        // Only once the cutscene is over (and the players' cameras are back on them) does the stone burst and the real
        // Vortigern step out: spawned while the scene was playing, the player's game never showed him.
        if (awaken == SCENE + 20) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), getX(), getY() + 1.4, getZ(), 260, 0.7, 1.2, 0.7, 0.3);
            sl.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 1.2, getZ(), 2, 0.3, 0.5, 0.3, 0);
            sl.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0f, 0.6f);
            sl.playSound(null, blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2.0f, 0.8f);
            Player p = drawer == null ? null : sl.getPlayerByUUID(drawer);
            var at = position(); float yaw = getYRot();
            discard();
            var server = sl.getServer();
            server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 2, () -> Vortigern.awaken(sl, at, yaw, p, 30)));
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        if (awaken >= 0 && drawer != null) t.putUUID("Drawer", drawer);
        t.putInt("Awaken", awaken);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        awaken = t.contains("Awaken") ? t.getInt("Awaken") : -1;
        if (t.hasUUID("Drawer")) drawer = t.getUUID("Drawer");
        setNoAi(true);
    }

    /** Where he stands: just off the stone, facing it. */
    public static float facing(Vec3 from, Vec3 to) {
        return (float) (Math.atan2(to.z - from.z, to.x - from.x) * 180 / Math.PI) - 90f;
    }
}
