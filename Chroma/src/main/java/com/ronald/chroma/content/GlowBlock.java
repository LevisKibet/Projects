package com.ronald.chroma.content;

import com.ronald.chroma.light.LightColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The light a strip gives off. Lines put these invisible blocks into the air beside them every couple of blocks, so
 * the light is real vanilla light (mobs won't spawn, plants grow, it works on servers and with Luxium's shadows) and the
 * coloured lighting reads its colour from the block. Anything placed there simply replaces it.
 *
 * There is one glow block per colour (each dye colour at full strength, softened and pale, so mixed lines can give a
 * faint blend), and each one's particle texture is a flat swatch of its colour. Chroma's own
 * lighting knows them by name; Chronicles of Logres' copy of the lighting (which takes over when Chronicles is installed)
 * doesn't know Chroma at all, but it colours any light-giving block it doesn't recognise from that texture, so the
 * glows come out the right colour either way.
 */
public class GlowBlock extends Block {
    public static final BooleanProperty BRIGHT = BooleanProperty.create("bright");
    public final DyeColor dye;
    /** 0 full colour, 1 softened, 2 pale (see Curve.SHADES). */
    public final int shade;

    public GlowBlock(DyeColor dye, int shade) {
        super(BlockBehaviour.Properties.of().replaceable().noCollission().noLootTable().noOcclusion()
                .pushReaction(PushReaction.DESTROY).strength(-1f, 3600000f).noParticlesOnBreak()
                .lightLevel(s -> s.getValue(BRIGHT) ? 13 : 10));
        this.dye = dye;
        this.shade = shade;
        registerDefaultState(stateDefinition.any().setValue(BRIGHT, true));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(BRIGHT); }
    @Override public RenderShape getRenderShape(BlockState s) { return RenderShape.INVISIBLE; }
    @Override public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return Shapes.empty(); }
    @Override public boolean propagatesSkylightDown(BlockState s, BlockGetter l, BlockPos p) { return true; }
    @Override public float getShadeBrightness(BlockState s, BlockGetter l, BlockPos p) { return 1f; }

    /** The colour this glow lights things with (for Chroma's own lighting). */
    public static LightColors.Tone tone(BlockState s) {
        GlowBlock glow = (GlowBlock) s.getBlock();
        int c = Curve.glowColour(glow.dye.getId(), glow.shade);
        float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
        float mx = Math.max(0.01f, Math.max(r, Math.max(g, b)));
        return new LightColors.Tone(r / mx, g / mx, b / mx, false);
    }
}
