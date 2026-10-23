package com.ronald.ancientpeaks_lore.light;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Gives Luxium's shadow-casting lights our colours. Called from a mixin that only exists when Luxium is installed. */
public final class LuxiumColours {
    private LuxiumColours() {}

    private static final BlockPos.MutableBlockPos NEXT = new BlockPos.MutableBlockPos();

    public static void recolour(ClientLevel level, long packed, int light, float[] out, int at) {
        try {
            if (level == null || out == null || at < 0 || at + 2 >= out.length) return;
            if (!ChromaLight.ACTIVE || !Settings.enabled() || !Settings.blocks()) return;
            BlockPos pos = BlockPos.of(packed);
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) return;
            LightColors.Tone t = LightColors.of(s);
            float r = t.r(), g = t.g(), b = t.b();

            // a light boxed in by stained glass shines out in the glass's colour, and so do its shadows
            int open = 0, tinted = 0;
            float fr = 0, fg = 0, fb = 0;
            for (Direction d : Direction.values()) {
                BlockState n = level.getBlockState(NEXT.setWithOffset(pos, d));
                if (n.isAir()) { open++; continue; }
                int f = LightColors.filter(n);
                if (f != 0xFFFFFF) { tinted++; fr += (f & 255) / 255f; fg += (f >> 8 & 255) / 255f; fb += (f >> 16 & 255) / 255f; }
            }
            if (open == 0 && tinted > 0) { r *= fr / tinted; g *= fg / tinted; b *= fb / tinted; }

            float mx = Math.max(r, Math.max(g, b));
            if (mx <= 0.001f) return;
            // the same brightness Luxium gave it, in our colour
            float k = 1.18f * (float) Math.pow(Math.max(0f, Math.min(1f, light / 15f)), 0.78);
            out[at] = r / mx * k;
            out[at + 1] = g / mx * k;
            out[at + 2] = b / mx * k;
        } catch (Throwable ignored) {
            // never take Luxium's renderer down with us; it keeps its own colour instead
        }
    }
}
