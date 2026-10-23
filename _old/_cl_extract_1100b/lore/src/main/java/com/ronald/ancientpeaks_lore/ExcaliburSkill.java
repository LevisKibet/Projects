package com.ronald.ancientpeaks_lore;

import com.google.common.collect.Lists;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.weaponinnate.SimpleWeaponInnateSkill;
import yesman.epicfight.skill.weaponinnate.WeaponInnateSkill;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.entity.eventlistener.PlayerEventListener;

import java.util.*;

/**
 * Excalibur's innate skill, Judgement of Avalon: the bearer raises the sword to the sky and every enemy they have
 * wounded in the last minute is struck by lightning - twice. The bolts are the bearer's own blows (they count as the
 * bearer's kills, and bite Vortigern fully), but they never set the land alight or strike the bearer or their pets.
 */
public class ExcaliburSkill extends SimpleWeaponInnateSkill {
    /** How long a wound marks an enemy for judgement (a minute). */
    static final long MEMORY = 20 * 60;
    /** How far the judgement reaches. */
    static final double RANGE = 64;
    /** Each bolt: 5 hearts, armour piercing as lightning is. */
    static final float BOLT = 10.0f;
    /** Ticks from raising the sword to the first bolt, and between the two. */
    static final int FIRST = 8, SECOND = 14;
    /**
     * Charging: the gauge (skill_parameters consumption, in seconds) fills by itself a second a second. Epic Fight adds
     * the damage of each of your sword's combo blows to it; any other damage you deal adds half its amount, and a
     * clean parry adds PARRY seconds. Our own lightning never charges it.
     */
    static final float PARRY = 15f, OTHER_DAMAGE = 0.5f, MAX_PER_HIT = 12f;
    private static final UUID LISTENER = UUID.fromString("5d0f2a52-3c58-4f5e-9a3b-6f1e0c0a7a11");

    /** bearer -> (wounded entity -> game time of the last wound) */
    private static final Map<UUID, Map<UUID, Long>> WOUNDED = new HashMap<>();
    /** set while our own bolts land, so they don't refresh the marks (the second bolt still finds them) */
    private static boolean striking;

    public ExcaliburSkill(SimpleWeaponInnateSkill.Builder builder) { super(builder); }

    static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((LivingHurtEvent e) -> {
            if (striking) return;
            Entity by = e.getSource().getEntity();
            LivingEntity hit = e.getEntity();
            if (!(by instanceof ServerPlayer p) || hit == p || hit.level().isClientSide) return;
            WOUNDED.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).put(hit.getUUID(), hit.level().getGameTime());
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> WOUNDED.remove(e.getEntity().getUUID()));
        // damage that isn't one of Epic Fight's own combo blows (those charge the gauge already): half of it, up to a cap
        MinecraftForge.EVENT_BUS.addListener((LivingHurtEvent e) -> {
            if (striking || !(e.getSource().getEntity() instanceof ServerPlayer p) || e.getEntity() == p) return;
            if (e.getSource() instanceof yesman.epicfight.world.damagesource.EpicFightDamageSource eds && eds.isBasicAttack()) return;
            charge(p, Math.min(MAX_PER_HIT, e.getAmount() * OTHER_DAMAGE));
        });
    }

    /** Adds seconds to the bearer's Judgement gauge, if Excalibur's skill is the one in their innate slot. */
    static void charge(ServerPlayer p, float amount) {
        if (amount <= 0 || JUDGEMENT() == null) return;
        PlayerPatch<?> pp = yesman.epicfight.world.capabilities.EpicFightCapabilities.getEntityPatch(p, PlayerPatch.class);
        if (pp == null) return;
        SkillContainer c = pp.getSkill(yesman.epicfight.skill.SkillSlots.WEAPON_INNATE);
        if (c == null || !c.hasSkill(JUDGEMENT()) || c.isFull() || c.isActivated()) return;
        c.getSkill().setConsumptionSynchronize(c, c.getResource() + amount);
    }

    private static yesman.epicfight.skill.Skill JUDGEMENT() { return LoreSkills.JUDGEMENT_OF_AVALON; }

    /** Parries: Epic Fight marks a parried blow on the event while guarding; look at it once every guard has had its say. */
    @Override
    public void onInitiate(SkillContainer container) {
        super.onInitiate(container);
        container.getExecutor().getEventListener().addEventListener(PlayerEventListener.EventType.TAKE_DAMAGE_EVENT_ATTACK, LISTENER, event -> {
            ServerPlayer p = event.getPlayerPatch().getOriginal();
            var server = p.getServer();
            if (server == null) return;
            server.tell(new TickTask(server.getTickCount(), () -> { if (event.isParried()) parried(p); }));
        });
    }

    @Override
    public void onRemoved(SkillContainer container) {
        super.onRemoved(container);
        container.getExecutor().getEventListener().removeListener(PlayerEventListener.EventType.TAKE_DAMAGE_EVENT_ATTACK, LISTENER);
    }

    private static void parried(ServerPlayer p) {
        charge(p, PARRY);
        p.serverLevel().sendParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1.4, p.getZ(), 10, 0.3, 0.3, 0.3, 0.2);
    }

    /** Only with a full charge - in creative too (Epic Fight would otherwise let it be cast endlessly there). */
    @Override
    public boolean canExecute(SkillContainer container) {
        return super.canExecute(container) && container.getStack() > 0;
    }

    /** The living enemies the player wounded within the last minute, near enough to be judged. */
    static List<LivingEntity> marked(ServerPlayer p) {
        Map<UUID, Long> m = WOUNDED.get(p.getUUID());
        List<LivingEntity> out = new ArrayList<>();
        if (m == null) return out;
        ServerLevel sl = p.serverLevel();
        long now = sl.getGameTime();
        m.values().removeIf(t -> now - t > MEMORY || t > now);
        for (UUID id : m.keySet()) {
            Entity en = sl.getEntity(id);
            if (!(en instanceof LivingEntity le) || !le.isAlive() || le == p) continue;
            if (le instanceof OwnableEntity o && p.getUUID().equals(o.getOwnerUUID())) continue;   // never your own pets
            if (le.distanceToSqr(p) > RANGE * RANGE) continue;
            out.add(le);
        }
        return out;
    }

    @Override
    public void executeOnServer(SkillContainer container, FriendlyByteBuf args) {
        super.executeOnServer(container, args);                               // raises the sword (and spends the charge)
        if (!(container.getExecutor().getOriginal() instanceof ServerPlayer p)) return;
        if (p.isCreative() && container.getStack() > 0)                       // Epic Fight spends nothing in creative
            yesman.epicfight.skill.Skill.setSkillStackSynchronize(container, container.getStack() - 1);
        ServerLevel sl = p.serverLevel();
        ItemStack blade = p.getMainHandItem();
        if (blade.is(LoreContent.EXCALIBUR.get())) ExcaliburItem.judge(blade, sl.getGameTime());   // the core burns blue
        List<UUID> targets = marked(p).stream().map(Entity::getUUID).toList();
        if (targets.isEmpty()) {
            sl.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 0.6f);
            return;
        }
        var server = sl.getServer();
        server.tell(new TickTask(server.getTickCount() + FIRST, () -> strike(sl, p, targets, true)));
        server.tell(new TickTask(server.getTickCount() + FIRST + SECOND, () -> strike(sl, p, targets, false)));
    }

    private static void strike(ServerLevel sl, ServerPlayer p, List<UUID> targets, boolean first) {
        if (p.isRemoved() || p.level() != sl) return;
        DamageSource src = new DamageSource(sl.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.LIGHTNING_BOLT), p);
        striking = true;
        try {
            for (UUID id : targets) {
                if (!(sl.getEntity(id) instanceof LivingEntity le) || !le.isAlive()) continue;
                LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(sl);
                if (bolt != null) {
                    bolt.moveTo(le.getX(), le.getY(), le.getZ());
                    bolt.setVisualOnly(true);                                 // the bolt is for show: the damage is ours
                    bolt.setCause(p);
                    sl.addFreshEntity(bolt);
                }
                le.invulnerableTime = 0;
                le.hurt(src, BOLT);
                le.setSecondsOnFire(first ? 2 : 3);
                sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, le.getX(), le.getY() + le.getBbHeight() * 0.6, le.getZ(),
                        24, le.getBbWidth() * 0.6, le.getBbHeight() * 0.4, le.getBbWidth() * 0.6, 0.4);
            }
        } finally { striking = false; }
    }

    /** Excalibur's own blows don't change what the shared animation does for other weapons. */
    @Override
    public WeaponInnateSkill registerPropertiesToAnimation() { return this; }

    @Override
    public List<Component> getTooltipOnItem(ItemStack itemStack, CapabilityItem cap, PlayerPatch<?> playerpatch) {
        List<Component> list = Lists.newArrayList();
        String key = this.getTranslationKey();
        list.add(Component.translatable(key).withStyle(ChatFormatting.WHITE)
                .append(Component.literal(String.format("[%.0f]", this.consumption)).withStyle(ChatFormatting.AQUA)));
        list.add(Component.translatable(key + ".tooltip").withStyle(ChatFormatting.DARK_GRAY));
        return list;
    }
}
