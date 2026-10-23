package com.ronald.chroma.light.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The built-in coloured lighting stands aside entirely when the old standalone Chroma Light mod is installed too:
 * both would hook the same spots in the renderer, and two redirects of one call can't both apply.
 */
public class LightMixinPlugin implements IMixinConfigPlugin {
    /**
     * Chroma's lighting stands aside when another copy of it would hook the same spots: Chronicles of Logres (which has
     * the same lighting built in, and lights Chroma's glows from their colour) or the old standalone Chroma Light.
     */
    public static boolean standDown() {
        try {
            var list = net.minecraftforge.fml.loading.FMLLoader.getLoadingModList();
            if (list == null) return false;
            return list.getModFileById("chromalight") != null || list.getModFileById("ancientpeaks_lore") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean loaded(String id) {
        try {
            var list = net.minecraftforge.fml.loading.FMLLoader.getLoadingModList();
            return list != null && list.getModFileById(id) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (standDown()) return false;
        if (mixinClassName.endsWith("LuxiumLightColourMixin")) return loaded("luxium");
        return true;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
