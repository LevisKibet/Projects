#version 150

// Depth of field, the merge: each full-size pixel takes the sharp image or the half-size blur (smoothly upscaled)
// according to how out of focus it is.

uniform sampler2D HalfSampler;
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
    vec3 sharp = texture(DiffuseSampler, texCoord).rgb;
    if (DofMode < 0.5 || DofAmount <= 0.001 || MaxBlur < 0.5) { fragColor = vec4(sharp, 1.0); return; }
    float c = blurSize(linearDepth(texCoord), texCoord);
    float t = smoothstep(0.6, 3.0, c);
    fragColor = vec4(mix(sharp, texture(HalfSampler, texCoord).rgb, t), 1.0);
}
