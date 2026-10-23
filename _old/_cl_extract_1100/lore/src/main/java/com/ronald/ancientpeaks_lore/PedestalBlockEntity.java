package com.ronald.ancientpeaks_lore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The sword stone's block entity: it holds nothing, it's there so the beam rising from the stone can be drawn. */
public class PedestalBlockEntity extends BlockEntity {
    public PedestalBlockEntity(BlockPos pos, BlockState state) { super(LoreContent.PEDESTAL.get(), pos, state); }

    /** The beam reaches the sky, so it's drawn whenever the stone is anywhere near view. */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), worldPosition.getX() + 1, worldPosition.getY() + 1024, worldPosition.getZ() + 1);
    }
}
