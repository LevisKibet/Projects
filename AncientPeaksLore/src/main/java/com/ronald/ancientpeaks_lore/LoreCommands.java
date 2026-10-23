package com.ronald.ancientpeaks_lore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;

/**
 * /aplore list [folder]     lists the structures (click one to place it)
 * /aplore place <name>      places one in front of you (no 48-block limit)
 * /aplore gallery [folder]  lays out every structure in a folder in a row along +X, with a sign-post gap between them
 * /aplore folder            shows which folder dev structures come from
 * /aplore whereis           the nearest island's castle, swords, tower and harbour (click a line to go there)
 * /aplore summit            checks the nearest island's sword in the stone and Vortigern's statue, and puts back whatever is missing
 * /aplore churches          the five churches near spawn, Merlin's first (click a line to go there)
 * /aplore quest status|reset|bless   your Excalibur quest progress; reset it, or skip straight to Merlin's blessing
 */
final class LoreCommands {
    static void register(CommandDispatcher<CommandSourceStack> d) {
        var node = d.register(Commands.literal("aplore").requires(s -> s.hasPermission(2))
                .then(Commands.literal("whereis").executes(LoreCommands::whereis))
                .then(Commands.literal("churches").executes(LoreCommands::churches))
                .then(Commands.literal("summit").executes(LoreCommands::summit))
                .then(Commands.literal("quest")
                        .then(Commands.literal("status").executes(c -> quest(c, "status")))
                        .then(Commands.literal("reset").executes(c -> quest(c, "reset")))
                        .then(Commands.literal("bless").executes(c -> quest(c, "bless"))))
                .then(Commands.literal("folder").executes(c -> {
                    var f = DevStructures.folder();
                    c.getSource().sendSuccess(() -> Component.literal(f == null ? "No dev folder: using the structures in the jar." : "Dev structures: " + f + "  (/reload after changing files)"), false);
                    return 1;
                }))
                .then(Commands.literal("list").executes(c -> list(c, ""))
                        .then(Commands.argument("folder", StringArgumentType.greedyString()).suggests((c, b) -> SharedSuggestionProvider.suggest(folders(c.getSource()), b))
                                .executes(c -> list(c, StringArgumentType.getString(c, "folder")))))
                .then(Commands.literal("place").then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(DevStructures.listIds(c.getSource().getServer(), "").stream().map(ResourceLocation::getPath), b))
                        .executes(c -> place(c, StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("gallery").executes(c -> gallery(c, ""))
                        .then(Commands.argument("folder", StringArgumentType.greedyString()).suggests((c, b) -> SharedSuggestionProvider.suggest(folders(c.getSource()), b))
                                .executes(c -> gallery(c, StringArgumentType.getString(c, "folder"))))));
        d.register(Commands.literal("logres").requires(s -> s.hasPermission(2)).redirect(node));   // the same commands as /aplore
    }

    /** Church spots, worked out the way worldgen does: the top solid block, water counting as no ground. */
    static List<int[]> chapelSpots(ServerLevel level) {
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        return LoreSite.chapelSpots(level.getSeed(), (x, z) -> {
            int land = gen.getFirstOccupiedHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
            int surf = gen.getFirstOccupiedHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, rs);
            return land == surf ? land - 1 : Integer.MIN_VALUE;
        });
    }

    private static boolean hasMerlin(ServerLevel level) {
        return LoreSite.templates(level.getStructureManager(), "churches").stream()
                .anyMatch(rl -> rl.getPath().contains("merlin") || rl.getPath().contains("besieged"));
    }

    private static int churches(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) { c.getSource().sendFailure(Component.literal("The churches are in the overworld")); return 0; }
        var spots = chapelSpots(level);
        if (spots.isEmpty()) { c.getSource().sendFailure(Component.literal("No church ruins found round spawn")); return 0; }
        boolean merlin = hasMerlin(level);
        var p = c.getSource().getPosition();
        c.getSource().sendSuccess(() -> Component.literal("Church ruins (click to go there):"), false);
        for (int[] sp : spots) {
            String name = sp[2] == 0 && merlin ? "Merlin's besieged chapel" : "Church ruin";
            tpLine(c, name, sp[0], sp[1], (int) Math.hypot(sp[0] - p.x, sp[1] - p.z));
        }
        return spots.size();
    }

    /** "  Name: x, z (d blocks)" that teleports you there when clicked. */
    private static void tpLine(CommandContext<CommandSourceStack> c, String what, int x, int z, int dist) {
        c.getSource().sendSuccess(() -> Component.literal("  " + what + ": " + x + ", " + z + "  (" + dist + " blocks)")
                .withStyle(Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                        "/tp @s " + x + " 200 " + z)).withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("Click to fill in a teleport there")))), false);
    }

    private static int quest(CommandContext<CommandSourceStack> c, String what) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p = c.getSource().getPlayerOrException();
        switch (what) {
            case "reset" -> Quest.reset(p);
            case "bless" -> Quest.set(p, Quest.BLESSED, true);
            default -> { }
        }
        StringBuilder sb = new StringBuilder("Excalibur quest for " + p.getGameProfile().getName() + ":");
        sb.append("\n  Merlin's blessing: ").append(Quest.has(p, Quest.BLESSED) ? "yes" : "no");
        sb.append("\n  Drew Excalibur: ").append(Quest.has(p, Quest.EXCALIBUR) ? "yes" : "no");
        sb.append("\n  Vortigern slain: ").append(Quest.has(p, Quest.SLAIN) ? "yes" : "no");
        String msg = sb.toString();
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int summit(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        var s = ((com.ronald.ancientpeaks.world.HighlandsAccess) (Object) level.getChunkSource().randomState()).ancientpeaks$sampler();
        if (s == null) { c.getSource().sendFailure(Component.literal("No islands in this dimension")); return 0; }
        var p = c.getSource().getPosition();
        com.ronald.ancientpeaks.world.Island best = null; double bd = 1e18;
        int sx = Math.floorDiv((int) p.x, com.ronald.ancientpeaks.world.IslandSampler.SECTOR), sz = Math.floorDiv((int) p.z, com.ronald.ancientpeaks.world.IslandSampler.SECTOR);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var is = s.islandInSector(sx + dx, sz + dz);
            if (is != null && Math.hypot(is.cx - p.x, is.cz - p.z) < bd) { bd = Math.hypot(is.cx - p.x, is.cz - p.z); best = is; }
        }
        if (best == null) { c.getSource().sendFailure(Component.literal("No island nearby")); return 0; }
        String r = SummitWatch.ensure(level, best);
        if (r == null) { int[] a = best.anchor("meadow_plateau"); c.getSource().sendFailure(Component.literal("Go nearer the summit first (" + a[0] + ", " + a[1] + "): it isn't loaded")); return 0; }
        c.getSource().sendSuccess(() -> Component.literal(r), true);
        return 1;
    }

    private static int whereis(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        var s = ((com.ronald.ancientpeaks.world.HighlandsAccess) (Object) level.getChunkSource().randomState()).ancientpeaks$sampler();
        if (s == null) { c.getSource().sendFailure(Component.literal("No islands in this dimension")); return 0; }
        var p = c.getSource().getPosition();
        com.ronald.ancientpeaks.world.Island best = null; double bd = 1e18;
        int sx = Math.floorDiv((int) p.x, com.ronald.ancientpeaks.world.IslandSampler.SECTOR), sz = Math.floorDiv((int) p.z, com.ronald.ancientpeaks.world.IslandSampler.SECTOR);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var is = s.islandInSector(sx + dx, sz + dz);
            if (is != null && Math.hypot(is.cx - p.x, is.cz - p.z) < bd) { bd = Math.hypot(is.cx - p.x, is.cz - p.z); best = is; }
        }
        if (best == null) { c.getSource().sendFailure(Component.literal("No island nearby")); return 0; }
        var mgr = level.getStructureManager();
        int[] castle = LoreSite.castleSpot(s, best, mgr), tower = LoreSite.towerSpot(s, best), dock = LoreSite.dockSpot(s, best, mgr), swords = best.anchor("meadow_plateau");
        int lots = LoreSite.villageLayout(level.getSeed(), s, best, mgr).size();
        StringBuilder sb = new StringBuilder("Island " + best.cx + ", " + best.cz + ":");
        line(sb, "Camelot", castle); sb.append("  (" + lots + " houses and fields round it)");
        line(sb, "Swords", swords); line(sb, "Merlin's tower", tower); line(sb, "Harbour", dock); line(sb, "Great arch", best.greatArch());
        best.complete();
        line(sb, "The mere (cave mouth, at the waterline)", best.caveMouth()); line(sb, "The mere's way up (by the meadow)", best.caveExit());
        String msg = sb.toString();
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        var pos = c.getSource().getPosition();
        for (var e : new Object[][]{{"Camelot", castle}, {"Swords", swords}, {"Merlin's tower", tower}, {"Harbour", dock}, {"the mere", best.caveMouth()}, {"the mere's way up", best.caveExit()}}) {
            int[] q = (int[]) e[1];
            if (q != null) tpLine(c, "Go to " + e[0], q[0], q[1], (int) Math.hypot(q[0] - pos.x, q[1] - pos.z));
        }
        churches(c);
        return 1;
    }

    private static void line(StringBuilder sb, String what, int[] p) {
        sb.append("\n  ").append(what).append(": ").append(p == null ? "none" : p[0] + ", " + p[1]);
    }

    private static List<String> folders(CommandSourceStack s) {
        return DevStructures.listIds(s.getServer(), "").stream().map(rl -> rl.getPath())
                .filter(p -> p.contains("/")).map(p -> p.substring(0, p.lastIndexOf('/'))).distinct().sorted().toList();
    }

    private static int list(CommandContext<CommandSourceStack> c, String folder) {
        var ids = DevStructures.listIds(c.getSource().getServer(), folder);
        c.getSource().sendSuccess(() -> Component.literal(ids.size() + " structures" + (folder.isEmpty() ? "" : " in " + folder) + ":"), false);
        for (var id : ids) {
            String cmd = "/aplore place " + id.getPath();
            c.getSource().sendSuccess(() -> Component.literal("  " + id.getPath()).withStyle(Style.EMPTY.withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmd))), false);
        }
        return ids.size();
    }

    private static StructureTemplate template(ServerLevel level, String name) {
        return level.getStructureManager().get(new ResourceLocation(AncientPeaksLore.MODID, name)).orElse(null);
    }

    private static int place(CommandContext<CommandSourceStack> c, String name) {
        ServerLevel level = c.getSource().getLevel();
        StructureTemplate t = template(level, name);
        if (t == null) { c.getSource().sendFailure(Component.literal("No structure " + name)); return 0; }
        BlockPos at = BlockPos.containing(c.getSource().getPosition()).offset(2, -1, 2);
        t.placeInWorld(level, at, at, new StructurePlaceSettings(), level.random, 2);
        var size = t.getSize();
        c.getSource().sendSuccess(() -> Component.literal("Placed " + name + " (" + size.getX() + "x" + size.getY() + "x" + size.getZ() + ") at " + at.toShortString()), true);
        return 1;
    }

    private static int gallery(CommandContext<CommandSourceStack> c, String folder) {
        ServerLevel level = c.getSource().getLevel();
        var ids = DevStructures.listIds(c.getSource().getServer(), folder);
        BlockPos at = BlockPos.containing(c.getSource().getPosition()).offset(4, -1, 4);
        int x = 0, n = 0;
        for (var id : ids) {
            StructureTemplate t = level.getStructureManager().get(id).orElse(null);
            if (t == null) continue;
            BlockPos p = at.offset(x, 0, 0);
            t.placeInWorld(level, p, p, new StructurePlaceSettings(), level.random, 2);
            // a sign with the name in front of each one
            BlockPos sp = p.offset(0, 1, -2);
            level.setBlock(sp, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState(), 2);
            if (level.getBlockEntity(sp) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                String path = id.getPath();
                String shortName = path.substring(path.lastIndexOf('/') + 1);
                sign.setText(sign.getFrontText().setMessage(0, Component.literal(shortName.length() > 15 ? shortName.substring(0, 15) : shortName))
                        .setMessage(1, Component.literal(shortName.length() > 15 ? shortName.substring(15, Math.min(30, shortName.length())) : "")), true);
            }
            x += t.getSize().getX() + 6; n++;
        }
        int count = n;
        c.getSource().sendSuccess(() -> Component.literal("Laid out " + count + " structures along +X from " + at.toShortString()), true);
        return n;
    }
}
