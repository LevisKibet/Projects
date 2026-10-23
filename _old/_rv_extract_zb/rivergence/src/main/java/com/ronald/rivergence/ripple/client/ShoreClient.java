package com.ronald.rivergence.ripple.client;

import com.ronald.rivergence.ripple.RippleConfig;

import com.ronald.rivergence.ripple.sim.ShoreMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Keeps a map of the beaches around the player. The area is scanned a few rows per tick
 * and then rescanned now and then, so it never costs much in a single tick.
 */
@Mod.EventBusSubscriber(modid = com.ronald.rivergence.Rivergence.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ShoreClient {
    private static final int SIZE = 81;
    private static final int ROWS_PER_TICK = 4;
    private static final int PAUSE_TICKS = 40;

    private static ShoreMap current;
    private static ShoreMap building;
    private static int row;
    private static int pause;

    /** The latest finished beach map, or null. */
    public static ShoreMap map() {
        return current;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null || !RippleConfig.SHORE_WAVES.get()) {
            current = null;
            building = null;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        if (building == null) {
            if (pause > 0) {
                pause--;
                return;
            }
            building = new ShoreMap(SIZE, player.blockPosition().getX() - SIZE / 2,
                    player.blockPosition().getZ() - SIZE / 2);
            row = 0;
        }

        int end = Math.min(SIZE, row + ROWS_PER_TICK);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int j = row; j < end; j++) {
            for (int i = 0; i < SIZE; i++) {
                classify(level, building, i, j, pos);
            }
        }
        row = end;
        if (row >= SIZE) {
            building.compute();
            current = building;
            building = null;
            pause = PAUSE_TICKS;
        }
    }

    private static void classify(ClientLevel level, ShoreMap map, int i, int j, BlockPos.MutableBlockPos pos) {
        int x = map.originX + i;
        int z = map.originZ + j;
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        pos.set(x, y, z);
        BlockState state = level.getBlockState(pos);
        int k = j * SIZE + i;

        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER) && fluid.isSource()) {
            map.kind[k] = ShoreMap.WATER;
            map.top[k] = (short) y;
        } else if (!state.isAir() && !state.is(BlockTags.LEAVES) && fluid.isEmpty()
                && state.isFaceSturdy(level, pos, Direction.UP)) {
            map.kind[k] = absorbsWater(state) ? ShoreMap.BEACH : ShoreMap.GLOSSY;
            map.top[k] = (short) y;
        }
    }

    /** Sand, gravel and earth soak water up and turn dark; hard blocks just get shiny. */
    private static boolean absorbsWater(BlockState state) {
        return state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(BlockTags.DIRT);
    }
}
