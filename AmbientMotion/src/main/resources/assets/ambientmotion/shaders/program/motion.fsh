#version 150

// Camera motion blur, after GPU Gems 3 ch. 27 (velocity from the current and previous view-projection) with the
// usual fixes for comfort: the blur comes from how the camera turns only (no walking, no view bobbing), the turn rate
// is smoothed and scaled to a fixed shutter time on the Java side (so it is the same at any frame rate), slow turns
// get none at all, and the samples are centred, tent-weighted and jittered (interleaved gradient noise, Jimenez 2014)
// so there is no stepping or ghosting.

uniform sampler2D DiffuseSampler;
uniform mat4 InvViewProj;     // this frame's camera (rotation and projection)
uniform mat4 PrevViewProj;    // where the camera pointed one shutter-time ago
uniform float MotionAmount;   // 0 = off
uniform vec2 InSize;

in vec2 texCoord;
out vec4 fragColor;

float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }

void main() {
    vec3 here = texture(DiffuseSampler, texCoord).rgb;
    if (MotionAmount <= 0.0005) { fragColor = vec4(here, 1.0); return; }
    // the direction this pixel looks along, and where that direction sat on screen a moment ago
    vec4 far = InvViewProj * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec4 prev = PrevViewProj * vec4(far.xyz / far.w, 1.0);
    if (prev.w <= 0.0) { fragColor = vec4(here, 1.0); return; }
    vec2 vel = (texCoord - (prev.xy / prev.w * 0.5 + 0.5)) * MotionAmount;
    float len = length(vel * InSize);                 // in pixels
    if (len < 1.0) { fragColor = vec4(here, 1.0); return; }
    float maxLen = InSize.y * 0.035;                  // never more than ~3.5% of the screen
    if (len > maxLen) vel *= maxLen / len;
    int N = int(clamp(len * 0.5, 4.0, 16.0));        // a short smear needs few samples
    float jitter = ign(gl_FragCoord.xy) - 0.5;
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < N; i++) {
        float t = (float(i) + 0.5 + jitter) / float(N) - 0.5;      // -0.5 .. 0.5, centred on the pixel
        float w = 1.0 - abs(t) * 1.6;                                // tent: the middle counts most
        sum += texture(DiffuseSampler, clamp(texCoord + vel * t, vec2(0.0005), vec2(0.9995))).rgb * w;
        wsum += w;
    }
    fragColor = vec4(sum / wsum, 1.0);
}
