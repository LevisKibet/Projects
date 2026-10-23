package com.ronald.ancientpeaks_lore;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The stone on the summit pedestal with Excalibur in it. It can't be broken. Right-click it with an empty hand:
 * if Merlin has blessed you the sword comes free, lightning splits the sky and Vortigern's statue beside it wakes;
 * if not, it won't stir.
 */
public class SwordInStoneBlock extends Block implements net.minecraft.world.level.block.EntityBlock {
    public static final BooleanProperty PULLED = BooleanProperty.create("pulled");
    /** Vortigern has fallen here: a beam of light rises from the stone into the sky (dark again while he's risen). */
    public static final BooleanProperty BEAM = BooleanProperty.create("beam");
    private static final VoxelShape WITH_SWORD = Shapes.or(Block.box(0, 0, 0, 16, 16, 16), Block.box(7, 16, 7, 9, 23, 9), Block.box(2, 19.5, 6.5, 14, 23, 9.5), Block.box(6.5, 23, 6.5, 9.5, 32, 9.5));

    public SwordInStoneBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(PULLED, false).setValue(BEAM, false));
    }

    /** While the sword is still in it, the blade glimmers: motes of white light drift up off it. */
    @Override
    public void animateTick(BlockState s, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.util.RandomSource r) {
        if (s.getValue(PULLED) || r.nextInt(3) != 0) return;
        level.addParticle(r.nextInt(5) == 0 ? net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK : net.minecraft.core.particles.ParticleTypes.END_ROD,
                pos.getX() + 0.5 + (r.nextDouble() - 0.5) * 0.4, pos.getY() + 1.0 + r.nextDouble() * 0.9, pos.getZ() + 0.5 + (r.nextDouble() - 0.5) * 0.4,
                0, 0.015, 0);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(PULLED, BEAM); }

    @Override
    public VoxelShape getShape(BlockState s, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return s.getValue(PULLED) ? Shapes.block() : WITH_SWORD;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState s, BlockGetter level, BlockPos pos, CollisionContext ctx) { return Shapes.block(); }

    // ------------------------------------------------------------------ the beam (drawn by the stone's block entity)

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PedestalBlockEntity(pos, state); }

    /** Stones placed before the beam existed get their block entity the first time the world ticks them. */
    @Override public boolean isRandomlyTicking(BlockState s) { return true; }

    @Override
    @SuppressWarnings("deprecation")
    public void randomTick(BlockState s, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource r) {
        if (level.getBlockEntity(pos) == null) level.setBlockEntity(new PedestalBlockEntity(pos, s));
    }

    // ------------------------------------------------------------------ calling him back: a nether star on the stone

    private InteractionResult summonWithStar(BlockState state, ServerLevel sl, BlockPos pos, Player player, ItemStack star) {
        if (state.getValue(PULLED) || !sl.getEntitiesOfClass(Vortigern.class, new net.minecraft.world.phys.AABB(pos).inflate(96)).isEmpty()) {
            Quest.hint(player, "Vortigern already walks. The stone will not answer again until he falls.");
            return InteractionResult.CONSUME;                              // the star is kept
        }
        if (!player.getAbilities().instabuild) star.shrink(1);
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(sl);
        if (bolt != null) { bolt.moveTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5); bolt.setVisualOnly(true); sl.addFreshEntity(bolt); }
        sl.playSound(null, pos, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.0f, 0.8f);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 40, 0.4, 0.6, 0.4, 0.03);
        player.sendSystemMessage(Component.literal("The star burns away in the stone. Something stirs.").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        sl.setBlock(pos, state.setValue(PULLED, true), 3);                 // the beam goes dark while he walks
        var statues = sl.getEntitiesOfClass(VortigernStatue.class, new net.minecraft.world.phys.AABB(pos).inflate(16));
        if (!statues.isEmpty()) statues.get(0).awaken(player);
        else Vortigern.rise(sl, pos, player);
        return InteractionResult.CONSUME;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(net.minecraft.world.item.Items.NETHER_STAR)) {          // anyone may call him back for another fight
            if (level.isClientSide) return InteractionResult.SUCCESS;
            return summonWithStar(state, (ServerLevel) level, pos, player, held);
        }
        if (state.getValue(PULLED)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty()) {
            if (!level.isClientSide) Quest.hint(player, "Take hold of it with an empty hand.");
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        ServerLevel sl = (ServerLevel) level;
        if (!Quest.has(player, Quest.BLESSED)) {
            Quest.hint(player, "The sword does not stir. It answers only to one Merlin has blessed.");
            sl.playSound(null, pos, SoundEvents.STONE_HIT, SoundSource.BLOCKS, 1.0f, 0.5f);
            return InteractionResult.CONSUME;
        }
        // each player draws it for themselves: the stone gives the sword to everyone Merlin blessed (once each), and
        // Vortigern wakes only for someone who hasn't yet seen him fall - the stone is whole again after each fight
        boolean slain = Quest.has(player, Quest.SLAIN);
        boolean drawn = Quest.has(player, Quest.EXCALIBUR);
        boolean holding = player.getInventory().hasAnyMatching(st -> st.is(LoreContent.EXCALIBUR.get()));
        if (slain && (drawn && holding)) {
            Quest.hint(player, "Logres is free of the usurper. The sword rests for the next who needs it.");
            return InteractionResult.CONSUME;
        }
        if (!holding) {
            player.setItemInHand(InteractionHand.MAIN_HAND, ExcaliburItem.blessed(new ItemStack(LoreContent.EXCALIBUR.get())));
            Quest.set(player, Quest.EXCALIBUR, true);
            player.sendSystemMessage(Component.literal("You draw Excalibur from the stone.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(sl);
        if (bolt != null) { bolt.moveTo(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5); bolt.setVisualOnly(true); sl.addFreshEntity(bolt); }
        sl.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.2f, 0.5f);
        sl.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 2.0f, 1.2f);
        if (slain) return InteractionResult.CONSUME;                    // they've beaten him before: the sword alone
        if (!sl.getEntitiesOfClass(Vortigern.class, new net.minecraft.world.phys.AABB(pos).inflate(96)).isEmpty()) return InteractionResult.CONSUME;   // he's already up: join the fight
        sl.setBlock(pos, state.setValue(PULLED, true), 3);              // the stone stands empty until he falls
        var statues = sl.getEntitiesOfClass(VortigernStatue.class, new net.minecraft.world.phys.AABB(pos).inflate(16));
        if (!statues.isEmpty()) statues.get(0).awaken(player);
        else Vortigern.rise(sl, pos, player);
        return InteractionResult.CONSUME;
    }
}
