package com.ronald.ancientpeaks_lore;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A player's progress through the Excalibur quest, kept with the player (survives death):
 *   blessed    healed Merlin; the sword in the stone will move for them
 *   excalibur  drew the sword (and woke Vortigern)
 *   slain      was there when Vortigern fell
 */
public final class Quest {
    private Quest() {}
    public static final String BLESSED = "blessed", EXCALIBUR = "excalibur", SLAIN = "slain";
    public static final String[] STEPS = {BLESSED, EXCALIBUR, SLAIN};

    private static CompoundTag data(Player p) {
        CompoundTag root = p.getPersistentData();
        CompoundTag kept = root.getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag q = kept.getCompound(AncientPeaksLore.MODID);
        kept.put(AncientPeaksLore.MODID, q);
        root.put(Player.PERSISTED_NBT_TAG, kept);
        return q;
    }

    public static boolean has(Player p, String step) { return data(p).getBoolean(step); }
    public static void set(Player p, String step, boolean on) { if (on) data(p).putBoolean(step, true); else data(p).remove(step); }
    public static void reset(Player p) { for (String s : STEPS) set(p, s, false); }

    /** A line of dialogue to everyone within r blocks: "<Merlin> ..." */
    public static void say(ServerLevel level, Vec3 at, double r, String who, ChatFormatting colour, String line) {
        Component msg = Component.literal("<" + who + "> ").withStyle(colour).append(Component.literal(line).withStyle(ChatFormatting.ITALIC, ChatFormatting.WHITE));
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(at) <= r * r) p.sendSystemMessage(msg);
    }

    /** A grey hint above the hotbar. */
    public static void hint(Player p, String text) {
        p.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
    }
}
