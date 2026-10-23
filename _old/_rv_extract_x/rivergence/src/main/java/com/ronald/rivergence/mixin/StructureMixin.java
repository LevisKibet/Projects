package com.ronald.rivergence.mixin;

import com.ronald.rivergence.RivergenceConfig;
import com.ronald.rivergence.plan.Planner;
import com.ronald.rivergence.world.RiverAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Surface structures (villages, outposts, temples, huts) aren't started on top of a river or lake: as in vanilla,
 * where villages don't start in rivers, they're built elsewhere. Their outskirts may still reach the water.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {
    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void rivergence$offTheWater(Structure.GenerationContext ctx, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub.isEmpty() || !RivergenceConfig.structuresAvoidWater()) return;
        Structure self = (Structure) (Object) this;
        if (self.step() != GenerationStep.Decoration.SURFACE_STRUCTURES) return;
        Planner p = ((RiverAccess) (Object) ctx.randomState()).rivergence$planner();
        if (p == null) return;
        var pos = stub.get().position();
        int r = self instanceof JigsawStructure ? 40 : 16;
        boolean wait = com.ronald.rivergence.world.CarveDensity.STARTING.get();
        Planner.Column c = new Planner.Column();
        for (int dx = -r; dx <= r; dx += 4) for (int dz = -r; dz <= r; dz += 4) {
            if (dx * dx + dz * dz > r * r) continue;
            p.column(pos.getX() + dx, pos.getZ() + dz, c, wait);
            if (c.plan == null) return;                                   // not planned yet and not worth waiting for
            if (c.wet() && !c.canal && pos.getY() > c.water - 12) { cir.setReturnValue(Optional.empty()); return; }
        }
    }
}
