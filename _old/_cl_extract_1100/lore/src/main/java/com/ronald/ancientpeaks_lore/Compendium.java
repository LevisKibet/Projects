package com.ronald.ancientpeaks_lore;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The compendium: every lore book a player reads (opens in hand, or on a lectern) is written into their own record,
 * kept with the player for good (it survives death, and losing the compendium itself). The compendium item shows it.
 * Read them all and the "Exposition" advancement is yours.
 */
public final class Compendium {
    private Compendium() {}
    static final String KEY = "ancientpeaks_lore_read";
    static final ResourceLocation EXPOSITION = new ResourceLocation(AncientPeaksLore.MODID, "exposition");

    public static int total() { return LoreBooks.ALL.size(); }

    public static int[] read(Player p) {
        return p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getIntArray(KEY);
    }

    /** Which lore book is this (0 if none): by its id, or (books from before it had one) by title and author. */
    public static int idOf(ItemStack st) {
        if (!st.is(Items.WRITTEN_BOOK) || !st.hasTag()) return 0;
        CompoundTag t = st.getTag();
        if (t.contains("LoreId")) return t.getInt("LoreId");
        String title = t.getString("title"), author = t.getString("author");
        for (LoreBooks.Book b : LoreBooks.ALL) if (b.title().equals(title) && b.author().equals(author)) return b.n();
        return 0;
    }

    static void record(ServerPlayer p, int id) {
        if (id <= 0 || id > total()) return;
        int[] had = read(p);
        for (int h : had) if (h == id) return;
        int[] now = Arrays.copyOf(had, had.length + 1);
        now[had.length] = id;
        Arrays.sort(now);
        CompoundTag persisted = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putIntArray(KEY, now);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        // the client shows a "Compendium updated" toast and plays the enchanting sound
        LoreNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Sync(now, id));
        if (now.length >= total()) {
            var adv = p.server.getAdvancements().getAdvancement(EXPOSITION);
            if (adv != null) p.getAdvancements().award(adv, "read_all");
        }
    }

    static void sync(ServerPlayer p) {
        LoreNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Sync(read(p), 0));
    }

    /** Server -> client: the player's whole record, and the book just added to it (0 for none). */
    public record Sync(int[] ids, int added) {
        void encode(FriendlyByteBuf b) { b.writeVarIntArray(ids); b.writeVarInt(added); }
        static Sync decode(FriendlyByteBuf b) { return new Sync(b.readVarIntArray(), b.readVarInt()); }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                com.ronald.ancientpeaks_lore.client.CompendiumScreen.known(ids);
                if (added > 0) com.ronald.ancientpeaks_lore.client.CompendiumToast.show(added, ids.length);
            }));
            ctx.get().setPacketHandled(true);
        }
    }

    static void register() {
        // opening a lore book in hand
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem e) -> {
            if (e.getEntity() instanceof ServerPlayer p) record(p, idOf(e.getItemStack()));
        });
        // reading one on a lectern
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            if (p.level().getBlockEntity(e.getPos()) instanceof LecternBlockEntity le && le.hasBook()) record(p, idOf(le.getBook()));
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> { if (e.getEntity() instanceof ServerPlayer p) sync(p); });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> { if (e.getEntity() instanceof ServerPlayer p) sync(p); });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> { if (e.getEntity() instanceof ServerPlayer p) sync(p); });
    }
}
