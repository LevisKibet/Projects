package com.ronald.rivergence.world;

import com.ronald.rivergence.plan.Planner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What dams are made of. A beaver's: logs laid along it, packed with mud, roots and earth, leafy twigs on top. An old
 * reservoir wall: stone bricks long weathered (mossy, cracked, patched with cobble and andesite), its top course broken
 * here and there, vines down its face.
 */
final class WallDecor {
    private WallDecor() {}

    static void run(WorldGenLevel level, Planner.Column[] cols, BlockPos.MutableBlockPos m) {
        for (int a = 1; a <= 16; a++) for (int b = 1; b <= 16; b++) {
            Planner.Column c = cols[a + b * 18];
            if (c.wall == null) continue;
            boolean ancient = c.wall.ancient;
            double wx = c.wall.bx - c.wall.ax, wz = c.wall.bz - c.wall.az;
            Direction.Axis along = Math.abs(wx) > Math.abs(wz) ? Direction.Axis.X : Direction.Axis.Z;
            for (int y = c.wallBase; y <= c.wallTop; y++) {
                BlockState st = level.getBlockState(m.set(c.x, y, c.z));
                if (st.isAir() || !st.getFluidState().isEmpty() && !st.is(Blocks.WATER)) {
                    if (!st.isAir()) continue;
                }
                if (!st.isAir() && !natural(st)) continue;
                double h = BankDecor.hash(c.x, y, c.z, ancient ? 121 : 122);
                boolean top = y == c.wallTop;
                BlockState put;
                if (ancient) {
                    if (top && !c.wallNotch && h < 0.12) { level.setBlock(m, Blocks.AIR.defaultBlockState(), 2); continue; }   // broken course
                    Block bl = h < 0.38 ? Blocks.STONE_BRICKS : h < 0.62 ? Blocks.MOSSY_STONE_BRICKS : h < 0.78 ? Blocks.CRACKED_STONE_BRICKS
                            : h < 0.88 ? Blocks.COBBLESTONE : h < 0.95 ? Blocks.MOSSY_COBBLESTONE : Blocks.ANDESITE;
                    if (top && h < 0.5) bl = Blocks.MOSSY_STONE_BRICKS;
                    put = bl.defaultBlockState();
                } else {
                    if (top && !c.wallNotch && h < 0.35) {
                        put = (h < 0.2 ? Blocks.SPRUCE_LEAVES : Blocks.OAK_LEAVES).defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
                    } else {
                        Block bl = h < 0.42 ? (h < 0.25 ? Blocks.SPRUCE_LOG : Blocks.OAK_LOG) : h < 0.62 ? Blocks.MUD : h < 0.74 ? Blocks.PACKED_MUD
                                : h < 0.86 ? Blocks.MUDDY_MANGROVE_ROOTS : h < 0.94 ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.COARSE_DIRT;
                        put = bl.defaultBlockState();
                        if (put.hasProperty(RotatedPillarBlock.AXIS)) put = put.setValue(RotatedPillarBlock.AXIS, along);
                    }
                }
                level.setBlock(m, put, 2);
            }
            // vines hanging down an old wall's faces
            if (ancient) for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos face = new BlockPos(c.x, c.wallTop - 1, c.z).relative(d);
                if (!level.getBlockState(face).isAir() || BankDecor.hash(c.x, d.ordinal(), c.z, 123) > 0.18) continue;
                int len = 1 + (int) (BankDecor.hash(c.x, 7, c.z, 124) * 5);
                for (int i = 0; i < len; i++) {
                    BlockPos p = face.below(i);
                    if (!level.getBlockState(p).isAir()) break;
                    level.setBlock(p, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d.getOpposite()), true), 2);
                }
            }
        }
    }

    private static boolean natural(BlockState st) {
        return st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL)
                || st.is(Blocks.CLAY) || st.is(Blocks.MUD) || st.is(Blocks.SANDSTONE) || st.is(BlockTags.TERRACOTTA) || st.is(Blocks.SNOW_BLOCK)
                || st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE) || st.is(Blocks.MOSS_BLOCK);
    }
}
