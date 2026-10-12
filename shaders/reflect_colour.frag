#version 460
#include "common.glsl"

// The flashlight reflection's colour as a full screen draw into one texel (SceneRenderer::RecordReflectionSample): what
// reflect_colour.comp computes without a compute shader (see there for what the original does and what is guessed). The texel is red,
// green, blue and 1, the two vec2 the compute shader writes to the luminance buffer, which the pass copies there.
//
// ids.x the scene image; f0 = (uv0, uv1), f1 = (uv2, uv3) the screen UVs of the quad's corners (rays at 315, 45, 225 and 135 degrees).

layout(push_constant) uniform PassPush {
    uvec4 ids;
    vec4 f0;
    vec4 f1;
    vec4 f2;
    mat4 m;
} pass;

layout(location = 0) out vec4 out_colour;

const int kSize = 64;
const int kRadius = 12;
const ivec2 kRead = ivec2(0, 32);
// C(24, k): twelve [1 2 1] passes; the sum is 2^24
const float kBinomial[25] = float[](1.0, 24.0, 276.0, 2024.0, 10626.0, 42504.0, 134596.0, 346104.0, 735471.0, 1307504.0, 1961256.0,
                                    2496144.0, 2704156.0, 2496144.0, 1961256.0, 1307504.0, 735471.0, 346104.0, 134596.0, 42504.0,
                                    10626.0, 2024.0, 276.0, 24.0, 1.0);

// the colour step 1 writes at a pixel of the region
vec3 Warped(ivec2 pixel) {
    vec2 p = (vec2(pixel) + 0.5) / float(kSize);
    vec2 uv;
    if (p.x + p.y <= 1.0) {
        // triangle (0, 0), (1, 0), (0, 1)
        uv = pass.f0.xy + p.x * (pass.f0.zw - pass.f0.xy) + p.y * (pass.f1.xy - pass.f0.xy);
    } else {
        // triangle (1, 0), (0, 1), (1, 1)
        uv = pass.f1.zw + (1.0 - p.x) * (pass.f1.xy - pass.f1.zw) + (1.0 - p.y) * (pass.f0.zw - pass.f1.zw);
    }
    vec3 c = ImgLod(pass.ids.x, SMP_LINEAR_CLAMP, uv, 0.0).rgb;
    return min(max(c, vec3(0.0)), vec3(1.0));
}

void main() {
    vec3 acc = vec3(0.0);
    for (int j = 0; j < 2 * kRadius + 1; ++j) {
        for (int i = 0; i < 2 * kRadius + 1; ++i) {
            ivec2 pixel = clamp(kRead + ivec2(i - kRadius, j - kRadius), ivec2(0), ivec2(kSize - 1));
            float w = (kBinomial[i] / 16777216.0) * (kBinomial[j] / 16777216.0);
            acc += w * Warped(pixel);
        }
    }
    vec3 c = floor(clamp(acc, vec3(0.0), vec3(1.0)) * 255.0 + 0.5) / 255.0;
    out_colour = vec4(c, 1.0);
}
