package com.ronald.ancientpeaks_lore;

import com.ronald.ancientpeaks.world.HighlandsAccess;
import com.ronald.ancientpeaks.world.Island;
import com.ronald.ancientpeaks.world.IslandSampler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * Merlin, wounded in his besieged chapel near spawn.
 *   WOUNDED  hiding upstairs. A faint bell draws players near. Right-click him once the raiders in the house are
 *            dealt with: the "merlin_intro" cutscene plays (he thanks you and begs for a healing potion) and the
 *            siege begins.
 *   SIEGE    raiders come in waves and a bar shows him fading. Right-click him and he begs you to hurry; right-click
 *            him with the cure (a potion of healing or a golden apple - or throw a healing potion at him) and, with
 *            the last of his strength, he drives the raiders off. If the time runs out he slips away; the raiders go,
 *            and he can be roused again a minute later.
 *   HEALED   the "merlin" farewell cutscene plays (see LoreScript): he tells the tale of Arthur and Vortigern,
 *            blesses everyone near, gives the healer his map to the gate he fled the island through, and vanishes.
 *   RESTING  gone from sight (multiplayer: each player has their own quest). He waits unseen in his cabin, and when a
 *            player who hasn't had his blessing comes near, he is there again, wounded, for them to find and heal -
 *            and anyone who comes along can help. A player who already carries his blessing never brings him back.
 * He only ever plays one animation: "injured_leaning" (slumped against a chapel wall, facing the room).
 */
public class Merlin extends PathfinderMob implements GeoEntity {
    public static final int WOUNDED = 0, SIEGE = 1, HEALED = 2, RESTING = 3;
    private final java.util.Set<java.util.UUID> heardBell = new java.util.HashSet<>();
    static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(Merlin.class, EntityDataSerializers.INT);
    static final int SIEGE_TICKS = 20 * 180, WAVE_TICKS = 20 * 25, MAX_WAVES = 6;
    static final String BESIEGER = "ancientpeaks_lore_besieger";
    static final TagKey<Structure> GATEWAYS = TagKey.create(Registries.STRUCTURE, new ResourceLocation(AncientPeaksLore.MODID, "gateways"));

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bar = new ServerBossEvent(Component.literal("Merlin is fading"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
    private int timer, waveIn, waves, cooldown, talk = -1, intro = -1, nagCd;
    /** He has told someone his story (the first meeting): from then on he waits for the cure. */
    private boolean met;
    private java.util.UUID healer;

    public Merlin(EntityType<? extends Merlin> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        bar.setVisible(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 40).add(Attributes.MOVEMENT_SPEED, 0.22).add(Attributes.FOLLOW_RANGE, 16);
    }

    public int state() { return entityData.get(STATE); }
    public boolean healed() { return state() == HEALED; }
    void setState(int s) { entityData.set(STATE, s); }

    @Override protected void defineSynchedData() { super.defineSynchedData(); entityData.define(STATE, WOUNDED); }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    // ------------------------------------------------------------------ he can't be killed (only /kill)
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurt(source, amount);
        return false;
    }
    @Override public boolean removeWhenFarAway(double d) { return false; }
    @Override public boolean requiresCustomPersistence() { return true; }
    @Override public boolean isPushable() { return false; }
    @Override protected boolean shouldDespawnInPeaceful() { return false; }

    /** Instant health (a thrown or lingering potion of healing) wakes him too. */
    @Override
    public void heal(float amount) {
        super.heal(amount);
        if (amount > 0 && !level().isClientSide && (state() == SIEGE || (state() == WOUNDED && met)) && intro < 0) {
            Player p = level().getNearestPlayer(this, 24);
            onHealed(p);
        }
    }

    // ------------------------------------------------------------------ talking to him / healing him by hand
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (hand != InteractionHand.MAIN_HAND && !isCure(held)) return InteractionResult.PASS;
        ServerLevel sl = (ServerLevel) level();
        if (resting()) return InteractionResult.PASS;
        if (state() == HEALED || intro >= 0) return InteractionResult.CONSUME;          // he is already talking
        // 1. the first meeting: once the raiders in the house are dealt with
        if (!met) {
            if (raidersInside(sl)) {
                Quest.hint(player, "An old man lies slumped against the wall, barely breathing. Raiders are still in the house...");
                return InteractionResult.CONSUME;
            }
            met = true;
            intro = 0;
            LoreNet.playCutscene(sl, "merlin_intro", position(), getYRot(), 32);
            return InteractionResult.CONSUME;
        }
        // 4. the cure
        if (isCure(held)) {
            if (!player.getAbilities().instabuild) {
                if (held.is(Items.POTION)) player.setItemInHand(hand, new ItemStack(Items.GLASS_BOTTLE));
                else held.shrink(1);
            }
            onHealed(player);
            return InteractionResult.CONSUME;
        }
        // 2. without it: he begs you to hurry (and, if the raiders had gone, they come again)
        if (nagCd <= 0) {
            Quest.say(sl, position(), 32, "Merlin", ChatFormatting.LIGHT_PURPLE, LoreScript.MERLIN_SIEGE);
            nagCd = 60;
        }
        if (state() == WOUNDED) {
            if (cooldown > 0) Quest.hint(player, "He is gathering what little strength he has left...");
            else startSiege(sl, true);
        }
        return InteractionResult.CONSUME;
    }

    static boolean isCure(ItemStack held) {
        return held.is(Items.GOLDEN_APPLE) || held.is(Items.ENCHANTED_GOLDEN_APPLE)
                || (held.is(Items.POTION) && (PotionUtils.getPotion(held) == Potions.HEALING || PotionUtils.getPotion(held) == Potions.STRONG_HEALING));
    }

    /** Raiders still about the house (the ones who broke in, below him): he won't speak while they're near. */
    private boolean raidersInside(ServerLevel sl) {
        return !sl.getEntitiesOfClass(SaxonRaider.class, getBoundingBox().inflate(14, 8, 14), LivingEntity::isAlive).isEmpty();
    }

    /** Back to the wall, facing the room, on both sides (so the client never turns him). */
    @Override
    public void tick() {
        super.tick();
        setYBodyRot(getYRot()); setYHeadRot(getYRot()); yBodyRotO = getYRot(); yHeadRotO = getYRot();
    }

    // ------------------------------------------------------------------ the siege
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        ServerLevel sl = (ServerLevel) level();
        getNavigation().stop();
        setYBodyRot(getYRot()); setYHeadRot(getYRot());
        if (state() == HEALED && talk < 0) talk = 0;                    // reloaded mid-farewell: he starts again
        if (resting()) {
            if (tickCount % 20 == 0) {
                for (net.minecraft.server.level.ServerPlayer pl : sl.players())
                    if (!pl.isSpectator() && pl.distanceToSqr(this) <= 56 * 56 && !Quest.has(pl, Quest.BLESSED)) { reappear(sl); break; }
            }
            return;
        }
        if (cooldown > 0) cooldown--;

        // a faint bell from the chapel draws anyone passing: once a player hears it, a hint tells them to look for him
        if (state() == WOUNDED && tickCount % 20 == 0) {
            boolean near = false;
            for (net.minecraft.server.level.ServerPlayer pl : sl.players()) {
                if (pl.isSpectator() || pl.distanceToSqr(this) > 56 * 56) continue;
                near = true;
                if (heardBell.add(pl.getUUID())) {
                    pl.playNotifySound(SoundEvents.BELL_BLOCK, SoundSource.AMBIENT, 0.35f, 0.55f);
                    pl.sendSystemMessage(Component.literal("You hear a faint bell tolling somewhere nearby... someone may be in need. Search the chapel and help them.")
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
            }
            if (near && tickCount % 200 == 0) sl.playSound(null, blockPosition().above(6), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.5f, 0.55f);
        }
        if (nagCd > 0) nagCd--;
        // the first meeting's cutscene: when it ends the raiders come (he asked for the potion "before the rest of them breach the sanctuary")
        if (intro >= 0 && ++intro >= LoreScript.MERLIN_INTRO_LENGTH) {
            intro = -1;
            LoreNet.endCutscene(sl, position(), 40);
            startSiege(sl, true);
        }
        if (state() == SIEGE) {
            timer--;
            bar.setProgress(Mth.clamp(timer / (float) SIEGE_TICKS, 0, 1));
            if (tickCount % 20 == 0) updateBar(sl);
            if (--waveIn <= 0 && waves < MAX_WAVES) { spawnWave(sl); waves++; waveIn = WAVE_TICKS; }
            if (timer % 400 == 0 && timer > 0) sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 0.3, getZ(), 6, 0.4, 0.1, 0.4, 0.01);
            boolean anyone = sl.getNearestPlayer(this, 64) != null;
            if (timer <= 0 || !anyone) fail(sl, anyone);
        }
        if (talk >= 0) dialogue(sl);
    }

    private void startSiege(ServerLevel sl, boolean announce) {
        setState(SIEGE);
        timer = SIEGE_TICKS; waves = 0; waveIn = announce ? 100 : WAVE_TICKS;
        bar.setProgress(1); bar.setVisible(true); updateBar(sl);
        if (announce) sl.playSound(null, blockPosition(), SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 4.0f, 1.0f);
    }

    private void updateBar(ServerLevel sl) {
        for (ServerPlayer p : List.copyOf(bar.getPlayers())) if (p.distanceToSqr(this) > 48 * 48 || !p.isAlive()) bar.removePlayer(p);
        if (bar.isVisible()) for (ServerPlayer p : sl.players()) if (p.distanceToSqr(this) <= 48 * 48) bar.addPlayer(p);
    }

    /** Raiders close in from every side, more each wave, all after the nearest player. */
    private void spawnWave(ServerLevel sl) {
        Player target = sl.getNearestPlayer(this, 64);
        int n = 2 + waves;
        for (int i = 0; i < n; i++) {
            double a = random.nextDouble() * Math.PI * 2, r = 14 + random.nextDouble() * 8;
            int x = Mth.floor(getX() + Math.cos(a) * r), z = Mth.floor(getZ() + Math.sin(a) * r);
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            // Saxon raiders, with a crossbowman (a pillager) among them from the third wave
            EntityType<? extends Mob> type = waves >= 2 && i == 0 ? EntityType.PILLAGER : LoreContent.SAXON_RAIDER.get();
            Mob m = type.create(sl);
            if (m == null) continue;
            m.moveTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360, 0);
            m.finalizeSpawn(sl, sl.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.EVENT, null, null);
            m.addTag(BESIEGER);
            m.setPersistenceRequired();
            if (target != null) m.setTarget(target);
            sl.addFreshEntity(m);
            sl.sendParticles(ParticleTypes.LARGE_SMOKE, x + 0.5, y + 1, z + 0.5, 8, 0.3, 0.5, 0.3, 0.01);
        }
    }

    private void fail(ServerLevel sl, boolean anyoneThere) {
        setState(WOUNDED);
        bar.setVisible(false); bar.removeAllPlayers();
        cooldown = 20 * 60;
        if (anyoneThere) Quest.say(sl, position(), 48, "Merlin", ChatFormatting.LIGHT_PURPLE, LoreScript.MERLIN_FAIL);
        for (Mob m : besiegers(sl)) { sl.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 1, m.getZ(), 10, 0.3, 0.5, 0.3, 0.02); m.discard(); }
    }

    private List<Mob> besiegers(ServerLevel sl) {
        return sl.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(80), m -> m.getTags().contains(BESIEGER));
    }

    private void onHealed(Player by) {
        if (state() == HEALED) return;
        ServerLevel sl = (ServerLevel) level();
        setState(HEALED);
        setHealth(getMaxHealth());
        healer = by == null ? null : by.getUUID();
        bar.setVisible(false); bar.removeAllPlayers();
        sl.playSound(null, blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.NEUTRAL, 1.0f, 1.0f);
        sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 1, getZ(), 30, 0.6, 0.8, 0.6, 0.1);
        talk = 0;
    }

    /** What he says, lying where he fell, before he fades away. (A camera move can follow along here later.) */
    private void dialogue(ServerLevel sl) {
        talk++;
        Player p = healer == null ? null : sl.getPlayerByUUID(healer);
        if (p == null) p = sl.getNearestPlayer(this, 24);
        final int MAP = 10 + LoreScript.MERLIN_MAP_AT, GONE = 10 + LoreScript.MERLIN_FAREWELL_LENGTH - 60;
        if (talk == 1) {                                    // the last of his strength drives the raiders off
            sl.playSound(null, blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.NEUTRAL, 2.0f, 0.8f);
            for (Mob b : besiegers(sl)) {
                sl.sendParticles(ParticleTypes.ENCHANT, b.getX(), b.getY() + 1, b.getZ(), 40, 0.4, 0.8, 0.4, 1.0);
                sl.sendParticles(ParticleTypes.WITCH, b.getX(), b.getY() + 1, b.getZ(), 15, 0.3, 0.6, 0.3, 0.05);
                b.hurt(damageSources().indirectMagic(this, this), 1000);
            }
            for (SaxonRaider b : sl.getEntitiesOfClass(SaxonRaider.class, getBoundingBox().inflate(24), LivingEntity::isAlive)) {
                sl.sendParticles(ParticleTypes.ENCHANT, b.getX(), b.getY() + 1, b.getZ(), 40, 0.4, 0.8, 0.4, 1.0);
                b.hurt(damageSources().indirectMagic(this, this), 1000);
            }
        } else if (talk == 10) {
            LoreNet.playCutscene(sl, "merlin", position(), getYRot(), 32);
        } else if (talk == MAP) {                           // "Take my map..."
            // everyone who stood with him is blessed; each who wasn't before gets his map too (the healer always does)
            for (Player q : sl.getEntitiesOfClass(Player.class, getBoundingBox().inflate(32))) {
                boolean newly = !Quest.has(q, Quest.BLESSED);
                Quest.set(q, Quest.BLESSED, true);
                Quest.hint(q, "Merlin's blessing is upon you.");
                if (newly || q == p) giveMap(sl, q);
            }
            if (p != null && p.distanceToSqr(this) > 32 * 32) giveMap(sl, p);
        } else if (talk == GONE || talk == GONE + 100) {    // (never stays behind, whatever happened above)
            vanish(sl);
        } else if (talk > 10 && talk % 10 == 0) {
            sl.sendParticles(ParticleTypes.END_ROD, getX(), getY() + 1.0, getZ(), 2, 0.3, 0.5, 0.3, 0.01);
        }
    }

    /** He fades into light and is gone. */
    private void vanish(ServerLevel sl) {
        sl.sendParticles(ParticleTypes.END_ROD, getX(), getY() + 0.5, getZ(), 60, 0.5, 0.5, 0.5, 0.08);
        sl.sendParticles(ParticleTypes.ENCHANT, getX(), getY() + 0.5, getZ(), 120, 0.6, 0.8, 0.6, 1.2);
        sl.sendParticles(ParticleTypes.SOUL, getX(), getY() + 0.3, getZ(), 20, 0.5, 0.2, 0.5, 0.02);
        sl.playSound(null, blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.NEUTRAL, 2.0f, 0.7f);
        sl.playSound(null, blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.NEUTRAL, 1.5f, 1.2f);
        talk = -1;
        LoreNet.endCutscene(sl, position(), 48);
        rest();
    }

    /** Out of sight until someone who still needs him comes by. */
    private void rest() {
        setState(RESTING);
        met = false; healer = null; cooldown = 0; intro = -1;
        heardBell.clear();
        bar.setVisible(false); bar.removeAllPlayers();
        setInvisible(true);
    }

    /** Back where he lay, wounded again, for a player who hasn't had his blessing. */
    private void reappear(ServerLevel sl) {
        setInvisible(false);
        setState(WOUNDED);
        sl.sendParticles(ParticleTypes.ENCHANT, getX(), getY() + 0.6, getZ(), 40, 0.4, 0.6, 0.4, 0.6);
    }

    public boolean resting() { return state() == RESTING; }
    @Override public boolean isPickable() { return !resting() && super.isPickable(); }
    @Override public boolean shouldRender(double x, double y, double z) { return !resting() && super.shouldRender(x, y, z); }
    @Override public boolean isInvisible() { return resting() || super.isInvisible(); }

    // ------------------------------------------------------------------ his map
    /**
     * His map. With the Highland Gateways in the game it shows the nearest one - the portal he fled the island through -
     * marked with an X; without them (no Immersive Gateways, or switched off) it shows the way to the island itself,
     * with the X on its landing beach.
     */
    void giveMap(ServerLevel sl, Player p) {
        boolean gateways = com.ronald.ancientpeaks.Gateways.enabled();
        BlockPos target = null;
        if (gateways) {
            target = nearestGateway(sl, blockPosition());
            if (target == null) {                                // the slow, sure way (what /locate does)
                try {
                    var found = sl.findNearestMapStructure(GATEWAYS, blockPosition(), 100, false);
                    if (found != null) target = found;
                } catch (RuntimeException e) { AncientPeaksLore.LOGGER.warn("[ancientpeaks_lore] gateway search failed", e); }
            }
            if (target == null) AncientPeaksLore.LOGGER.warn("[ancientpeaks_lore] no Highland Gateway found anywhere near Merlin; his map points at the island instead");
        }
        boolean toGate = target != null;
        if (target == null) target = nearestIsland(sl);
        ItemStack map;
        if (target != null) {
            map = net.minecraft.world.item.MapItem.create(sl, target.getX(), target.getZ(), (byte) 2, true, true);
            net.minecraft.world.item.MapItem.renderBiomePreviewMap(sl, map);
            net.minecraft.world.level.saveddata.maps.MapItemSavedData.addTargetDecoration(map, target, "+",
                    net.minecraft.world.level.saveddata.maps.MapDecoration.Type.RED_X);
        } else {
            map = new ItemStack(Items.MAP);
        }
        map.setHoverName(Component.literal("Merlin's Map").withStyle(ChatFormatting.LIGHT_PURPLE).withStyle(s -> s.withItalic(false)));
        CompoundTag display = map.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(toGate ? "The gate I fled through. It leads across the water."
                : "Logres, across the water. Land on the beach where I marked it.").withStyle(ChatFormatting.GRAY))));
        display.put("Lore", lore);
        map.getOrCreateTag().putBoolean("ancientpeaks_lore_map", true);
        if (!p.addItem(map)) p.drop(map, false);
        // and his own book, for keeping the chronicles in
        if (!p.getInventory().hasAnyMatching(st -> st.is(LoreContent.COMPENDIUM.get()))) {
            ItemStack book = new ItemStack(LoreContent.COMPENDIUM.get());
            if (!p.addItem(book)) p.drop(book, false);
        }
    }

    /**
     * Where the nearest Highland Gateway really stands. Candidates come straight from the gateways' placement grid (one
     * chunk per 44x44-chunk cell), nearest first; each is confirmed by working out that chunk's structure starts (cheap:
     * no terrain is built), so the X always marks a portal that is actually there.
     */
    static BlockPos nearestGateway(ServerLevel sl, BlockPos near) {
        try {
            var set = sl.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).get(new ResourceLocation("ancientpeaks", "highland_gateways"));
            if (set == null || set.structures().isEmpty()
                    || !(set.placement() instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement rsp)) return null;
            Structure st = set.structures().get(0).structure().value();
            int spacing = rsp.spacing(), cx = Math.floorDiv(near.getX() >> 4, spacing), cz = Math.floorDiv(near.getZ() >> 4, spacing);
            java.util.List<net.minecraft.world.level.ChunkPos> cand = new java.util.ArrayList<>();
            for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++)
                cand.add(rsp.getPotentialStructureChunk(sl.getSeed(), cx + dx, cz + dz));
            cand.sort(java.util.Comparator.comparingDouble(c -> Math.hypot(c.getMiddleBlockX() - near.getX(), c.getMiddleBlockZ() - near.getZ())));
            int tried = 0;
            for (var cp : cand) {
                if (tried++ >= 80) break;
                var chunk = sl.getChunk(cp.x, cp.z, net.minecraft.world.level.chunk.ChunkStatus.STRUCTURE_STARTS);
                var start = chunk.getStartForStructure(st);
                if (start == null || !start.isValid()) continue;
                var box = start.getBoundingBox();
                return new BlockPos((box.minX() + box.maxX()) / 2, box.minY(), (box.minZ() + box.maxZ()) / 2);
            }
            return null;
        } catch (RuntimeException e) {
            AncientPeaksLore.LOGGER.warn("[ancientpeaks_lore] couldn't work out where the gateways are", e);
            return null;
        }
    }

    /** The island's landing beach (where arrivals by sea come ashore), or its centre. */
    private BlockPos nearestIsland(ServerLevel sl) {
        IslandSampler s = ((HighlandsAccess) (Object) sl.getChunkSource().randomState()).ancientpeaks$sampler();
        Island is = s == null ? null : s.island();
        if (is == null) return null;
        try {
            java.util.List<int[]> l = s.findLandings(is, 1);
            if (!l.isEmpty()) return new BlockPos(l.get(0)[0], l.get(0)[1], l.get(0)[2]);
        } catch (RuntimeException ignored) { }
        return new BlockPos(is.cx, 64, is.cz);
    }

    // ------------------------------------------------------------------ saving
    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putInt("QuestState", state() == SIEGE ? WOUNDED : state());       // a siege doesn't survive a reload
        if (healer != null) t.putUUID("Healer", healer);
        t.putBoolean("Met", met);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        setState(t.getInt("QuestState"));
        if (t.hasUUID("Healer")) healer = t.getUUID("Healer");
        met = t.getBoolean("Met");
        if (state() == RESTING) setInvisible(true);
    }

    @Override
    public void remove(RemovalReason reason) {
        bar.removeAllPlayers();
        super.remove(reason);
    }

    // ------------------------------------------------------------------ animation: only ever lying wounded
    static final RawAnimation LYING = RawAnimation.begin().thenLoop("injured_leaning");

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar c) {
        c.add(new AnimationController<>(this, "main", 0, s -> s.setAndContinue(LYING)));
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
