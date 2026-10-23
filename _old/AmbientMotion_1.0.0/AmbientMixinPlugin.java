package com.ronald.ambientmotion.mixin;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** The Embeddium hooks are only applied when Embeddium is installed. */
public class AmbientMixinPlugin implements IMixinConfigPlugin {
    private static boolean has(String id) {
        LoadingModList l = LoadingModList.get();
        return l != null && l.getModFileById(id) != null;
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.contains(".Embeddium")) return has("embeddium") && !has("luxium");   // Luxium rewrites that shader (and waves plants itself)
        return true;
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) { }
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) { }
}
