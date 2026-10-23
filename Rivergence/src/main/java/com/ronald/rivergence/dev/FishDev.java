package com.ronald.rivergence.dev;

import com.ronald.rivergence.client.FishSchools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/** Development only (-Drivergence.fishtest=true): puts fish in front of the camera, takes screenshots, quits. */
public final class FishDev {
    private FishDev() {}

    public static void init() {
        if (!Boolean.getBoolean("rivergence.fishtest")) return;
        MinecraftForge.EVENT_BUS.addListener(FishDev::tick);
    }

    private static int t = -1, sx, sz, top, ready = -1;
    private static BlockPos pool;

    private static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        t++;
        var srv = mc.getSingleplayerServer();
        if (t == 40) {
            mc.options.hideGui = true;
            sx = Integer.getInteger("rivergence.fishX", -494); sz = Integer.getInteger("rivergence.fishZ", -2065);
            String want = System.getProperty("rivergence.fishBiome", "");
            if (!want.isEmpty()) {
                try {
                    var found = srv.submit(() -> srv.overworld().findClosestBiome3d(h -> h.unwrapKey().map(k -> k.location().getPath().equals(want)).orElse(false),
                            new BlockPos(sx, 64, sz), 3000, 32, 64)).get();
                    if (found != null) { sx = found.getFirst().getX(); sz = found.getFirst().getZ(); }
                    System.out.println("[fishdev] " + want + " at " + sx + "," + sz);
                } catch (Exception ex) { ex.printStackTrace(); }
            }
            srv.execute(() -> {
                ServerLevel l = srv.overworld();
                l.setDayTime(Boolean.getBoolean("rivergence.fishNight") ? 18000 : 6000);
                l.setWeatherParameters(6000, 0, false, false);
                for (var sp : srv.getPlayerList().getPlayers()) {
                    sp.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                    sp.teleportTo(sx + 0.5, 90, sz + 0.5);
                }
            });
        }
        if (t > 60 && t < 499 && ready < 0) {
            boolean ok = mc.level.getChunkSource().hasChunk(sx >> 4, sz >> 4) && mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz) > 0;
            if (!ok) { if (t == 498) t = 61; return; }
            ready = t; t = 499 - 200; return;           // loaded: give the renderer a while, then go on
        }
        if (t == 500) {
            top = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz) - 1;
            int fl = mc.level.getHeight(Heightmap.Types.OCEAN_FLOOR, sx, sz);
            System.out.println("[fishdev] player " + mc.player.position() + " players " + srv.getPlayerList().getPlayers().size() + " water top " + top + " floor " + fl + " fluid " + mc.level.getFluidState(new BlockPos(sx, top, sz)));
            // a little plunge pool to the side, on a platform, for the koi
            pool = new BlockPos(sx - 40, top + 12, sz);
            srv.execute(() -> {
                ServerLevel l = srv.overworld();
                for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -4; y <= 0; y++) {
                    double d = Math.hypot(x, z);
                    BlockPos p = pool.offset(x, y, z);
                    if (d < 3.6 && y > -3) l.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
                    else if (d < 3.6) l.setBlockAndUpdate(p, Blocks.GRAVEL.defaultBlockState());
                    else l.setBlockAndUpdate(p, y == 0 ? (d < 4.6 ? Blocks.MOSSY_COBBLESTONE : Blocks.GRASS_BLOCK).defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
            });
        }
        if (t == 520) {
            FishSchools.devKoi(pool.getX() + 0.5, pool.getY() - 0.5, pool.getZ() + 0.5, 1.2f);
        }
        if (t < 560) return;
        float[][] shots = {
                {sx - 2, top - 2.5f, sz, -90, 0},         // underwater, looking east at the school
                {sx - 4, top + 6, sz - 2, -70, 40},       // above, looking down into the water
                {sx + 6, top - 2f, sz, -90, 0},           // swimming in among them
                {pool.getX() + 0.5f, pool.getY() + 4, pool.getZ() - 3.5f, 0, 55}, // the koi pool
                {sx - 2, top - 2.5f, sz, -90, -10},       // jellyfish
                {sx - 2, top - 2.5f, sz, -90, 0},         // the serpent
                {sx - 2, top - 2.5f, sz, -90, 0},         // the tuna
        };
        int k = (t - 560) / 60, ph = (t - 560) % 60;
        if (k >= shots.length) { if (ph == 5) mc.stop(); return; }
        float[] s = shots[k];
        if (ph == 0) {
            srv.execute(() -> {
                for (var sp : srv.getPlayerList().getPlayers()) sp.teleportTo(srv.overworld(), s[0] + 0.5, s[1], s[2] + 0.5, s[3], s[4]);
            });
            mc.player.moveTo(s[0] + 0.5, s[1], s[2] + 0.5, s[3], s[4]);
        }
        if (ph == 8 && k < 3) FishSchools.devSchool(mc.level, sx + (k == 2 ? 6 : 6), sz, top);
        if (ph == 2 && k == 4) com.ronald.rivergence.client.SeaLife.devBloom(sx + 14, sz, top - 10, top);
        if (ph == 2 && k == 5) com.ronald.rivergence.client.SeaLife.devTuna();
        if (ph == 20 && k == 6) com.ronald.rivergence.client.SeaLife.devTuna();
        if (ph == 44 && k == 6) { Screenshot.grab(mc.gameDirectory, "fish_tuna_a.png", mc.getMainRenderTarget(), c -> {}); }
        if (ph == 28 && k == 4) System.out.println("[fishdev] " + com.ronald.rivergence.client.SeaLife.devBench());
        if (ph == 10) { com.ronald.rivergence.client.SeaLife.devNanos = 0; com.ronald.rivergence.client.SeaLife.devFrames = 0; com.ronald.rivergence.client.SeaLife.devDraw = 0; }
        if (ph == 29) System.out.println("[fishdev] shot " + k + " cam " + mc.player.position() + " " + com.ronald.rivergence.client.SeaLife.devInfo() + " fps " + mc.getFps() + String.format(" sealife %.3f ms/frame (gpu draw %.3f)", com.ronald.rivergence.client.SeaLife.devFrames == 0 ? 0 : com.ronald.rivergence.client.SeaLife.devNanos / 1e6 / com.ronald.rivergence.client.SeaLife.devFrames, com.ronald.rivergence.client.SeaLife.devFrames == 0 ? 0 : com.ronald.rivergence.client.SeaLife.devDraw / 1e6 / com.ronald.rivergence.client.SeaLife.devFrames));
        if (ph == (k == 5 ? 50 : 30)) Screenshot.grab(mc.gameDirectory, "fish_" + k + ".png", mc.getMainRenderTarget(), c -> {});
    }
}
