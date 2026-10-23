package com.ronald.freshpowder.mixin;

import com.ronald.freshpowder.Config;
import com.ronald.freshpowder.world.Snow;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While it snows, now and then a random column of each loaded chunk: its drift grows, snow sifts through its trees. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "tickChunk", at = @At("TAIL"))
    private void freshpowder$snowfall(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!self.isRaining() || !Config.SNOWFALL.get() || self.random.nextInt(16) != 0) return;
        ChunkPos cp = chunk.getPos();
        BlockPos top = self.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, self.getBlockRandomPos(cp.getMinBlockX(), 0, cp.getMinBlockZ(), 15));
        Snow.fall(self, top, self.random);
    }
}
