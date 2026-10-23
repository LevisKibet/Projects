package com.palmtunes.mixin;

import com.palmtunes.client.PalmPose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Overrides the right arm's pose once vanilla (and the sleeve copy) are done. */
@Mixin(value = PlayerModel.class, priority = 1500)
public abstract class PlayerModelMixin<T extends LivingEntity> extends HumanoidModel<T> {

    @Shadow
    @Final
    public ModelPart rightSleeve;

    // Never called: only here so the compiler accepts the superclass.
    private PlayerModelMixin(ModelPart root) {
        super(root);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void palmtunes$palmPose(T entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                    float netHeadYaw, float headPitch, CallbackInfo ci) {
        PalmPose.apply(this.rightArm, this.rightSleeve, entity, ageInTicks);
        PalmPose.adjustHead(this.head, this.hat, entity);
    }
}
