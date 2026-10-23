#version 150

// Autofocus: the distance to whatever is under the crosshair, eased over a few frames and kept in a 1x1 target
// (packed into 24 bits of colour, on a log scale up to ~1000 blocks).

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform sampler2D PrevFocusSampler;
uniform mat4 InvViewProj;
uniform float FocusSpeed;

in vec2 texCoord;
out vec4 fragColor;

vec3 pack(float v) {
    v = clamp(v, 0.0, 0.99999);
    vec3 e = fract(v * vec3(1.0, 255.0, 65025.0));
    e -= e.yzz * vec3(1.0 / 255.0, 1.0 / 255.0, 0.0);
    return e;
}
float unpack(vec3 e) { return dot(e, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0)); }

float dist(vec2 uv) {
    float d = texture(DepthSampler, uv).r;
    vec4 p = InvViewProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return length(p.xyz / p.w);
}

void main() {
    // a few taps round the centre, nearest wins (so a thin post in front still takes focus)
    float d = dist(vec2(0.5));
    d = min(d, dist(vec2(0.49, 0.5)));
    d = min(d, dist(vec2(0.51, 0.5)));
    d = min(d, dist(vec2(0.5, 0.49)));
    d = min(d, dist(vec2(0.5, 0.51)));
    float now = log2(1.0 + min(d, 1000.0)) / 10.0;
    float before = unpack(texture(PrevFocusSampler, vec2(0.5)).rgb);
    float v = before <= 0.0001 ? now : mix(before, now, FocusSpeed);
    fragColor = vec4(pack(v), 1.0);
}
