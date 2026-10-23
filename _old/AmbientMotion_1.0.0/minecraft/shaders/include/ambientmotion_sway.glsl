// Ambient Motion: grass, leaves and lanterns swaying in the breeze.
// Shared by the vanilla terrain shaders and (injected at load) Embeddium's.

uniform float AmbientTime;      // seconds, follows the game clock (stops when paused)
uniform float AmbientSway;      // 0 = still, 1 = full strength
uniform vec4 AmbientLantern0;   // atlas UV rect (u0, v0, u1, v1) of the lantern texture
uniform vec4 AmbientLantern1;   // ... and the soul lantern's

const float AM_TAU = 6.28318530718;

bool am_inRect(vec2 uv, vec4 r) {
    return uv.x > r.x && uv.x < r.z && uv.y > r.y && uv.y < r.w;
}

// lp: the vertex position in a frame that repeats every 16 blocks (chunk- or region-local), so every wave below
// lines up across chunk borders. vid: the vertex's index (block faces are drawn as quads, their top corners first
// and last). cutout: drawn in a cutout layer (plants and leaves - never solid ground or water).
vec3 ambient_sway(vec3 lp, vec4 color, vec2 uv, int vid, bool cutout) {
    if (AmbientSway <= 0.0 || !cutout) return vec3(0.0);
    float t = AmbientTime;
    float a = AM_TAU / 16.0;

    // lanterns: a slow pendulum from the top of their block
    if (am_inRect(uv, AmbientLantern0) || am_inRect(uv, AmbientLantern1)) {
        vec3 base = vec3(floor(lp.x), floor(lp.y - 0.001), floor(lp.z));
        float hang = clamp(base.y + 1.0 - lp.y, 0.0, 1.0);
        float ph = a * (base.x * 3.0 + base.z * 5.0 + base.y * 7.0);
        float ax = sin(t * 1.35 + ph) * 0.07;
        float az = sin(t * 1.05 + ph * 1.7 + 1.3) * 0.055;
        return vec3(ax, 0.0, az) * hang * AmbientSway;
    }

    // foliage is the biome-tinted green: grass, ferns, leaves, vines, sugar cane, lily pads
    float chroma = max(max(color.r, color.g), color.b) - min(min(color.r, color.g), color.b);
    if (chroma < 0.03 || color.g < color.r || color.g < color.b * 0.8) return vec3(0.0);

    vec3 f = abs(fract(lp + 0.5) - 0.5);
    bool cube = f.x < 0.003 && f.y < 0.003 && f.z < 0.003;     // leaves: full blocks on the grid
    int corner = vid - (vid / 4) * 4;
    float amp;
    if (cube) amp = 0.035;                                     // the canopy breathes as one
    else if (corner == 0 || corner == 3) amp = 0.08;           // tips of grass and ferns; roots stay put
    else return vec3(0.0);

    float gust = 0.55 + 0.45 * sin(t * 0.37 + a * (lp.x + lp.z * 2.0));
    float wx = sin(t * 1.9 + a * (lp.x * 2.0 + lp.z) + a * 3.0 * lp.y) + 0.45 * sin(t * 3.3 + a * (lp.z * 3.0 - lp.x));
    float wz = sin(t * 1.6 + a * (lp.z * 2.0 - lp.x) + a * 5.0 * lp.y) + 0.45 * sin(t * 2.7 + a * (lp.x * 3.0 + lp.z));
    return vec3(wx, 0.12 * wx * wz, wz) * amp * gust * AmbientSway;
}
