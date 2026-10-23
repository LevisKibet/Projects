package com.ronald.rivergence.world;

import com.ronald.rivergence.plan.Planner;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.jetbrains.annotations.Nullable;

/** Mixed into RandomState: the world's water planner, and the terrain with the channels carved in. */
public interface RiverAccess {
    @Nullable Planner rivergence$planner();
    /** The terrain functions with the water's channels carved in (the original router when there's no planner). */
    NoiseRouter rivergence$router();
}
