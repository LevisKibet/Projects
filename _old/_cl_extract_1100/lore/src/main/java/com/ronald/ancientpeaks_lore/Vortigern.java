package com.ronald.ancientpeaks_lore;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;

/**
 * Vortigern, the usurper, woken when Excalibur is drawn from the stone. An Epic Fight humanoid
 * (data/ancientpeaks_lore/epicfight_mobpatch/vortigern.json): his swordplay is Epic Fight's plain longsword moveset (no
 * Liechtenauer stance: with it he was invisible on the client).
 * He only appears once the waking cutscene is over (see VortigernStatue) - spawned while it played, he was never
 * seen by the player's game. Around that:
 *   - he rises out of the summit (3 s, can't be hurt or act meanwhile)
 *   - at half health he roars, calls down lightning and his Saxon warriors (2 s, can't be hurt)
 *   - from then on fangs of dark sorcery tear out of the ground toward you, and more warriors come every 30 s
 *   - anything but Excalibur does half damage to him; he wears the crown and drops it when he falls
 */
public class Vortigern extends Monster {
    static final String MINION = "ancientpeaks_lore_vortigern_minion";
    static final ResourceLocation LONGSWORD = new ResourceLocation("epicfight", "netherite_longsword");

    private final ServerBossEvent bar = new ServerBossEvent(Component.translatable("bossbar.ancientpeaks_lore.vortigern"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
    private int intro = 60, introLength = 60, roar, fangCd = 60, summonCd;
    private java.util.UUID foe;
    /** The sword-in-the-stone he woke beside: whole again once he falls, for the next player's quest. */
    private BlockPos stone;
    private boolean phase2;
    /** 0 = fighting normally, 1 = winding up a heavy blow (blade raised), 2 = the blow landing. Drawn by the client model. */
    static final EntityDataAccessor<Integer> POSE = SynchedEntityData.defineId(Vortigern.class, EntityDataSerializers.INT);
    /** In his second phase (his eyes burn): seen by the client too. */
    static final EntityDataAccessor<Boolean> ENRAGED = SynchedEntityData.defineId(Vortigern.class, EntityDataSerializers.BOOLEAN);
    static final int LUNGE = 1, SWEEP = 2;
    private int special, castT, landT, specialCd = 60, poseT;
    public int heavyPose() { return entityData.get(POSE); }
    @Override protected void defineSynchedData() { super.defineSynchedData(); entityData.define(POSE, 0); entityData.define(ENRAGED, false); }

    public Vortigern(EntityType<? extends Vortigern> type, Level level) {
        super(type, level);
        xpReward = 500;
        setPersistenceRequired();
        setMaxUpStep(1.0f);
        noCulling = true;                                               // drawn whenever he's in range, whatever the view frustum thinks
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 400).add(Attributes.ARMOR, 12).add(Attributes.ARMOR_TOUGHNESS, 6)
                .add(Attributes.ATTACK_DAMAGE, 1.0).add(Attributes.ATTACK_KNOCKBACK, 0.8).add(Attributes.MOVEMENT_SPEED, 0.2185)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.85).add(Attributes.FOLLOW_RANGE, 48);
    }

    /**
     * He stands where his statue stood, still and silent for the rest of the cutscene (frozenTicks), then fights
     * whoever drew the sword.
     */
    public static Vortigern awaken(ServerLevel level, Vec3 at, float yaw, Player foe, int frozenTicks) {
        Vortigern v = LoreContent.VORTIGERN.get().create(level);
        if (v == null) return null;
        v.moveTo(at.x, at.y, at.z, yaw, 0);
        v.setYHeadRot(yaw); v.setYBodyRot(yaw);
        v.finalizeSpawn(level, level.getCurrentDifficultyAt(v.blockPosition()), MobSpawnType.EVENT, null, null);
        v.intro = v.introLength = Math.max(1, frozenTicks);
        v.foe = foe == null ? null : foe.getUUID();
        BlockPos c = BlockPos.containing(at);
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-8, -4, -8), c.offset(8, 6, 8)))
            if (level.getBlockState(q).is(LoreContent.SWORD_IN_STONE.get())) { v.stone = q.immutable(); break; }
        level.addFreshEntity(v);
        return v;
    }

    /** For a stone with no statue beside it (an older world): he rises out of the ground near it, with his cutscene. */
    public static void rise(ServerLevel level, BlockPos stone, Player by) {
        if (!level.getEntitiesOfClass(Vortigern.class, new AABB(stone).inflate(96)).isEmpty()) return;
        if (!level.getEntitiesOfClass(VortigernStatue.class, new AABB(stone).inflate(16)).isEmpty()) return;
        Vec3 away = by == null ? new Vec3(1, 0, 0) : new Vec3(stone.getX() + 0.5 - by.getX(), 0, stone.getZ() + 0.5 - by.getZ());
        if (away.lengthSqr() < 1e-3) away = new Vec3(1, 0, 0);
        away = away.normalize();
        int x = Mth.floor(stone.getX() + 0.5 + away.x * 3), z = Mth.floor(stone.getZ() + 0.5 + away.z * 3);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Vec3 at = new Vec3(x + 0.5, y, z + 0.5);
        // the same way as at a summit with its statue: the stone figure first, the cutscene, then him
        VortigernStatue st = LoreContent.VORTIGERN_STATUE.get().create(level);
        if (st == null) return;
        float yaw = VortigernStatue.facing(at, Vec3.atCenterOf(stone));
        st.moveTo(at.x, at.y, at.z, yaw, 0); st.setYHeadRot(yaw); st.setYBodyRot(yaw);
        level.addFreshEntity(st);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE.defaultBlockState()), at.x, at.y + 0.2, at.z, 120, 1.2, 0.3, 1.2, 0.2);
        st.awaken(by);
    }

    /** The longsword (Epic Fight's, if it's there) and the crown. */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance diff, MobSpawnType type, SpawnGroupData data, CompoundTag tag) {
        SpawnGroupData d = super.finalizeSpawn(level, diff, type, data, tag);
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(LoreContent.VORTIGERN_SWORD.get()));
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(LoreContent.CROWN.get()));
        setDropChance(EquipmentSlot.MAINHAND, 0);
        setDropChance(EquipmentSlot.HEAD, 0);                        // the crown is dropped on purpose when he falls
        return d;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        // Epic Fight adds his longsword attack and chase goals from his mob patch (epicfight_mobpatch/vortigern.json)
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 16));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, Vortigern.class));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, false));
    }

    @Override public boolean removeWhenFarAway(double d) { return false; }
    /** Never hidden: no potion, effect or other mod's trick makes him invisible. */
    @Override public boolean isInvisible() { return false; }
    @Override public boolean isInvisibleTo(Player p) { return false; }
    @Override public boolean shouldRender(double x, double y, double z) { return true; }

    /** He takes no target until his scene is over. */
    @Override
    public void setTarget(LivingEntity t) { super.setTarget(intro > 0 ? null : t); }
    @Override public boolean requiresCustomPersistence() { return true; }
    @Override public boolean canChangeDimensions() { return false; }
    @Override protected boolean shouldDespawnInPeaceful() { return false; }
    @Override public boolean causeFallDamage(float d, float m, DamageSource s) { return false; }

    /** Plays one of Epic Fight's animations on him (does nothing if he has no Epic Fight patch). */
    private void playEpicFight(yesman.epicfight.api.asset.AssetAccessor<? extends yesman.epicfight.api.animation.types.StaticAnimation> anim) {
        try {
            var patch = yesman.epicfight.world.capabilities.EpicFightCapabilities.getEntityPatch(this, yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch.class);
            if (patch != null && anim != null) patch.playAnimationSynchronized(anim, 0.0f);
        } catch (RuntimeException e) {
            AncientPeaksLore.LOGGER.debug("Epic Fight animation failed on Vortigern", e);
        }
    }

    // ------------------------------------------------------------------ taking hits
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurt(source, amount);
        if (intro > 0 || roar > 0) return false;
        Entity by = source.getEntity();
        if (by == this || (by != null && by.getTags().contains(MINION))) return false;
        if (!(by instanceof LivingEntity le && le.getMainHandItem().is(LoreContent.EXCALIBUR.get()))) amount *= 0.5f;
        boolean hit = super.hurt(source, amount);
        if (hit && !level().isClientSide && !phase2 && getHealth() <= getMaxHealth() / 2) phaseTwo((ServerLevel) level());
        return hit;
    }

    /** Past half health: his blows wither. */
    public boolean enraged() { return level().isClientSide ? entityData.get(ENRAGED) : phase2; }

    private void phaseTwo(ServerLevel sl) {
        bar.setColor(BossEvent.BossBarColor.PURPLE);
        phase2 = true; roar = 40; summonCd = 0; fangCd = 80;
        entityData.set(ENRAGED, true);
        getNavigation().stop();
        playEpicFight(yesman.epicfight.gameasset.Animations.WRATHFUL_LIGHTING);
        net.minecraft.world.entity.LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(sl);
        if (bolt != null) { bolt.moveTo(getX(), getY(), getZ()); bolt.setVisualOnly(true); sl.addFreshEntity(bolt); }
        sl.playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3.0f, 0.6f);
        Quest.say(sl, position(), 64, "Vortigern", ChatFormatting.DARK_RED, LoreScript.VORTIGERN_PHASE_TWO);
        shove(sl, 8, 1.4);
    }

    // ------------------------------------------------------------------ his rise, his roar, his sorcery
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        ServerLevel sl = (ServerLevel) level();
        bar.setProgress(getHealth() / getMaxHealth());
        if (intro > 0) {
            // still and silent for the rest of his scene: no target, no steps (his AI stays on so he keeps drawing
            // and animating him normally)
            if (intro == introLength) bar.setVisible(false);
            getNavigation().stop();
            super.setTarget(null);
            setDeltaMovement(0, getDeltaMovement().y, 0);
            if (introLength - intro < 40 && intro % 4 == 0)
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), getX(), getY() + 1, getZ(), 12, 0.5, 0.9, 0.5, 0.1);
            if (--intro == 0) {
                bar.setVisible(true);
                LoreNet.endCutscene(sl, position(), 64);
                Player f = foe == null ? null : sl.getPlayerByUUID(foe);
                if (f != null && !f.isCreative()) setTarget(f);
                sl.playSound(null, blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2.0f, 0.5f);
            }
            return;
        }
        setAggressive(getTarget() != null && getTarget().isAlive());
        if (poseT > 0 && --poseT == 0 && special == 0) entityData.set(POSE, 0);
        if (roar > 0) {
            roar--;
            if (roar == 20) summon(sl, getTarget());
            return;
        }
        if (isDeadOrDying()) return;
        if (!phase2) return;
        LivingEntity t = getTarget();
        if (fangCd > 0) fangCd--;
        if (summonCd > 0) summonCd--;
        if (t == null || !t.isAlive()) return;
        double d = distanceTo(t);
        if (summonCd <= 0) { summon(sl, t); summonCd = 20 * 30; }
        else if (fangCd <= 0 && d > 5 && d < 20) { fangs(sl, t); fangCd = 20 * 9; }
    }

    /**
     * His heavy blows, between the ordinary sword cuts of his melee attack:
     *   the leap - from a few blocks off he raises the blade, leaps at you and smashes down where you stood (hurts
     *   everyone round the landing and throws them back);
     *   the sweep - up close he winds up and swings the blade all the way round him.
     * Each is telegraphed by his raised sword for half a second or more. Faster once he is wounded.
     */
    private void heavyBlows(ServerLevel sl) {
        LivingEntity t = getTarget();
        if (special == 0) {
            if (specialCd > 0) specialCd--;
            if (t == null || !t.isAlive() || specialCd > 0) return;
            double d = distanceTo(t);
            if (d > 4.5 && d < 13 && onGround()) { special = LUNGE; castT = phase2 ? 10 : 14; }
            else if (d <= 4.0 && random.nextFloat() < 0.6f) { special = SWEEP; castT = phase2 ? 9 : 13; }
            else { specialCd = 20; return; }
            entityData.set(POSE, 1);
            sl.playSound(null, blockPosition(), SoundEvents.ARMOR_EQUIP_NETHERITE, SoundSource.HOSTILE, 1.6f, 0.6f);
            return;
        }
        getNavigation().stop();
        if (t != null) getLookControl().setLookAt(t, 60, 60);
        if (castT > 0) {
            castT--;
            if (t != null) { setYRot((float) (Mth.atan2(t.getZ() - getZ(), t.getX() - getX()) * Mth.RAD_TO_DEG) - 90f); setYBodyRot(getYRot()); }
            if (castT == 0) {
                if (special == LUNGE && t != null) {
                    Vec3 to = t.position().subtract(position()).multiply(1, 0, 1);
                    double len = Math.max(0.1, to.length());
                    Vec3 v = to.scale(Math.min(1.6, len * 0.16) / len);
                    setDeltaMovement(v.x, 0.62, v.z); hasImpulse = true;
                    landT = 40;
                    sl.playSound(null, blockPosition(), SoundEvents.RAVAGER_ATTACK, SoundSource.HOSTILE, 1.6f, 0.7f);
                } else {
                    blowRing(sl, 3.8, 9.0f, 1.1, ParticleTypes.SWEEP_ATTACK, SoundEvents.PLAYER_ATTACK_SWEEP);
                    finishSpecial();
                }
            }
            return;
        }
        if (special == LUNGE) {
            landT--;
            if ((landT < 36 && onGround()) || landT <= 0) {
                blowRing(sl, 3.2, 11.0f, 1.4, ParticleTypes.EXPLOSION, SoundEvents.GENERIC_EXPLODE);
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, getBlockStateOn()), getX(), getY() + 0.1, getZ(), 60, 1.5, 0.1, 1.5, 0.2);
                finishSpecial();
            }
        }
    }

    private void finishSpecial() {
        special = 0; specialCd = phase2 ? 60 : 100;
        entityData.set(POSE, 2); poseT = 8;
        swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
    }

    private void blowRing(ServerLevel sl, double r, float dmg, double knock, net.minecraft.core.particles.ParticleOptions fx, net.minecraft.sounds.SoundEvent snd) {
        sl.playSound(null, blockPosition(), snd, SoundSource.HOSTILE, 2.0f, 0.6f);
        sl.sendParticles(fx, getX(), getY() + 1, getZ(), fx == ParticleTypes.EXPLOSION ? 3 : 8, r * 0.4, 0.3, r * 0.4, 0.0);
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(r, 1.5, r), e -> e != this && !e.getTags().contains(MINION) && e.isAlive())) {
            if (e.distanceTo(this) > r + 0.5) continue;
            e.hurt(damageSources().mobAttack(this), dmg);
            Vec3 away = e.position().subtract(position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 1e-3) away = new Vec3(1, 0, 0);
            away = away.normalize().scale(knock);
            e.push(away.x, 0.35 * knock, away.z); e.hurtMarked = true;
        }
    }

    private void shove(ServerLevel sl, double r, double strength) {
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(r, 2, r), e -> e != this && !e.getTags().contains(MINION))) {
            Vec3 away = e.position().subtract(position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 1e-3) away = new Vec3(1, 0, 0);
            away = away.normalize().scale(strength);
            e.push(away.x, 0.55 * strength, away.z);
            e.hurtMarked = true;
        }
    }

    private void fangs(ServerLevel sl, LivingEntity t) {
        sl.playSound(null, blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 2.0f, 0.6f);
        sl.sendParticles(ParticleTypes.WITCH, getX(), getY() + 2, getZ(), 30, 0.5, 0.5, 0.5, 0.05);
        double a = Mth.atan2(t.getZ() - getZ(), t.getX() - getX());
        for (int i = 0; i < 14; i++) {
            double r = 1.5 + i * 1.3, x = getX() + Math.cos(a) * r, z = getZ() + Math.sin(a) * r;
            for (int y = Mth.floor(getY()) + 4; y >= Mth.floor(getY()) - 4; y--) {
                BlockPos p = BlockPos.containing(x, y, z);
                if (sl.getBlockState(p.below()).isFaceSturdy(sl, p.below(), Direction.UP) && sl.isEmptyBlock(p)) {
                    EvokerFangs f = new EvokerFangs(sl, x, y, z, (float) a, i, this);
                    sl.addFreshEntity(f);
                    break;
                }
            }
        }
    }

    private void summon(ServerLevel sl, LivingEntity t) {
        long alive = sl.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(48), m -> m.getTags().contains(MINION)).size();
        sl.playSound(null, blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 2.5f, 0.6f);
        for (int i = 0; i < 3 && alive + i < 6; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            int x = Mth.floor(getX() + Math.cos(a) * 4), z = Mth.floor(getZ() + Math.sin(a) * 4);
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            SaxonShade w = LoreContent.SAXON_SHADE.get().create(sl);
            if (w == null) continue;
            w.moveTo(x + 0.5, y + 1.5, z + 0.5, random.nextFloat() * 360, 0);
            w.finalizeSpawn(sl, sl.getCurrentDifficultyAt(w.blockPosition()), MobSpawnType.MOB_SUMMONED, null, null);
            w.setOwner(this);
            w.setBoundOrigin(blockPosition());
            w.setLimitedLife(20 * (25 + random.nextInt(15)));
            w.setCustomName(Component.literal("Shade of the Saxon Host").withStyle(ChatFormatting.DARK_RED));
            w.addTag(MINION);
            if (t != null) w.setTarget(t);
            sl.addFreshEntity(w);
            sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x + 0.5, y + 1.5, z + 0.5, 25, 0.3, 0.5, 0.3, 0.03);
            sl.sendParticles(ParticleTypes.LARGE_SMOKE, x + 0.5, y + 1, z + 0.5, 20, 0.3, 0.6, 0.3, 0.02);
        }
    }

    // ------------------------------------------------------------------ the end of him
    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (level().isClientSide) return;
        ServerLevel sl = (ServerLevel) level();
        bar.setProgress(0);
        // everyone who stood in the fight shares the "Usurper" advancement, not only whoever landed the last blow
        var usurper = sl.getServer().getAdvancements().getAdvancement(new net.minecraft.resources.ResourceLocation(AncientPeaksLore.MODID, "usurper"));
        if (usurper != null)
            for (var p : sl.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class, getBoundingBox().inflate(64)))
                if (!p.isSpectator()) p.getAdvancements().award(usurper, "slain");
        setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        if (stone != null) {                                          // the sword is back in the stone for whoever comes next
            var st = sl.getBlockState(stone);
            if (st.is(LoreContent.SWORD_IN_STONE.get()))                   // and the beam shines up from it again
                sl.setBlock(stone, st.setValue(SwordInStoneBlock.PULLED, false).setValue(SwordInStoneBlock.BEAM, true), 3);
        }
        Quest.say(sl, position(), 64, "Vortigern", ChatFormatting.DARK_RED, LoreScript.VORTIGERN_DEFEAT);
        for (Player p : sl.getEntitiesOfClass(Player.class, getBoundingBox().inflate(64))) {
            Quest.set(p, Quest.SLAIN, true);
            p.sendSystemMessage(Component.literal("The usurper has fallen.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }
        for (Mob m : sl.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(64), m -> m.getTags().contains(MINION))) {
            sl.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 1, m.getZ(), 10, 0.3, 0.5, 0.3, 0.02);
            m.discard();
        }
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
        net.minecraft.world.entity.item.ItemEntity it = spawnAtLocation(new ItemStack(LoreContent.CROWN.get()), 1.5f);
        if (it != null) { it.setGlowingTag(true); it.setUnlimitedLifetime(); }
        net.minecraft.world.entity.item.ItemEntity blade = spawnAtLocation(new ItemStack(LoreContent.VORTIGERN_SWORD.get()), 1.0f);
        if (blade != null) blade.setUnlimitedLifetime();
    }

    /** He takes 3 seconds to fall, in a haze of souls. */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (!level().isClientSide && deathTime % 5 == 0)
            ((ServerLevel) level()).sendParticles(ParticleTypes.SOUL, getX(), getY() + 1, getZ(), 8, 0.5, 0.8, 0.5, 0.02);
        if (deathTime >= 60 && !level().isClientSide) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ boss bar, saving
    @Override public void startSeenByPlayer(ServerPlayer p) { super.startSeenByPlayer(p); bar.addPlayer(p); }
    @Override public void stopSeenByPlayer(ServerPlayer p) { super.stopSeenByPlayer(p); bar.removePlayer(p); }

    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putBoolean("Phase2", phase2);
        t.putInt("Intro", intro);
        t.putInt("IntroLength", introLength);
        if (foe != null) t.putUUID("Foe", foe);
        if (stone != null) t.putLong("Stone", stone.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        phase2 = t.getBoolean("Phase2");
        entityData.set(ENRAGED, phase2);
        if (phase2) bar.setColor(BossEvent.BossBarColor.PURPLE);
        introLength = t.contains("IntroLength") ? t.getInt("IntroLength") : 60;
        if (t.hasUUID("Foe")) foe = t.getUUID("Foe");
        if (t.contains("Stone")) stone = BlockPos.of(t.getLong("Stone"));
        intro = t.contains("Intro") ? t.getInt("Intro") : 0;
        setNoAi(false);                                        // (older saves froze him with NoAI)
    }
}
