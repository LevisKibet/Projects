package com.ronald.ancientpeaks_lore.client;

import com.ronald.ancientpeaks_lore.AncientPeaksLore;
import com.ronald.ancientpeaks_lore.Merlin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * geo/entity/NAME.geo.json, animations/entity/NAME.animation.json and textures/entity/NAME.png, from the jar or
 * (while testing) from the dev folder's characters/NAME/. Until the character has its own texture a vanilla skin
 * with the same layout stands in. The "head" bone follows where the character looks.
 */
public class CharacterModel<T extends GeoAnimatable> extends GeoModel<T> {
    private final ResourceLocation geo, anim, tex, fallback;

    public CharacterModel(String name, String fallbackTexture) {
        geo = new ResourceLocation(AncientPeaksLore.MODID, "geo/entity/" + name + ".geo.json");
        anim = new ResourceLocation(AncientPeaksLore.MODID, "animations/entity/" + name + ".animation.json");
        tex = new ResourceLocation(AncientPeaksLore.MODID, "textures/entity/" + name + ".png");
        fallback = new ResourceLocation(fallbackTexture);
    }

    @Override public ResourceLocation getModelResource(T a) { return geo; }
    @Override public ResourceLocation getAnimationResource(T a) { return anim; }
    @Override public ResourceLocation getTextureResource(T a) { return ClientRes.exists(tex.toString()) ? tex : fallback; }

    @Override
    public void setCustomAnimations(T animatable, long id, AnimationState<T> state) {
        super.setCustomAnimations(animatable, id, state);
        if (animatable instanceof Merlin) return;                         // he only ever lies there
        getBone("head").ifPresent(head -> {
            EntityModelData d = state.getData(DataTickets.ENTITY_MODEL_DATA);
            if (d == null) return;
            head.setRotX(d.headPitch() * Mth.DEG_TO_RAD);
            head.setRotY(d.netHeadYaw() * Mth.DEG_TO_RAD);
        });
    }
}
