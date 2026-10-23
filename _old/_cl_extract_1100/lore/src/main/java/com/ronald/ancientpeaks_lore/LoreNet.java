package com.ronald.ancientpeaks_lore;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.function.Supplier;

/** Server -> client: "play this cutscene around this spot". */
public final class LoreNet {
    private LoreNet() {}
    static final String VERSION = "3";
    public static final String IN_CUTSCENE = "ancientpeaks_lore_cutscene";
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(AncientPeaksLore.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public record Cutscene(String scene, double x, double y, double z, float yaw) {
        void encode(FriendlyByteBuf b) { b.writeUtf(scene); b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeFloat(yaw); }
        static Cutscene decode(FriendlyByteBuf b) { return new Cutscene(b.readUtf(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat()); }
        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    com.ronald.ancientpeaks_lore.client.Cutscenes.start(scene, x, y, z, yaw)));
            ctx.get().setPacketHandled(true);
        }
    }

    static void register() {
        CHANNEL.messageBuilder(Cutscene.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Cutscene::encode).decoder(Cutscene::decode).consumerMainThread(Cutscene::handle).add();
        CHANNEL.messageBuilder(Compendium.Sync.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Compendium.Sync::encode).decoder(Compendium.Sync::decode).consumerMainThread(Compendium.Sync::handle).add();
    }

    /**
     * Plays a cutscene for every player within r blocks of the spot. They can't be hurt until {@link #endCutscene}.
     * Returns who is watching.
     */
    public static List<ServerPlayer> playCutscene(ServerLevel level, String scene, Vec3 at, float yaw, double r) {
        List<ServerPlayer> watching = level.getPlayers(p -> p.position().distanceToSqr(at) <= r * r && !p.isSpectator());
        for (ServerPlayer p : watching) {
            p.addTag(IN_CUTSCENE);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Cutscene(scene, at.x, at.y, at.z, yaw));
        }
        return watching;
    }

    public static void endCutscene(ServerLevel level, Vec3 at, double r) {
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(at) <= r * r) p.removeTag(IN_CUTSCENE);
    }
}
