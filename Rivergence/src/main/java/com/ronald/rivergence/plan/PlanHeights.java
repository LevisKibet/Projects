package com.ronald.rivergence.plan;

/**
 * Heights for planning: the quick estimate everywhere, and the exact surface wherever the land is near sea level,
 * since there a few blocks decide whether a cell is shore or sea (and the estimate runs a few blocks low).
 */
public final class PlanHeights implements Drainage.Heights {
    private final Drainage.Heights fast, exact;
    public int exactCalls;

    public PlanHeights(Drainage.Heights fast, Drainage.Heights exact) { this.fast = fast; this.exact = exact; }

    @Override
    public double at(int x, int z) {
        double h = fast.at(x, z);
        if (h > 50 && h < 74) { exactCalls++; return exact.at(x, z); }
        return h;
    }
}
