package com.ronald.rivergence;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.ronald.rivergence.plan.Planner;
import com.ronald.rivergence.plan.RegionPlan;
import com.ronald.rivergence.world.RiverAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * /rivergence find <feature>: the nearest springs, waterfalls, lakes, deltas, wetlands, oases, beaver dams or old
 * reservoir dams round you, from the water plan of the region you're in and any neighbouring regions already planned.
 * Each result's coordinates can be clicked to teleport there (like /locate).
 */
public final class RivergenceCommands {
    private RivergenceCommands() {}

    static final String[] KINDS = {"spring", "waterfall", "lake", "delta", "wetland", "oasis", "beaver_dam", "old_dam", "bay"};

    public static void init() { MinecraftForge.EVENT_BUS.addListener(RivergenceCommands::register); }

    private static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("rivergence").requires(s -> s.hasPermission(2))
                .then(Commands.literal("find")
                        .then(Commands.argument("feature", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(KINDS, b))
                                .executes(RivergenceCommands::find))));
    }

    private static int find(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String kind = StringArgumentType.getString(ctx, "feature");
        boolean known = false;
        for (String k : KINDS) if (k.equals(kind)) known = true;
        if (!known) { src.sendFailure(Component.literal("Unknown feature. Try one of: " + String.join(", ", KINDS))); return 0; }
        ServerLevel level = src.getLevel();
        Planner planner = ((RiverAccess) (Object) level.getChunkSource().randomState()).rivergence$planner();
        if (planner == null) { src.sendFailure(Component.literal("Rivergence isn't shaping the water in this dimension.")); return 0; }
        Vec3 at = src.getPosition();
        int px = (int) Math.floor(at.x), pz = (int) Math.floor(at.z);

        // this region (waited for: it's planned already wherever you can stand) and the neighbours that are ready
        List<RegionPlan> plans = new ArrayList<>();
        RegionPlan here = planner.region(px, pz, true);
        if (here != null) plans.add(here);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            RegionPlan p = planner.region(px + dx * RegionPlan.SIZE, pz + dz * RegionPlan.SIZE, false);
            if (p != null) plans.add(p);
        }

        List<int[]> found = new ArrayList<>();                         // x, y, z
        for (RegionPlan p : plans) collect(p, kind, found);
        // nearest first, without the duplicates neighbouring plans share
        found.sort((a, b) -> Double.compare(dist2(a, px, pz), dist2(b, px, pz)));
        List<int[]> shown = new ArrayList<>();
        for (int[] f : found) {
            boolean dup = false;
            for (int[] s : shown) if (Math.abs(s[0] - f[0]) < 64 && Math.abs(s[2] - f[2]) < 64) dup = true;
            if (!dup) shown.add(f);
            if (shown.size() == 5) break;
        }
        String name = kind.replace('_', ' ');
        if (shown.isEmpty()) {
            src.sendFailure(Component.literal("No " + name + " found within the planned area round you (about 2000 blocks). Try exploring further and asking again."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Nearest " + (shown.size() > 1 ? (name.equals("oasis") ? "oases" : name + "s") : name) + ":").withStyle(ChatFormatting.AQUA), false);
        for (int[] f : shown) {
            int dist = (int) Math.round(Math.sqrt(dist2(f, px, pz)));
            MutableComponent coords = Component.literal("[" + f[0] + ", " + f[1] + ", " + f[2] + "]").withStyle(s -> s
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + f[0] + " " + f[1] + " " + f[2]))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to teleport"))));
            src.sendSuccess(() -> Component.literal("  ").append(coords).append(Component.literal(" (" + dist + " blocks away)")), false);
        }
        return shown.size();
    }

    private static double dist2(int[] f, int x, int z) { double dx = f[0] - x, dz = f[2] - z; return dx * dx + dz * dz; }

    private static void collect(RegionPlan p, String kind, List<int[]> out) {
        switch (kind) {
            case "spring" -> { for (var s : p.springs) out.add(new int[]{(int) s.x, s.level + 2, (int) s.z}); }
            case "waterfall" -> { for (var w : p.pools) out.add(new int[]{(int) w.x, w.level + 2, (int) w.z}); }
            case "delta" -> { for (var d : p.deltas) out.add(new int[]{(int) (d.x + d.dx * d.r * 0.5), 66, (int) (d.z + d.dz * d.r * 0.5)}); }
            case "bay" -> { for (var b : p.bays) out.add(new int[]{(int) b.x, 64, (int) b.z}); }
            case "oasis" -> { for (var o : p.oases) out.add(new int[]{(int) o.x, o.level + 2, (int) o.z}); }
            case "beaver_dam", "old_dam" -> {
                boolean old = kind.equals("old_dam");
                for (var w : p.walls) if (w.ancient == old) out.add(new int[]{(int) w.nx, w.top + 2, (int) w.nz});
            }
            case "wetland" -> {
                for (int[] c : p.wetlandCells()) {
                    double h = p.heightAt(c[0], c[1]);
                    out.add(new int[]{c[0], (int) (Double.isNaN(h) ? 70 : Math.max(64, h + 2)), c[1]});
                }
            }
            case "lake" -> {
                for (var l : p.lakes) {
                    int best = 0;
                    for (int i = 1; i < l.sd.length; i++) if (l.sd[i] > l.sd[best]) best = i;
                    out.add(new int[]{l.gx0 + (best % l.w) * l.step, l.level + 2, l.gz0 + (best / l.w) * l.step});
                }
            }
            default -> { }
        }
    }
}
