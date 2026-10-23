package com.ronald.freshpowder.client;

import com.ronald.freshpowder.FreshPowder;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

/** Swaps the snow layer models for the smooth ones. */
@Mod.EventBusSubscriber(modid = FreshPowder.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void onBake(ModelEvent.ModifyBakingResult e) {
        Map<ResourceLocation, BakedModel> models = e.getModels();
        for (BlockState s : Blocks.SNOW.getStateDefinition().getPossibleStates()) {
            if (s.getValue(SnowLayerBlock.LAYERS) >= 8) continue;
            ResourceLocation key = BlockModelShaper.stateToModelLocation(s);
            BakedModel base = models.get(key);
            if (base != null) models.put(key, new SmoothSnowModel(base));
        }
    }
}
