package com.ronald.ambientmotion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * Feeds the sway shaders (assets/minecraft/shaders/include/ambientmotion_sway.glsl) each frame: the clock, the
 * strength, and where the lantern textures sit on the block atlas. The vanilla terrain shaders get them as uniforms
 * here; Embeddium's get the same values from EmbeddiumShaderStateMixin.
 */
public final class Sway {
    private Sway() {}

    static float time, strength;
    /** Luxium waves plants with its own terrain shaders: ours stand down so nothing sways twice. */
    static final boolean LUXIUM = net.minecraftforge.fml.ModList.get() != null && net.minecraftforge.fml.ModList.get().isLoaded("luxium");
    static final float[] lantern0 = new float[4], lantern1 = new float[4];
    private static final ResourceLocation LANTERN = new ResourceLocation("minecraft", "block/lantern");
    private static final ResourceLocation SOUL_LANTERN = new ResourceLocation("minecraft", "block/soul_lantern");

    static void update(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        strength = AmbientMotion.shaderPackInUse() || LUXIUM ? 0 : Settings.sway();
        time = mc.level == null ? 0 : (float) ((mc.level.getGameTime() % 72000L) + partialTick) / 20f;
        boolean lanterns = Settings.lanterns() && strength > 0;
        rect(mc, LANTERN, lanterns, lantern0);
        rect(mc, SOUL_LANTERN, lanterns, lantern1);
        apply(GameRenderer.getRendertypeCutoutShader());
        apply(GameRenderer.getRendertypeCutoutMippedShader());
    }

    private static void rect(Minecraft mc, ResourceLocation id, boolean on, float[] out) {
        if (!on) { out[0] = out[1] = out[2] = out[3] = 0; return; }
        try {
            TextureAtlasSprite s = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(id);
            out[0] = s.getU0(); out[1] = s.getV0(); out[2] = s.getU1(); out[3] = s.getV1();
        } catch (RuntimeException e) {
            out[0] = out[1] = out[2] = out[3] = 0;
        }
    }

    private static void apply(ShaderInstance sh) {
        if (sh == null) return;
        sh.safeGetUniform("AmbientTime").set(time);
        sh.safeGetUniform("AmbientSway").set(strength);
        sh.safeGetUniform("AmbientLantern0").set(lantern0[0], lantern0[1], lantern0[2], lantern0[3]);
        sh.safeGetUniform("AmbientLantern1").set(lantern1[0], lantern1[1], lantern1[2], lantern1[3]);
    }
}
