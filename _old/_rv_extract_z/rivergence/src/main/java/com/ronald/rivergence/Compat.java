package com.ronald.rivergence;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** Other mods Rivergence works alongside: what's installed, and what of theirs it uses or steps round. */
public final class Compat {
    private Compat() {}

    private static Boolean tectonic, terralith, farmersDelight, streams;
    private static Block wildRice;

    private static boolean loaded(String id) { return ModList.get() != null && ModList.get().isLoaded(id); }

    public static boolean tectonic() { if (tectonic == null) tectonic = loaded("tectonic"); return tectonic; }
    public static boolean terralith() { if (terralith == null) terralith = loaded("terralith"); return terralith; }
    public static boolean farmersDelight() { if (farmersDelight == null) farmersDelight = loaded("farmersdelight"); return farmersDelight; }
    public static boolean streams() { if (streams == null) streams = loaded("streams"); return streams; }

    /** Farmer's Delight's wild rice, or null without it. */
    public static Block wildRice() {
        if (!farmersDelight()) return null;
        if (wildRice == null) {
            Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("farmersdelight", "wild_rice"));
            wildRice = b == null ? Blocks.AIR : b;
        }
        return wildRice == Blocks.AIR ? null : wildRice;
    }

    /** Said once at start: what was found, and anything worth knowing about it. */
    public static void report() {
        if (tectonic()) Rivergence.LOGGER.info("[Rivergence] Tectonic found: bays are off (Tectonic shapes the coasts). If Tectonic's own rivers are on, consider turning them off in its config so the two don't both cut rivers.");
        if (terralith()) Rivergence.LOGGER.info("[Rivergence] Terralith found: its biomes get their own banks, beds and plants.");
        if (farmersDelight()) Rivergence.LOGGER.info("[Rivergence] Farmer's Delight found: wild rice grows in shallow, still water.");
        if (streams()) Rivergence.LOGGER.warn("[Rivergence] Streams is installed as well: both mods shape running water, so expect them to fight. Use one or the other.");
    }
}
