package com.ronald.rivergence.client;

import com.ronald.rivergence.Rivergence;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small model read from a Wavefront .obj (with its .mtl) in the mod's assets, made in Blender or Blockbench: faces
 * grouped by part name, each with its material's colour and opacity. Drawn by SeaLife, which bends and moves the
 * parts by name.
 */
final class SeaModel {
    /** One face: four corners (a triangle repeats its last), its part and material, colour 0..255, opacity 0..1, and how lit it is from above. */
    static final class Face {
        final float[] x = new float[4], y = new float[4], z = new float[4];
        String part, mat;
        int r, g, b;
        float alpha = 1, shade = 1;
        /** Worked out once: hangs below (threads, arms), part of the bell, its colour role (0 body, 1 side, 2 glow), a number from its name. */
        boolean hang, bell;
        int role, idx;
        /** For hanging parts: how far below the bell's bottom they reach. */
        float len = 1;
        /** Every other face of the hanging parts (the second ribbon of each pair), left out at middle distance. */
        boolean alt;
    }

    final List<Face> faces = new ArrayList<>();
    float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    final Map<String, float[]> partY = new HashMap<>();       // each part's lowest and highest y

    private static final Map<String, SeaModel> CACHE = new HashMap<>();

    static SeaModel get(String name) {
        return CACHE.computeIfAbsent(name, SeaModel::load);
    }

    static void clear() { CACHE.clear(); }

    private static SeaModel load(String name) {
        SeaModel m = new SeaModel();
        try {
            var rm = Minecraft.getInstance().getResourceManager();
            Map<String, float[]> mats = new HashMap<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(rm.open(new ResourceLocation(Rivergence.MODID, "models/sea/" + name + ".mtl")), StandardCharsets.UTF_8))) {
                String cur = null, line;
                while ((line = in.readLine()) != null) {
                    String[] s = line.trim().split("\\s+");
                    if (s.length == 0) continue;
                    if (s[0].equals("newmtl")) { cur = s[1]; mats.put(cur, new float[]{1, 1, 1, 1}); }
                    else if (cur != null && s[0].equals("Kd")) { float[] c = mats.get(cur); c[0] = Float.parseFloat(s[1]); c[1] = Float.parseFloat(s[2]); c[2] = Float.parseFloat(s[3]); }
                    else if (cur != null && s[0].equals("d")) mats.get(cur)[3] = Float.parseFloat(s[1]);
                }
            }
            List<float[]> v = new ArrayList<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(rm.open(new ResourceLocation(Rivergence.MODID, "models/sea/" + name + ".obj")), StandardCharsets.UTF_8))) {
                String part = "", mat = "", line;
                while ((line = in.readLine()) != null) {
                    String[] s = line.trim().split("\\s+");
                    if (s.length == 0 || s[0].isEmpty()) continue;
                    switch (s[0]) {
                        case "v" -> v.add(new float[]{Float.parseFloat(s[1]), Float.parseFloat(s[2]), Float.parseFloat(s[3])});
                        case "o", "g" -> part = s.length > 1 ? s[1].replaceAll("\\.\\d+$", "") : "";
                        case "usemtl" -> mat = s[1];
                        case "f" -> {
                            int n = s.length - 1;
                            int[] idx = new int[n];
                            for (int i = 0; i < n; i++) { int k = Integer.parseInt(s[i + 1].split("/")[0]); idx[i] = k < 0 ? v.size() + k : k - 1; }
                            // fans of quads for anything bigger than a quad
                            for (int start = 0; start + 2 < n; start += 2) {
                                Face f = new Face();
                                int[] q = {idx[0], idx[start + 1], idx[start + 2], idx[Math.min(n - 1, start + 3)]};
                                for (int c = 0; c < 4; c++) { float[] p = v.get(q[c]); f.x[c] = p[0]; f.y[c] = p[1]; f.z[c] = p[2]; }
                                f.part = part; f.mat = mat;
                                f.hang = part.startsWith("thread") || part.startsWith("arm");
                                f.bell = part.startsWith("bell");
                                f.role = mat.equals("bell") || mat.equals("threads") ? 0 : mat.equals("bell_side") ? 1 : 2;
                                f.idx = part.isEmpty() ? 0 : Math.max(0, part.charAt(part.length() - 1) - '0');
                                float[] c = mats.getOrDefault(mat, new float[]{1, 1, 1, 1});
                                f.r = (int) (c[0] * 255); f.g = (int) (c[1] * 255); f.b = (int) (c[2] * 255); f.alpha = c[3];
                                // a little shading from its facing: tops bright, sides less, bottoms least
                                float ax = f.x[1] - f.x[0], ay = f.y[1] - f.y[0], az = f.z[1] - f.z[0], bx = f.x[2] - f.x[0], by = f.y[2] - f.y[0], bz = f.z[2] - f.z[0];
                                float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx, nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                                f.shade = nl < 1e-9f ? 1 : 0.8f + 0.2f * Math.abs(ny / nl);
                                m.faces.add(f);
                                if (n <= 4) break;
                            }
                        }
                        default -> { }
                    }
                }
            }
            for (Face f : m.faces) for (int c = 0; c < 4; c++) {
                m.minX = Math.min(m.minX, f.x[c]); m.maxX = Math.max(m.maxX, f.x[c]);
                m.minY = Math.min(m.minY, f.y[c]); m.maxY = Math.max(m.maxY, f.y[c]);
                float[] py = m.partY.computeIfAbsent(f.part, k -> new float[]{Float.MAX_VALUE, -Float.MAX_VALUE});
                py[0] = Math.min(py[0], f.y[c]); py[1] = Math.max(py[1], f.y[c]);
            }
            float[] bs = m.partY.get("bell_sides");
            float bottom = bs == null ? -0.15f : bs[0];
            int hk = 0;
            for (Face f : m.faces) if (f.hang) f.alt = (hk++ & 1) == 1;
            for (Face f : m.faces) if (f.hang) { float[] py = m.partY.get(f.part); f.len = py == null ? 1 : Math.max(0.05f, bottom - py[0]); }
            Rivergence.LOGGER.debug("Rivergence: model {} - {} faces", name, m.faces.size());
        } catch (Exception ex) {
            Rivergence.LOGGER.warn("Rivergence: couldn't read model {}", name, ex);
        }
        return m;
    }
}
