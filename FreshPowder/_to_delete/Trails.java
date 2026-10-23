package com.ronald.freshpowder.world;

import com.ronald.freshpowder.Config;
import com.ronald.freshpowder.FreshPowder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

/** Walking through snow presses it down a layer each time you step into a new block, so paths wear in. */
@Mod.EventBusSubscriber(modid = FreshPowder.MODID)
public final class Trails {
    private Trails() {}

    private static final Map<Entity, Long> LAST = new WeakHashMap<>();

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent e) {
        LivingEntity en = e.getEntity();
        Level level = en.level();
        if (level.isClientSide || ((en.tickCount + en.getId()) & 3) != 0) return;
        if (!Config.TRAILS.get() || !(en instanceof Player || Config.MOB_TRAILS.get())) return;
        if (!en.onGround() || en.getBbWidth() < 0.35f || en.isSpectator()) return;
        BlockPos p = en.blockPosition();
        Long last = LAST.put(en, p.asLong());
        if (last == null || last == p.asLong()) return;                    // only on stepping into a new block
        BlockState s = level.getBlockState(p);
        if (!s.is(Blocks.SNOW)) return;
        int n = s.getValue(SnowLayerBlock.LAYERS);
        int press = en instanceof Player || en.getBbWidth() > 1.2f ? 2 : 1;      // people and horses sink deeper than a fox
        int to = Math.max(1, n - press);
        if (to < n) level.setBlock(p, s.setValue(SnowLayerBlock.LAYERS, to), 3);
    }
}
