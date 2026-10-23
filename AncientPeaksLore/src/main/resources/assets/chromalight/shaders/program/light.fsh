#version 150

// Chroma Light: colours the light the world is already lit with.
//   The light volume (VolumeSampler) holds, for every block round the player, the coloured block light that has
//   spread there (rgb) and how much sky light reaches it (a). Like vanilla, whichever is stronger wins: under open
//   daytime sky the colour fades out, in caves, buildings and at night it shows.
//   Moving lights (held torches, burning mobs...) come as points and follow the same rule.

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform sampler2D LightSampler;    // 64 x 2: row 0 = camera-relative position + radius, row 1 = colour + light level
uniform sampler2D VolumeSampler;   // the light volume, slices side by side
uniform mat4 InvViewProj;
uniform float LightCount;
uniform float Strength;
uniform float HasVolume;
uniform vec3 VolOffset;            // camera position minus the volume's corner
uniform vec3 VolSize;
uniform vec2 AtlasSize;
uniform float AtlasCols;
uniform float SkyDarken;           // 0 at noon, 11 at midnight (vanilla's own number)

in vec2 texCoord;
out vec4 fragColor;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec4 slice(float s, vec2 xz) {
    vec2 tile = vec2(mod(s, AtlasCols), floor(s / AtlasCols));
    return texture(VolumeSampler, (tile * VolSize.xz + xz) / AtlasSize);
}

vec4 volume(vec3 v) {
    if (any(lessThan(v, vec3(0.0))) || any(greaterThan(v, VolSize))) return vec4(0.0, 0.0, 0.0, 1.0);
    vec2 xz = clamp(v.xz, vec2(0.5), VolSize.xz - 0.5);
    float fy = clamp(v.y - 0.5, 0.0, VolSize.y - 1.0);
    float y0 = floor(fy);
    float y1 = min(y0 + 1.0, VolSize.y - 1.0);
    return mix(slice(y0, xz), slice(y1, xz), fy - y0);
}

void main() {
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    float d = texture(DepthSampler, texCoord).r;
    if (d >= 1.0) { fragColor = vec4(scene, 1.0); return; }          // sky
    vec4 p = InvViewProj * vec4(texCoord * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec3 P = p.xyz / p.w;                                              // camera-relative position of this surface
    vec3 N = cross(dFdx(P), dFdy(P));
    float nl = length(N);
    N = nl > 1e-6 ? N / nl : vec3(0.0, 1.0, 0.0);
    if (dot(N, P) > 0.0) N = -N;                                       // face the camera

    // the block of air in front of this surface
    vec4 vol = HasVolume > 0.5 ? volume(P + VolOffset + N * 0.5) : vec4(0.0);
    float sky = max(vol.a * 15.0 - SkyDarken, 0.0);                   // sky light that's really reaching here now

    vec3 hueSum = vec3(0.0);
    float wSum = 0.0, wMax = 0.0;

    vec3 c = vol.rgb * 15.0;
    float L = max(c.r, max(c.g, c.b));
    if (L > 0.3) {
        float w = clamp((L - sky + 1.5) / 4.0, 0.0, 1.0) * smoothstep(0.3, 2.5, L);
        hueSum += (c / L) * w; wSum += w; wMax = max(wMax, w);
    }

    int count = int(LightCount + 0.5);
    for (int i = 0; i < count; i++) {
        vec4 a = texelFetch(LightSampler, ivec2(i, 0), 0);
        vec3 Lv = a.xyz - P;
        float r2 = a.w * a.w, d2 = dot(Lv, Lv);
        if (d2 >= r2) continue;
        vec4 b = texelFetch(LightSampler, ivec2(i, 1), 0);
        float dist = sqrt(d2);
        float att = 1.0 - dist / a.w;
        att *= att;
        float facing = dist < 0.7 ? 1.0 : clamp(dot(N, Lv / dist) * 0.65 + 0.35, 0.0, 1.0);
        float lvl = b.a - dist;                                        // the light level it gives here, vanilla style
        float w = att * facing * clamp((lvl - sky + 1.5) / 4.0, 0.0, 1.0);
        float m = max(b.r, max(b.g, b.b));
        if (w <= 0.0 || m <= 0.0) continue;
        hueSum += (b.rgb / m) * w; wSum += w; wMax = max(wMax, w);
    }

    vec3 color = scene;
    if (wSum > 0.001) {
        vec3 hue = hueSum / wSum;
        vec3 tint = hue / max(luma(hue), 0.22);                        // keep the brightness, change the colour
        vec3 lit = min(scene * tint, vec3(max(max(scene.r, max(scene.g, scene.b)) * 1.6, 0.0)));
        color = mix(scene, lit, clamp(wMax, 0.0, 1.0) * 0.85 * Strength);
    }
    fragColor = vec4(color, 1.0);
}
