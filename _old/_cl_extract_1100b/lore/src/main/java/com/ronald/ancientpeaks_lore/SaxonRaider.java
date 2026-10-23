package com.ronald.ancientpeaks_lore;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.damagesource.DamageSource;

/**
 * A Saxon raider: one of Vortigern's hired warbands, burning the road chapels and robbing the supply carts. A man in
 * mail (chainmail and odd pieces of iron) with an axe or sword; they hunt in small groups and fight like soldiers -
 * they don't burn in the sun, they take cover in no one's house and they come back for more. They besiege Merlin's
 * chapel, camp by the raided carts and roam the islands by night.
 */
public class SaxonRaider extends Monster {
    public SaxonRaider(EntityType<? extends SaxonRaider> type, Level level) { super(type, level); xpReward = 8; }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 26).add(Attributes.ATTACK_DAMAGE, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.285).add(Attributes.FOLLOW_RANGE, 32).add(Attributes.ARMOR, 2);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1, false));
        goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 1.0));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.9));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 10));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, SaxonRaider.class).setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, false));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance diff, MobSpawnType type, SpawnGroupData data, CompoundTag tag) {
        SpawnGroupData d = super.finalizeSpawn(level, diff, type, data, tag);
        kit(getRandom());
        return d;
    }

    /** Mail and a weapon: chainmail mostly, a piece or two of iron, never a full set; an axe, a sword or a mace-like shovel. */
    void kit(RandomSource r) {
        Item[][] pieces = {{Items.CHAINMAIL_HELMET, Items.IRON_HELMET}, {Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE},
                {Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS}, {Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS}};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            if (r.nextFloat() < (i == 1 ? 0.95f : 0.6f)) setItemSlot(slots[i], new ItemStack(pieces[i][r.nextFloat() < 0.3f ? 1 : 0]));
            setDropChance(slots[i], 0.04f);
        }
        float w = r.nextFloat();
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(w < 0.4f ? Items.IRON_AXE : w < 0.75f ? Items.IRON_SWORD : w < 0.9f ? Items.STONE_AXE : Items.STONE_SWORD));
        if (r.nextFloat() < 0.25f) setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        setDropChance(EquipmentSlot.MAINHAND, 0.06f); setDropChance(EquipmentSlot.OFFHAND, 0.05f);
    }

    @Override protected SoundEvent getAmbientSound() { return SoundEvents.VINDICATOR_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource s) { return SoundEvents.PLAYER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.VINDICATOR_DEATH; }
    @Override public float getVoicePitch() { return 0.75f + random.nextFloat() * 0.1f; }
}
