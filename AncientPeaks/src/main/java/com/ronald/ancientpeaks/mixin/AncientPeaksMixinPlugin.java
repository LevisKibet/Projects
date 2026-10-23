package com.ronald.ancientpeaks.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Leaves out the mixins into optional mods (Immersive Gateways, Streams Reflowing) when those aren't installed. */
public class AncientPeaksMixinPlugin implements IMixinConfigPlugin {
    private static boolean has(String modId) {
        LoadingModList l = LoadingModList.get();
        return l != null && l.getModFileById(modId) != null;
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".PortalDataManagerMixin")) return has("immersive_gateways");
        if (mixinClassName.endsWith(".StreamsVetoMixin")) return has("streamsreflowing");
        return true;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
