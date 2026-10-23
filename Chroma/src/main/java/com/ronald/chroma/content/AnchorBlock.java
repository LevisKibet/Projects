package com.ronald.chroma.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * The little clip a line starts from. It holds the whole line (see LineEntity); break it and the line comes down and
 * drops back as one strip or string. FACING is the way out from the surface it's clipped to.
 */
public class AnchorBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);
    static {
        SHAPES.put(Direction.UP, Block.box(5, 0, 5, 11, 1.5, 11));
        SHAPES.put(Direction.DOWN, Block.box(5, 14.5, 5, 11, 16, 11));
        SHAPES.put(Direction.NORTH, Block.box(5, 5, 14.5, 11, 11, 16));
        SHAPES.put(Direction.SOUTH, Block.box(5, 5, 0, 11, 11, 1.5));
        SHAPES.put(Direction.WEST, Block.box(14.5, 5, 5, 16, 11, 11));
        SHAPES.put(Direction.EAST, Block.box(0, 5, 5, 1.5, 11, 11));
    }

    public AnchorBlock() {
        super(BlockBehaviour.Properties.of().noCollission().noOcclusion().instabreak().sound(SoundType.LANTERN)
                .pushReaction(PushReaction.DESTROY));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPES.get(s.getValue(FACING)); }
    @Override public VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return Shapes.empty(); }
    @Override public RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new LineEntity(pos, state); }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !player.getAbilities().instabuild && level.getBlockEntity(pos) instanceof LineEntity line)
            Block.popResource(level, pos, new ItemStack(line.kind() == Curve.BULBS ? ChromaContent.STRING_LIGHTS.get() : ChromaContent.LED_STRIP.get()));
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
        if (!state.is(now.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof LineEntity line) line.clearGlows();
        super.onRemove(state, level, pos, now, moved);
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        return new ItemStack(level.getBlockEntity(pos) instanceof LineEntity l && l.kind() == Curve.BULBS ? ChromaContent.STRING_LIGHTS.get() : ChromaContent.LED_STRIP.get());
    }

    @Override public boolean canSurvive(BlockState s, LevelReader l, BlockPos p) { return true; }
}
