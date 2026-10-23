package com.ronald.ancientpeaks_lore;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Game rules for the quest that live outside the entities. */
final class LoreEvents {
    private LoreEvents() {}
    /** The most one of Vortigern's blows can deal, before armour (6 hearts): he must never one-shot a well-armed player. */
    static final float MAX_BLOW = 9.0f;
    /** The most any one of his blows (or his lightning) can take off a player after armour: 3 hearts. */
    static final float MAX_WOUND = 6.0f;

    static void register() {
        MinecraftForge.EVENT_BUS.addListener((LivingHurtEvent e) -> {
            if (e.getSource().getEntity() instanceof Vortigern && e.getAmount() > MAX_BLOW) e.setAmount(MAX_BLOW);
        });
        // and after armour and Epic Fight's armour negation: never more than 3 hearts a blow, lightning included
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.living.LivingDamageEvent e) -> {
            if (!(e.getEntity() instanceof net.minecraft.world.entity.player.Player p) || e.getAmount() <= MAX_WOUND) return;
            var src = e.getSource();
            boolean his = src.getEntity() instanceof Vortigern || src.getDirectEntity() instanceof Vortigern
                    || (src.is(net.minecraft.world.damagesource.DamageTypes.LIGHTNING_BOLT)
                        && !p.level().getEntitiesOfClass(Vortigern.class, p.getBoundingBox().inflate(32)).isEmpty());
            if (his) e.setAmount(MAX_WOUND);
        });
        // the usurper's blade withers what it cuts: always in a player's hand, in Vortigern's only once he's roused
        // (here rather than only in the item, so Epic Fight's own blows count too)
        MinecraftForge.EVENT_BUS.addListener((LivingHurtEvent e) -> {
            var src = e.getSource();
            if (!(src.getEntity() instanceof net.minecraft.world.entity.LivingEntity by) || src.getDirectEntity() != by || e.getEntity() == by) return;
            boolean sword = by.getMainHandItem().is(LoreContent.VORTIGERN_SWORD.get());
            if (by instanceof Vortigern v ? v.enraged() : sword) VortigernSwordItem.wither(e.getEntity());
        });
        // nobody can be hurt while a cutscene is playing for them
        MinecraftForge.EVENT_BUS.addListener((LivingAttackEvent e) -> {
            if (e.getEntity().getTags().contains(LoreNet.IN_CUTSCENE)) e.setCanceled(true);
        });
        // a cutscene cut short by leaving the game doesn't leave you untouchable
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> e.getEntity().removeTag(LoreNet.IN_CUTSCENE));
    }
}
