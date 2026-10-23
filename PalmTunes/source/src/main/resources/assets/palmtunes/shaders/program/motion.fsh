#version 150

// Camera motion blur (after GPU Gems 3 ch. 27) from the camera's turn only, ported from the author's AmbientMotion mod.
// FocusMask keeps the middle of the picture - the subject of the shot - sharp while the surroundings smear.

uniform sampler2D DiffuseSampler;
uniform mat4 InvViewProj;
uniform mat4 PrevViewProj;
uniform float MotionAmount;
uniform float FocusMask;
uniform vec2 InSize;

in vec2 texCoord;
out vec4 fragColor;

float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }

void main() {
    vec3 here = texture(DiffuseSampler, texCoord).rgb;
    if (MotionAmount <= 0.0005) { fragColor = vec4(here, 1.0); return; }
    vec4 far = InvViewProj * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec4 prev = PrevViewProj * vec4(far.xyz / far.w, 1.0);
    if (prev.w <= 0.0) { fragColor = vec4(here, 1.0); return; }
    vec2 vel = (texCoord - (prev.xy / prev.w * 0.5 + 0.5)) * MotionAmount;
    vec2 q = (texCoord - 0.5) * vec2(InSize.x / InSize.y, 1.0);
    vel *= mix(1.0, smoothstep(0.10, 0.42, length(q)), FocusMask);
    float len = length(vel * InSize);
    if (len < 1.0) { fragColor = vec4(here, 1.0); return; }
    float maxLen = InSize.y * 0.035;
    if (len > maxLen) vel *= maxLen / len;
    int N = int(clamp(len * 0.5, 4.0, 16.0));
    float jitter = ign(gl_FragCoord.xy) - 0.5;
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < N; i++) {
        float t = (float(i) + 0.5 + jitter) / float(N) - 0.5;
        float w = 1.0 - abs(t) * 1.6;
        sum += texture(DiffuseSampler, clamp(texCoord + vel * t, vec2(0.0005), vec2(0.9995))).rgb * w;
        wsum += w;
    }
    fragColor = vec4(sum / wsum, 1.0);
}
