package com.ronald.chroma.client;

import com.ronald.chroma.content.AnchorBlock;
import com.ronald.chroma.content.ChromaContent;
import com.ronald.chroma.content.Curve;
import com.ronald.chroma.content.LineEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.List;

/**
 * Only runs in a development launch with -Dchroma.autotest=true: builds a dark test room with a few lines in it,
 * takes screenshots from set spots, then quits. Lets the lights be checked without anyone at the keyboard.
 */
public final class ChromaDev {
    private ChromaDev() {}

    public static void init() {
        if (!Boolean.getBoolean("chroma.autotest")) return;
        MinecraftForge.EVENT_BUS.addListener(ChromaDev::tick);
    }

    private static int t = -1;
    private static BlockPos base;

    private static final float[][] SHOTS = {
            // x, y, z (relative to the room's floor centre), yaw, pitch, holding the shaper?
            {0, 1.6f, -2, 180, 5, 0},       // north wall, red
            {-3, 1.6f, 0, 90, 8, 0},        // west wall, green
            {4, 1.6f, 0, 270, 5, 0},        // east wall, string lights
            {0, 3.4f, 1, 0, 35, 0},         // the rainbow on the floor
            {0, 1.6f, -4, 180, 5, 1},       // the north wall with the shaper in hand
    };

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        t++;
        if (t == 20) {
            BlockPos at = mc.player.blockPosition();
            base = new BlockPos(at.getX(), at.getY(), at.getZ());
            mc.getSingleplayerServer().execute(() -> build(mc.getSingleplayerServer().overworld(), base));
            mc.options.hideGui = true;
        }
        if (t < 120) return;
        int k = (t - 120) / 40, phase = (t - 120) % 40;
        if (k >= SHOTS.length) { if (phase == 5) mc.stop(); return; }
        float[] s = SHOTS[k];
        if (phase == 0) {
            mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    s[5] > 0 ? new ItemStack(ChromaContent.LIGHT_SHAPER.get()) : ItemStack.EMPTY);
            mc.player.moveTo(base.getX() + 0.5 + s[0], base.getY() + s[1] - 1.62, base.getZ() + 0.5 + s[2], s[3], s[4]);
            mc.getSingleplayerServer().execute(() -> {
                var sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                sp.teleportTo(base.getX() + 0.5 + s[0], base.getY() + s[1] - 1.62, base.getZ() + 0.5 + s[2]);
                sp.setYRot(s[3]); sp.setXRot(s[4]);
            });
        }
        if (phase == 30) Screenshot.grab(mc.gameDirectory, "chroma_test_" + k + ".png", mc.getMainRenderTarget(), c -> {});
    }

    /** A closed stone room, 21 wide, with one kind of line on each wall so their light doesn't mix. */
    private static void build(ServerLevel level, BlockPos b) {
        level.setDayTime(18000);
        int R = 10;
        for (int x = -R; x <= R; x++) for (int y = -1; y <= 6; y++) for (int z = -R; z <= R; z++) {
            boolean wall = Math.abs(x) == R || Math.abs(z) == R || y == -1 || y == 6;
            level.setBlockAndUpdate(b.offset(x, y, z), wall ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        double nz = b.getZ() - R + 1 + 0.004;          // north wall: a red strip, curved in the middle
        line(level, b.offset(-4, 3, -R + 1), Direction.SOUTH, Curve.STRIP, List.of(
                pt(b.getX() - 3.75, b.getY() + 3.25, nz, Direction.SOUTH, Vec3.ZERO),
                pt(b.getX() + 0.25, b.getY() + 4.25, nz, Direction.SOUTH, new Vec3(1.5, 0, 0)),
                pt(b.getX() + 4.25, b.getY() + 3.25, nz, Direction.SOUTH, Vec3.ZERO)),
                new int[]{Curve.VIVID[DyeColor.RED.getId()]}, Curve.SOLID);
        if (Boolean.getBoolean("chroma.single")) return;
        double wx = b.getX() - R + 1 + 0.004;          // west wall: a red and blue strip (should cast a soft purple)
        line(level, b.offset(-R + 1, 2, -4), Direction.EAST, Curve.STRIP, List.of(
                pt(wx, b.getY() + 2.25, b.getZ() - 3.75, Direction.EAST, Vec3.ZERO),
                pt(wx, b.getY() + 2.25, b.getZ() + 4.25, Direction.EAST, Vec3.ZERO)),
                new int[]{Curve.VIVID[DyeColor.RED.getId()], Curve.VIVID[DyeColor.BLUE.getId()]}, Curve.FLOW);
        double ex = b.getX() + R - 0.03;               // east wall: string lights, drooping
        line(level, b.offset(R - 1, 3, -4), Direction.WEST, Curve.BULBS, List.of(
                pt(ex, b.getY() + 3.75, b.getZ() - 3.75, Direction.WEST, Vec3.ZERO),
                pt(ex, b.getY() + 2.75, b.getZ() + 0.25, Direction.WEST, new Vec3(0, 0, 1.6)),
                pt(ex, b.getY() + 3.75, b.getZ() + 4.25, Direction.WEST, Vec3.ZERO)),
                Curve.CLASSIC, Curve.STEADY);
        double fy = b.getY() + 0.004;                  // floor, south side: a flowing rainbow
        int[] rainbow = {Curve.VIVID[DyeColor.RED.getId()], Curve.VIVID[DyeColor.ORANGE.getId()], Curve.VIVID[DyeColor.YELLOW.getId()], Curve.VIVID[DyeColor.LIME.getId()],
                Curve.VIVID[DyeColor.LIGHT_BLUE.getId()], Curve.VIVID[DyeColor.BLUE.getId()], Curve.VIVID[DyeColor.PURPLE.getId()], Curve.VIVID[DyeColor.MAGENTA.getId()]};
        line(level, b.offset(-4, 0, 7), Direction.UP, Curve.STRIP, List.of(
                pt(b.getX() - 3.75, fy, b.getZ() + 7.25, Direction.UP, new Vec3(1.5, 0, 0)),
                pt(b.getX() + 0.25, fy, b.getZ() + 5.25, Direction.UP, new Vec3(1.5, 0, 0)),
                pt(b.getX() + 4.25, fy, b.getZ() + 7.25, Direction.UP, new Vec3(1.5, 0, 0))),
                rainbow, Curve.FLOW);
    }

    private static Curve.Pt pt(double x, double y, double z, Direction f, Vec3 h) { return new Curve.Pt(new Vec3(x, y, z), f, h); }

    private static void line(ServerLevel level, BlockPos anchor, Direction face, int kind, List<Curve.Pt> pts, int[] cols, int mode) {
        level.setBlockAndUpdate(anchor, ChromaContent.ANCHOR.get().defaultBlockState().setValue(AnchorBlock.FACING, face));
        if (level.getBlockEntity(anchor) instanceof LineEntity l) l.setUp(kind, pts, cols, mode);
    }
}
