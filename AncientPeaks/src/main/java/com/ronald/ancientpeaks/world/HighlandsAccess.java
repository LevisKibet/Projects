package com.ronald.ancientpeaks.world;

import org.jetbrains.annotations.Nullable;

/** Duck interface mixed into RandomState and Climate.Sampler so other code can reach the seeded island maths. */
public interface HighlandsAccess {
    @Nullable IslandSampler ancientpeaks$sampler();
    void ancientpeaks$set(IslandSampler sampler);
}
