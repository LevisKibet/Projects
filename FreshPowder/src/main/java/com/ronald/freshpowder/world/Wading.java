package com.ronald.freshpowder.world;

import com.ronald.freshpowder.Config;
import com.ronald.freshpowder.FreshPowder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Wading through deep powder takes a little more effort: the deeper, the slower. */
@Mod.EventBusSubscriber(modid = FreshPowder.MODID)
public final class Wading {
    private Wading() {}

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent e) {
        LivingEntity en = e.getEntity();
        if (!en.onGround() || en.isSpectator() || !Config.WADING.get()) return;
        BlockState s = en.level().getBlockState(en.blockPosition());
        if (!s.is(Blocks.SNOW)) return;
        int n = s.getValue(SnowLayerBlock.LAYERS);
        if (n < 3 || n >= 8) return;
        double f = n == 3 ? 0.93 : n == 4 ? 0.9 : 0.86;
        Vec3 v = en.getDeltaMovement();
        en.setDeltaMovement(v.x * f, v.y, v.z * f);
    }
}
