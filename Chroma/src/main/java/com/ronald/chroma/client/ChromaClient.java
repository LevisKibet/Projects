package com.ronald.chroma.client;

import com.ronald.chroma.ChromaNet;
import com.ronald.chroma.content.ChromaContent;
import com.ronald.chroma.content.LineItem;
import com.ronald.chroma.content.ShaperItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * The client half: draws lines, and turns clicks into edits. A click that lands on a line (looked at more closely than
 * any block behind it) is taken by the line; otherwise it goes on as normal.
 */
public final class ChromaClient {
    private ChromaClient() {}

    /** What the player is looking at on a line right now (refreshed every tick while holding something that uses it). */
    static volatile LinePick.Hit lastHit;

    public static void init(IEventBus modBus) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(ChromaContent.LINE.get(), LineRenderer::new));
        MinecraftForge.EVENT_BUS.addListener(ChromaClient::tick);
        ChromaDev.init();
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.RenderLevelStageEvent e) -> {
            if (e.getStage() == net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) LightSplash.newFrame();
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.level.LevelEvent.Unload e) -> { if (e.getLevel().isClientSide()) BlockChanges.clear(); });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.level.ChunkEvent.Load e) -> {
            if (e.getLevel().isClientSide() && e.getLevel() instanceof net.minecraft.world.level.Level l)
                BlockChanges.chunkLoaded(e.getChunk().getPos().x, e.getChunk().getPos().z, l.getMinSection(), l.getMaxSection() - 1);
        });
        MinecraftForge.EVENT_BUS.addListener(ChromaClient::rightClickBlock);
        MinecraftForge.EVENT_BUS.addListener(ChromaClient::rightClickItem);
        MinecraftForge.EVENT_BUS.addListener(ChromaClient::leftClickBlock);
        MinecraftForge.EVENT_BUS.addListener(ChromaClient::leftClickEmpty);
    }

    private static boolean uses(Item i) { return i instanceof ShaperItem || i instanceof LineItem || i instanceof DyeItem || i == net.minecraft.world.item.Items.AMETHYST_SHARD; }

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        lastHit = mc.player != null && uses(mc.player.getMainHandItem().getItem()) ? LinePick.pick() : null;
    }

    private static boolean sneaking() { return Minecraft.getInstance().player.isShiftKeyDown(); }

    private static void send(int action, BlockPos pos, int index, Vec3 hit, int face) {
        ChromaNet.send(new ChromaNet.Edit(action, pos, index, hit, face, sneaking()));
        Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND);
    }

    /** A right-click on a line with the shaper or a dye. True if it was taken. */
    private static boolean rightOnLine(Item item) {
        LinePick.Hit h = LinePick.pick();
        if (h == null) return false;
        BlockPos at = h.line().getBlockPos();
        if (item instanceof ShaperItem) {
            if (h.type() == LinePick.BODY) send(sneaking() ? ChromaNet.SPEED : ChromaNet.MODE, at, -1, h.at(), 0);
            else send(ChromaNet.GROW, at, h.index(), h.at(), 0);
            return true;
        }
        if (item instanceof DyeItem) { send(ChromaNet.DYE, at, -1, h.at(), 0); return true; }
        if (item == net.minecraft.world.item.Items.AMETHYST_SHARD) { send(ChromaNet.RAINBOW, at, -1, h.at(), 0); return true; }
        if (item instanceof LineItem li && !sneaking() && h.type() == LinePick.POINT && h.line().kind() == li.kind
                && (h.index() == 0 || h.index() == h.line().points().size() - 1)) {
            send(ChromaNet.RESUME, at, h.index(), h.at(), 0);
            return true;
        }
        return false;
    }

    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!e.getLevel().isClientSide || e.getHand() != InteractionHand.MAIN_HAND) return;
        Item item = e.getItemStack().getItem();
        if (!uses(item)) return;
        boolean taken = rightOnLine(item);
        if (!taken && item instanceof LineItem) {
            send(ChromaNet.PLACE, e.getPos(), -1, e.getHitVec().getLocation(), e.getFace().get3DDataValue());
            taken = true;
        }
        if (taken) { e.setCanceled(true); e.setCancellationResult(InteractionResult.SUCCESS); }
    }

    private static void rightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (!e.getLevel().isClientSide || e.getHand() != InteractionHand.MAIN_HAND) return;
        Item item = e.getItemStack().getItem();
        if (!uses(item)) return;
        boolean taken = rightOnLine(item);
        if (!taken && item instanceof LineItem && sneaking()) { send(ChromaNet.FINISH, BlockPos.ZERO, -1, Vec3.ZERO, 0); taken = true; }
        if (taken) { e.setCanceled(true); e.setCancellationResult(InteractionResult.SUCCESS); }
    }

    private static long lastLeft;

    /** Left-click with the shaper on a point or handle: turn it, or (sneaking) take the point out. */
    private static boolean leftOnLine() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player.getMainHandItem().getItem() instanceof ShaperItem)) return false;
        LinePick.Hit h = LinePick.pick();
        if (h == null || h.type() == LinePick.BODY) return false;
        long now = mc.level.getGameTime();
        if (now - lastLeft >= 4) {
            lastLeft = now;
            send(sneaking() ? ChromaNet.REMOVE : ChromaNet.TURN, h.line().getBlockPos(), h.index(), h.at(), 0);
        }
        return true;
    }

    private static void leftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (!e.getLevel().isClientSide) return;
        if (leftOnLine()) e.setCanceled(true);
    }

    private static void leftClickEmpty(PlayerInteractEvent.LeftClickEmpty e) { leftOnLine(); }
}
