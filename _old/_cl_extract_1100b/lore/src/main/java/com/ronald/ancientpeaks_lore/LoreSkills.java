package com.ronald.ancientpeaks_lore;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import yesman.epicfight.api.forgeevent.SkillBuildEvent;
import yesman.epicfight.api.forgeevent.WeaponCapabilityPresetRegistryEvent;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.skill.weaponinnate.SimpleWeaponInnateSkill;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.WeaponCapability;
import yesman.epicfight.world.capabilities.item.WeaponCapabilityPresets;

/**
 * Epic Fight hooks: Excalibur's innate skill (Judgement of Avalon) and the "ancientpeaks_lore:excalibur" weapon type -
 * Epic Fight's longsword in every way (combos, guard, idle and walk), with Judgement of Avalon as its innate skill in
 * every style. The item's capability json (capabilities/weapons/excalibur.json) points at that type.
 */
public final class LoreSkills {
    private LoreSkills() {}
    public static SimpleWeaponInnateSkill JUDGEMENT_OF_AVALON;
    static final ResourceLocation EXCALIBUR_TYPE = new ResourceLocation(AncientPeaksLore.MODID, "excalibur");

    static void register(IEventBus modBus) {
        modBus.addListener(LoreSkills::skills);
        modBus.addListener(LoreSkills::presets);
        ExcaliburSkill.registerEvents();
    }

    private static void skills(SkillBuildEvent e) {
        SkillBuildEvent.ModRegistryWorker reg = e.createRegistryWorker(AncientPeaksLore.MODID);
        SimpleWeaponInnateSkill.Builder b = SimpleWeaponInnateSkill.createSimpleWeaponInnateBuilder().setAnimations(Animations.WRATHFUL_LIGHTING);
        b.setResource(yesman.epicfight.skill.Skill.Resource.COOLDOWN);           // fills over time (and faster as you fight)
        JUDGEMENT_OF_AVALON = reg.build("judgement_of_avalon", ExcaliburSkill::new, b);
    }

    private static void presets(WeaponCapabilityPresetRegistryEvent e) {
        e.getTypeEntry().put(EXCALIBUR_TYPE, item -> {
            WeaponCapability.Builder b = (WeaponCapability.Builder) WeaponCapabilityPresets.LONGSWORD.apply(item);
            for (CapabilityItem.Styles s : new CapabilityItem.Styles[]{CapabilityItem.Styles.ONE_HAND, CapabilityItem.Styles.TWO_HAND, CapabilityItem.Styles.OCHS})
                b.innateSkill(s, stack -> JUDGEMENT_OF_AVALON);
            return b;
        });
    }
}
