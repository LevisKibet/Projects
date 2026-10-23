#version 150

// Depth of field, the blur itself - drawn at half resolution (a quarter of the pixels) and with the sample spiral
// spaced for it, so it costs a fraction of a full-size gather. The single-pass bokeh gather by Dennis Gustafsson
// ("Bokeh depth of field in a single pass", 2018): a golden-angle spiral out from each pixel, where every sample
// counts as far as its own circle of confusion reaches, and background samples are held to twice this pixel's blur
// so a sharp object in front never smears into the blur behind it. dof_merge then lays it over the sharp image.

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform float DofMode;
uniform float DofAmount;       // 0..1, eased on the Java side
uniform float FocusDistance;   // blocks
uniform float MaxBlur;         // pixels
uniform vec2 NearFar;
uniform vec2 InSize;

in vec2 texCoord;
in vec2 oneTexel;
out vec4 fragColor;

const float GOLDEN_ANGLE = 2.39996323;
const float RAD_SCALE = 1.2;     // in full-size pixels: 0.6 of a half-size one

float linearDepth(vec2 uv) {
    float z = texture(DepthSampler, uv).r * 2.0 - 1.0;
    float n = NearFar.x, f = NearFar.y;
    return 2.0 * n * f / (f + n - z * (f - n));
}

float blurSize(float depth, vec2 uv) {
    if (DofMode > 1.5) return smoothstep(0.08, 0.40, abs(uv.y - 0.5)) * MaxBlur * DofAmount;
    // thin lens: the circle of confusion grows with 1/focus - 1/depth (in front of the focus it goes negative)
    float coc = clamp((1.0 / FocusDistance - 1.0 / depth) * FocusDistance * 1.35, -1.0, 1.0);
    return abs(coc) * MaxBlur * DofAmount;
}

void main() {
    vec3 color = texture(DiffuseSampler, texCoord).rgb;
    float centerDepth = linearDepth(texCoord);
    float centerSize = blurSize(centerDepth, texCoord);
    float tot = 1.0;
    float radius = RAD_SCALE;
    float maxR = MaxBlur * DofAmount;
    for (float ang = 0.0; radius < maxR; ang += GOLDEN_ANGLE) {
        vec2 tc = clamp(texCoord + vec2(cos(ang), sin(ang)) * oneTexel * radius, vec2(0.0005), vec2(0.9995));
        vec3 sampleColor = texture(DiffuseSampler, tc).rgb;
        float sampleDepth = linearDepth(tc);
        float sampleSize = blurSize(sampleDepth, tc);
        if (sampleDepth > centerDepth) sampleSize = clamp(sampleSize, 0.0, centerSize * 2.0);
        float m = smoothstep(radius - 1.0, radius + 1.0, sampleSize);
        color += mix(color / tot, sampleColor, m);
        tot += 1.0;
        radius += RAD_SCALE / radius;
    }
    fragColor = vec4(color / tot, 1.0);
}
