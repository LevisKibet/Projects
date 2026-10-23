package com.ronald.chroma;

import com.ronald.chroma.content.AnchorBlock;
import com.ronald.chroma.content.ChromaContent;
import com.ronald.chroma.content.Curve;
import com.ronald.chroma.content.GlowBlock;
import com.ronald.chroma.content.LineEntity;
import com.ronald.chroma.content.LineItem;
import com.ronald.chroma.content.ShaperItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lines are edited by the client saying what it clicked (a point, a handle, a stretch of line, or a block face to lay
 * the next point on) and the server checking it and doing it.
 */
public final class ChromaNet {
    private ChromaNet() {}

    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(Chroma.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static final int PLACE = 0, RESUME = 1, GROW = 2, TURN = 3, REMOVE = 4, MODE = 5, SPEED = 6, DYE = 7, FINISH = 8, RAINBOW = 9;

    /** The line each player is laying right now (its anchor). */
    private static final Map<UUID, BlockPos> BUILDING = new HashMap<>();

    public static void register() {
        CHANNEL.messageBuilder(Edit.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Edit::encode).decoder(Edit::decode).consumerMainThread(Edit::handle).add();
    }

    public static void send(Edit e) { CHANNEL.sendToServer(e); }

    /** What a click did: which action, on which line (or which block was clicked), which point, where and which face. */
    public record Edit(int action, BlockPos pos, int index, Vec3 hit, int face, boolean sneak) {
        void encode(FriendlyByteBuf b) {
            b.writeVarInt(action); b.writeBlockPos(pos); b.writeVarInt(index);
            b.writeDouble(hit.x); b.writeDouble(hit.y); b.writeDouble(hit.z);
            b.writeByte(face); b.writeBoolean(sneak);
        }
        static Edit decode(FriendlyByteBuf b) {
            return new Edit(b.readVarInt(), b.readBlockPos(), b.readVarInt(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readByte(), b.readBoolean());
        }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            ctx.get().enqueueWork(() -> { if (p != null) apply(p, this); });
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ the server's side of every click

    static void apply(ServerPlayer p, Edit e) {
        ServerLevel level = p.serverLevel();
        ItemStack held = p.getMainHandItem();
        if (e.action == PLACE || e.action == FINISH) { place(p, level, held, e); return; }

        if (!(level.getBlockEntity(e.pos) instanceof LineEntity line)) return;
        if (p.distanceToSqr(Vec3.atCenterOf(e.pos)) > 64 * 64) return;
        if (e.index >= 0 && (e.index >= line.points().size() || p.getEyePosition().distanceTo(line.points().get(e.index).pos()) > 9)) return;

        switch (e.action) {
            case RESUME -> {
                if (held.getItem() instanceof LineItem li && li.kind == line.kind()) {
                    BUILDING.put(p.getUUID(), e.pos);
                    tell(p, "Carrying on this line: right-click to add points, sneak right-click to finish.");
                }
            }
            case GROW -> { if (held.getItem() instanceof ShaperItem) { line.growHandle(e.index, e.sneak ? -1 : 1); click(level, e, 1.2f); } }
            case TURN -> { if (held.getItem() instanceof ShaperItem) { line.turnHandle(e.index); click(level, e, 1.0f); } }
            case REMOVE -> {
                if (held.getItem() instanceof ShaperItem) {
                    if (line.removePoint(e.index)) click(level, e, 0.7f);
                    else tell(p, "A line needs at least two points. Break its anchor to take it down.");
                }
            }
            case MODE -> {
                if (held.getItem() instanceof ShaperItem) {
                    line.cycleMode();
                    tell(p, "Pattern: " + (line.kind() == Curve.BULBS ? Curve.BULB_MODES : Curve.STRIP_MODES)[line.mode()]);
                }
            }
            case SPEED -> {
                if (held.getItem() instanceof ShaperItem) { line.cycleSpeed(); tell(p, "Speed: " + Curve.SPEED_NAMES[line.speed()]); }
            }
            case DYE -> {
                if (held.getItem() instanceof DyeItem dye) {
                    line.dye(dye.getDyeColor(), e.sneak);
                    if (!p.getAbilities().instabuild) held.shrink(1);
                    level.playSound(null, BlockPos.containing(e.hit), SoundEvents.DYE_USE, SoundSource.BLOCKS, 1f, 1f);
                }
            }
            case RAINBOW -> {
                if (held.is(net.minecraft.world.item.Items.AMETHYST_SHARD)) {
                    line.rainbow();
                    level.playSound(null, BlockPos.containing(e.hit), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, 1.2f);
                }
            }
            default -> {}
        }
    }

    /** Lay the next point of a line, or start a new one. */
    private static void place(ServerPlayer p, ServerLevel level, ItemStack held, Edit e) {
        if (e.action == FINISH || e.sneak) {
            if (BUILDING.remove(p.getUUID()) != null) tell(p, "Line finished.");
            return;
        }
        if (!(held.getItem() instanceof LineItem item)) return;
        if (p.getEyePosition().distanceTo(e.hit) > 8) return;
        Direction face = Direction.from3DDataValue(e.face);
        BlockPos current = BUILDING.get(p.getUUID());
        boolean starting = current == null || !(level.getBlockEntity(current) instanceof LineEntity cl) || cl.kind() != item.kind;
        Vec3 at = snap(e.pos, face, e.hit, starting).add(Vec3.atLowerCornerOf(face.getNormal()).scale(item.kind == Curve.BULBS ? 0.03 : 0.004));
        Curve.Pt pt = new Curve.Pt(at, face, Vec3.ZERO);

        BlockPos building = BUILDING.get(p.getUUID());
        if (building != null && level.getBlockEntity(building) instanceof LineEntity line && line.kind() == item.kind) {
            if (line.addPoint(pt)) { click(level, e, 1.4f); return; }
            if (line.points().size() >= LineEntity.MAX_POINTS) tell(p, "That line is as long as it gets. Starting a new one.");
            else if (line.points().get(line.points().size() - 1).pos().distanceTo(at) > LineEntity.MAX_SPAN) tell(p, "Too far from the last point. Starting a new line.");
            else return;
        }

        // a new line: its anchor clips on beside the face that was clicked
        BlockPos anchor = e.pos.relative(face);
        BlockState here = level.getBlockState(anchor);
        if (!(here.isAir() || here.getBlock() instanceof GlowBlock || here.canBeReplaced())) { tell(p, "No room for the anchor there."); return; }
        pt = new Curve.Pt(snap(e.pos, face, e.hit, true).add(Vec3.atLowerCornerOf(face.getNormal()).scale(item.kind == Curve.BULBS ? 0.03 : 0.004)), face, Vec3.ZERO);
        level.setBlockAndUpdate(anchor, ChromaContent.ANCHOR.get().defaultBlockState().setValue(AnchorBlock.FACING, face));
        if (level.getBlockEntity(anchor) instanceof LineEntity line) {
            line.start(item.kind, pt);
            BUILDING.put(p.getUUID(), anchor);
            if (!p.getAbilities().instabuild) held.shrink(1);
            level.playSound(null, anchor, SoundEvents.LANTERN_PLACE, SoundSource.BLOCKS, 0.8f, 1.3f);
        }
    }

    /**
     * Points sit on one of five spots on a block face: its middle, or the middle of one of its quarters. A line always
     * starts from the middle, where its anchor clip sits, so the cord comes out from under the clip.
     */
    static Vec3 snap(BlockPos block, Direction face, Vec3 hit, boolean start) {
        double x = hit.x - block.getX(), y = hit.y - block.getY(), z = hit.z - block.getZ();
        Direction.Axis ax = face.getAxis();
        // the two in-face coordinates
        double[] uv = ax == Direction.Axis.X ? new double[]{y, z} : ax == Direction.Axis.Y ? new double[]{x, z} : new double[]{x, y};
        double bu = 0.5, bv = 0.5;
        if (!start) {
            double best = (uv[0] - 0.5) * (uv[0] - 0.5) + (uv[1] - 0.5) * (uv[1] - 0.5);
            for (double qu : new double[]{0.25, 0.75}) for (double qv : new double[]{0.25, 0.75}) {
                double d = (uv[0] - qu) * (uv[0] - qu) + (uv[1] - qv) * (uv[1] - qv);
                if (d < best) { best = d; bu = qu; bv = qv; }
            }
        }
        if (ax == Direction.Axis.X) { y = bu; z = bv; }
        else if (ax == Direction.Axis.Y) { x = bu; z = bv; }
        else { x = bu; y = bv; }
        // on the face itself
        if (ax == Direction.Axis.X) x = face == Direction.EAST ? 1 : 0;
        if (ax == Direction.Axis.Y) y = face == Direction.UP ? 1 : 0;
        if (ax == Direction.Axis.Z) z = face == Direction.SOUTH ? 1 : 0;
        return new Vec3(block.getX() + x, block.getY() + y, block.getZ() + z);
    }

    private static void click(ServerLevel level, Edit e, float pitch) {
        level.playSound(null, BlockPos.containing(e.hit), SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.5f, pitch);
    }

    private static void tell(ServerPlayer p, String s) { p.displayClientMessage(Component.literal(s).withStyle(ChatFormatting.GRAY), true); }

    public static void forget(UUID player) { BUILDING.remove(player); }

    /** Which hand clicks count from (lines are only edited with the main hand). */
    public static final InteractionHand HAND = InteractionHand.MAIN_HAND;
}
