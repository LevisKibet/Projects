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
    /** The map reaches as far as the waves are drawn (the render distance, unless set nearer). */
    public static int reach() {
        int set = RippleConfig.SHORE_RANGE.get();
        int rd = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        return Math.max(32, Math.min(256, set > 0 ? Math.min(set, rd) : rd));
    }
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
            int size = reach() * 2 + 17;
            building = new ShoreMap(size, player.blockPosition().getX() - size / 2,
                    player.blockPosition().getZ() - size / 2);
            row = 0;
        }

        final int SIZE = building.size;
        int end = Math.min(SIZE, row + Math.max(4, SIZE / 16));
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
        int k = j * map.size + i;

        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER) && fluid.isSource()) {
            // waves come in only from the sea: water at sea level in an ocean or on a beach, never a river or a lake
            if (y == level.getSeaLevel() - 1 && sea(level, pos)) {
                map.kind[k] = ShoreMap.WATER;
                map.top[k] = (short) y;
            }
        } else if (!state.isAir() && fluid.isEmpty() && natural(state) && y <= level.getSeaLevel() + 3
                && state.isFaceSturdy(level, pos, Direction.UP)) {
            map.kind[k] = absorbsWater(state) ? ShoreMap.BEACH : ShoreMap.GLOSSY;
            map.top[k] = (short) y;
        }
    }

    private static boolean sea(ClientLevel level, BlockPos pos) {
        var b = level.getBiome(pos);
        return b.is(net.minecraft.tags.BiomeTags.IS_OCEAN) || b.is(net.minecraft.tags.BiomeTags.IS_BEACH) || b.is(net.minecraft.tags.BiomeTags.IS_DEEP_OCEAN);
    }

    /** Only the land itself gets waves: not a wreck, a dock or anything else built there. */
    private static boolean natural(BlockState state) {
        return absorbsWater(state) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.CLAY) || state.is(Blocks.MUD) || state.is(Blocks.CALCITE) || state.is(BlockTags.TERRACOTTA)
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE);
    }

    /** Sand, gravel and earth soak water up and turn dark; hard blocks just get shiny. */
    private static boolean absorbsWater(BlockState state) {
        return state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(BlockTags.DIRT);
    }
}
