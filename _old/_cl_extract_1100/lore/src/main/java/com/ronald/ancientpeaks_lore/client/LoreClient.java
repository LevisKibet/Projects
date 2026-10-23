package com.ronald.ancientpeaks_lore.client;

import com.ronald.ancientpeaks_lore.AncientPeaksLore;
import com.ronald.ancientpeaks_lore.LoreContent;
import com.ronald.ancientpeaks_lore.Merlin;
import com.ronald.ancientpeaks_lore.Vortigern;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Client only. Merlin is a GeckoLib model (one animation: wounded). Vortigern is a plain humanoid with a player-layout
 * skin (textures/entity/vortigern.png), drawn by Minecraft's humanoid renderer; Epic Fight runs his longsword fighting.
 */
public final class LoreClient {
    private LoreClient() {}
    static final ResourceLocation VORTIGERN_SKIN = new ResourceLocation(AncientPeaksLore.MODID, "textures/entity/vortigern.png");
    static final ResourceLocation VORTIGERN_EYES = new ResourceLocation(AncientPeaksLore.MODID, "textures/entity/vortigern_eyes.png");
    static final ResourceLocation MERLIN_EYES = new ResourceLocation(AncientPeaksLore.MODID, "textures/entity/merlin_eyes.png");
    static final ResourceLocation STATUE_SKIN = new ResourceLocation(AncientPeaksLore.MODID, "textures/entity/vortigern_statue.png");
    static final ResourceLocation RAIDER = new ResourceLocation(AncientPeaksLore.MODID, "textures/entity/saxon_raider.png");
    static final ResourceLocation SHADE = new ResourceLocation(AncientPeaksLore.MODID, "textures/entity/saxon_shade.png");
    static final ResourceLocation SHADE_CHARGING = new ResourceLocation(AncientPeaksLore.MODID, "textures/entity/saxon_shade_charging.png");

    /** Frozen mid-reach for the sword: leaning in, right hand out toward the hilt, eyes on it, one foot forward. */
    static class StatueModel extends HumanoidModel<com.ronald.ancientpeaks_lore.VortigernStatue> {
        StatueModel(net.minecraft.client.model.geom.ModelPart root) { super(root); }
        @Override
        public void setupAnim(com.ronald.ancientpeaks_lore.VortigernStatue e, float limb, float limbAmt, float age, float yaw, float pitch) {
            head.xRot = 0.45f; head.yRot = -0.1f; head.zRot = 0;
            body.xRot = 0.22f; body.yRot = -0.08f;
            rightArm.xRot = -1.35f; rightArm.yRot = -0.12f; rightArm.zRot = 0.08f;
            leftArm.xRot = -0.25f; leftArm.yRot = 0.1f; leftArm.zRot = -0.35f;
            rightLeg.xRot = -0.42f; rightLeg.yRot = 0.05f;
            leftLeg.xRot = 0.32f; leftLeg.yRot = -0.05f;
            rightArm.z = 0.8f; leftArm.z = 0.6f;                       // the shoulders follow the lean
            hat.copyFrom(head);
        }
    }

    /**
     * Vortigern drawn by Minecraft's own humanoid renderer. His heavy blows are telegraphed: blade raised overhead, then
     * driven down. Two-handed guard with the longsword raised
     * when he is hunting someone, a heavy overhead swing when he strikes.
     */
    static class VortigernModel extends HumanoidModel<Vortigern> {
        VortigernModel(net.minecraft.client.model.geom.ModelPart root) { super(root); }
        @Override
        public void prepareMobModel(Vortigern v, float limb, float limbAmt, float partial) {
            rightArmPose = v.getMainHandItem().isEmpty() ? ArmPose.EMPTY : ArmPose.ITEM;
            leftArmPose = ArmPose.EMPTY;
            super.prepareMobModel(v, limb, limbAmt, partial);
        }
        @Override
        public void setupAnim(Vortigern v, float limb, float limbAmt, float age, float yaw, float pitch) {
            super.setupAnim(v, limb, limbAmt, age, yaw, pitch);
            int heavy = v.heavyPose();
            if (heavy == 1) {
                // winding up: the longsword raised high in both hands
                rightArm.xRot = -2.9f + Mth.cos(age * 0.6f) * 0.05f; rightArm.yRot = -0.25f; rightArm.zRot = 0;
                leftArm.xRot = -2.8f + Mth.cos(age * 0.6f) * 0.05f; leftArm.yRot = 0.35f; leftArm.zRot = 0;
                body.xRot = -0.12f;
                return;
            }
            if (heavy == 2) {
                // the blow: blade driven down, body bent into it
                rightArm.xRot = -0.35f; rightArm.yRot = -0.3f; leftArm.xRot = -0.3f; leftArm.yRot = 0.45f;
                body.xRot = 0.45f;
                return;
            }
            if (v.isAggressive() && attackTime <= 0) {
                // guard: both hands on the hilt, blade up and forward
                rightArm.xRot = -0.9f + Mth.cos(age * 0.09f) * 0.04f; rightArm.yRot = -0.35f; rightArm.zRot = 0;
                leftArm.xRot = -0.85f + Mth.cos(age * 0.09f) * 0.04f; leftArm.yRot = 0.55f; leftArm.zRot = 0;
            } else if (attackTime > 0) {
                // a two-handed overhead cut
                float k = Mth.sin(attackTime * (float) Math.PI);
                float down = -2.6f + attackTime * 3.2f;
                rightArm.xRot = down; rightArm.yRot = -0.3f * k;
                leftArm.xRot = down + 0.1f; leftArm.yRot = 0.45f;
                body.xRot = 0.35f * k;
            }
        }
    }

    public static void init(IEventBus bus) {
        bus.addListener(LoreClient::renderers);
        // Excalibur's core burns blue while Judgement of Avalon is called (models/item/excalibur_judgement.json)
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent e) -> e.enqueueWork(() ->
                net.minecraft.client.renderer.item.ItemProperties.register(LoreContent.EXCALIBUR.get(),
                        new ResourceLocation(AncientPeaksLore.MODID, "judgement"), (stack, level, holder, seed) -> {
                            var lv = level != null ? level : net.minecraft.client.Minecraft.getInstance().level;
                            return com.ronald.ancientpeaks_lore.ExcaliburItem.judging(stack, lv) ? 1f : 0f;
                        })));
        Cutscenes.register();
        EpicBossBar.register();
        bus.addListener((RegisterClientReloadListenersEvent e) -> e.registerReloadListener((ResourceManagerReloadListener) rm -> ClientRes.forget()));
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(LoreContent.PEDESTAL.get(), PedestalBeamRenderer::new);
        e.registerEntityRenderer(LoreContent.MERLIN.get(), ctx -> {
            GeoEntityRenderer<Merlin> r = new GeoEntityRenderer<>(ctx, new CharacterModel<>("merlin", "minecraft:textures/entity/illager/illusioner.png"));
            r.withScale(0.9375f);
            // once he's healed his strength comes back to him: his eyes glow
            r.addRenderLayer(new software.bernie.geckolib.renderer.layer.GeoRenderLayer<Merlin>(r) {
                @Override
                public void render(PoseStack pose, Merlin m, software.bernie.geckolib.cache.object.BakedGeoModel model, net.minecraft.client.renderer.RenderType type,
                                   net.minecraft.client.renderer.MultiBufferSource buffers, com.mojang.blaze3d.vertex.VertexConsumer buffer, float partial, int light, int overlay) {
                    if (!m.healed() || m.isInvisible()) return;
                    var eyes = net.minecraft.client.renderer.RenderType.eyes(MERLIN_EYES);
                    getRenderer().reRender(model, pose, buffers, m, eyes, buffers.getBuffer(eyes), partial, 0xF000F0,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
                }
            });
            return r;
        });
        e.registerEntityRenderer(LoreContent.VORTIGERN.get(), ctx -> {
            HumanoidMobRenderer<Vortigern, HumanoidModel<Vortigern>> r = new HumanoidMobRenderer<>(ctx, new VortigernModel(ctx.bakeLayer(ModelLayers.PLAYER)), 0.7f) {
                @Override public ResourceLocation getTextureLocation(Vortigern v) { return VORTIGERN_SKIN; }
                @Override protected void scale(Vortigern v, PoseStack pose, float partial) { pose.scale(1.3f, 1.3f, 1.3f); }
            };
            r.addLayer(new HumanoidArmorLayer<>(r, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), ctx.getModelManager()));
            // in his second phase his eyes burn under the mask
            r.addLayer(new net.minecraft.client.renderer.entity.layers.EyesLayer<Vortigern, HumanoidModel<Vortigern>>(r) {
                private final net.minecraft.client.renderer.RenderType type = net.minecraft.client.renderer.RenderType.eyes(VORTIGERN_EYES);
                @Override public net.minecraft.client.renderer.RenderType renderType() { return type; }
                @Override
                public void render(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light, Vortigern v, float limb, float limbAmt, float partial, float age, float yaw, float pitch) {
                    if (v.enraged() && !v.isInvisible()) super.render(pose, buffers, light, v, limb, limbAmt, partial, age, yaw, pitch);
                }
            });
            return r;
        });
        e.registerEntityRenderer(LoreContent.VORTIGERN_STATUE.get(), ctx ->
                new HumanoidMobRenderer<com.ronald.ancientpeaks_lore.VortigernStatue, StatueModel>(ctx, new StatueModel(ctx.bakeLayer(ModelLayers.PLAYER)), 0.7f) {
                    @Override public ResourceLocation getTextureLocation(com.ronald.ancientpeaks_lore.VortigernStatue v) { return STATUE_SKIN; }
                    @Override protected void scale(com.ronald.ancientpeaks_lore.VortigernStatue v, PoseStack pose, float partial) { pose.scale(1.3f, 1.3f, 1.3f); }
                });
        e.registerEntityRenderer(LoreContent.SAXON_RAIDER.get(), ctx -> {
            HumanoidMobRenderer<com.ronald.ancientpeaks_lore.SaxonRaider, HumanoidModel<com.ronald.ancientpeaks_lore.SaxonRaider>> r =
                    new HumanoidMobRenderer<>(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)) {
                        @Override public void prepareMobModel(com.ronald.ancientpeaks_lore.SaxonRaider v, float a, float b, float c) {
                            rightArmPose = v.getMainHandItem().isEmpty() ? ArmPose.EMPTY : ArmPose.ITEM;
                            leftArmPose = v.getOffhandItem().isEmpty() ? ArmPose.EMPTY : ArmPose.ITEM;
                            super.prepareMobModel(v, a, b, c);
                        }
                    }, 0.5f) {
                @Override public ResourceLocation getTextureLocation(com.ronald.ancientpeaks_lore.SaxonRaider v) { return RAIDER; }
            };
            r.addLayer(new HumanoidArmorLayer<>(r, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), ctx.getModelManager()));
            return r;
        });
        e.registerEntityRenderer(LoreContent.SAXON_SHADE.get(), ctx -> new net.minecraft.client.renderer.entity.VexRenderer(ctx) {
            @Override public ResourceLocation getTextureLocation(net.minecraft.world.entity.monster.Vex v) { return v.isCharging() ? SHADE_CHARGING : SHADE; }
        });
    }
}
