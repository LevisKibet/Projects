package com.ronald.rivergence.ripple.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the active ripple fields and routes disturbances into them. A new disturbance close to an
 * existing field goes into that same field, so the waves overlap and interfere.
 */
public final class RippleWorld {
    private static final double INTERIOR_MARGIN = 2.0;

    private final List<RippleField> fields = new ArrayList<>();
    private final int maxFields;
    private final RippleField.Terrain terrain;

    public RippleWorld(int maxFields, RippleField.Terrain terrain) {
        this.maxFields = maxFields;
        this.terrain = terrain;
    }

    public List<RippleField> fields() { return fields; }

    public void clear() { fields.clear(); }

    private RippleField route(double x, double y, double z) {
        for (RippleField f : fields) {
            if (Math.abs(f.surfaceY - y) < 0.2 && f.isInterior(x, z, INTERIOR_MARGIN)) {
                return f;
            }
        }
        if (fields.size() >= maxFields) {
            // Drop the oldest field to make room.
            int oldest = 0;
            for (int k = 1; k < fields.size(); k++) {
                if (fields.get(k).age > fields.get(oldest).age) oldest = k;
            }
            fields.remove(oldest);
        }
        RippleField f = new RippleField(x, z, y);
        if (terrain != null) f.refreshWet(terrain);
        fields.add(f);
        return f;
    }

    /** Something landing in the water. {@code size} is roughly the object's width in blocks. */
    public void splash(double x, double y, double z, float size, float speed) {
        RippleField f = route(x, y, z);
        float strength = Math.min(1.6F, 0.35F + speed * 1.6F) * (0.6F + size * 0.6F);
        float sigma = 0.14F + Math.min(size, 1.5F) * 0.08F;
        f.droplet(x, z, 0.09F * strength, sigma, 7F, 20F, 46);
    }

    /** A small, gentle disturbance, such as a waterfall landing or a paddle. */
    public void drip(double x, double y, double z, float strength) {
        RippleField f = route(x, y, z);
        f.droplet(x, z, 0.035F * strength, 0.14F, 7F, 12F, 28);
    }

    /**
     * Something moving through the water. Every sample along the path pushes the surface;
     * moving faster than the waves travel leaves a V-shaped wake behind.
     */
    public void wake(double fromX, double fromZ, double toX, double toZ, double y, float width, float strength) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist / 0.12));
        float sigma = Math.max(0.13F, width * 0.3F);
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            double x = fromX + dx * t;
            double z = fromZ + dz * t;
            RippleField f = route(x, y, z);
            f.impulse(x, z, strength * 0.0055F * (float) (dist / steps / 0.12), sigma);
        }
    }

    public void tick() {
        for (int k = fields.size() - 1; k >= 0; k--) {
            RippleField f = fields.get(k);
            f.step();
            if (terrain != null && f.age % 20 == 0) f.refreshWet(terrain);
            if (f.isDead()) fields.remove(k);
        }
    }
}
