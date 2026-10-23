#version 150

// Depth of field. Close-up (mode 1): when you look at something right next to you, it stays sharp and the world
// behind it melts away; look elsewhere and it eases back. The blur is the single-pass bokeh gather by Dennis
// Gustafsson ("Bokeh depth of field in a single pass", 2018): a golden-angle spiral out from each pixel, where every
// sample contributes as far as its own circle of confusion reaches, and background samples are held to twice this
// pixel's blur, so the sharp object in front never smears into the blur behind it.
// Tilt-shift (mode 2): a sharp band across the middle of the screen, blurring above and below (the miniature look).

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
const float RAD_SCALE = 0.6;

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
    if (DofMode < 0.5 || DofAmount <= 0.001 || MaxBlur < 0.5) { fragColor = vec4(color, 1.0); return; }
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
        float m = smoothstep(radius - 0.5, radius + 0.5, sampleSize);
        color += mix(color / tot, sampleColor, m);
        tot += 1.0;
        radius += RAD_SCALE / radius;
    }
    fragColor = vec4(color / tot, 1.0);
}
