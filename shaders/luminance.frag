#version 460
#include "common.glsl"

// The exposure meter as a full screen draw (SceneRenderer::RecordLuminanceDraw): luminance.comp's job without a compute shader. The
// target has one texel for each 32 x 32 tile of the lit picture; the texel's invocation walks its tile and writes what the compute
// shader's work group did, as three outputs: the partial sum and count (location 0, the compute shader's partial[]: x the sum of the
// pixels' luma, y the count of them, plus 2048 for each bright one), the counters (1: no depth, non-finite, over 60000, black) and the
// albedo's luma x 1024 summed (2). The compute shader gave only partly written results on an Adreno 740.

layout(push_constant) uniform PassPush {
    uvec4 ids;
    vec4 f0;
    vec4 f1;
    vec4 f2;
    mat4 m;
} pass;

layout(location = 0) out vec2 out_partial;
layout(location = 1) out uvec4 out_counts;
layout(location = 2) out uint out_albedo;

void main() {
    // pass.f0.x: a ceiling for one pixel's share of the mean (0 for none); pass.f0.y: pixels brighter than this are counted as bright
    // (the count rides in the high part of the pixel count, for the log: 2048 for each bright pixel)
    const float ceiling = pass.f0.x;
    const float threshold = pass.f0.y;
    ivec2 size = ivec2(ImgSize(IMG_DIFFUSE));
    ivec2 base = ivec2(gl_FragCoord.xy) * 32;
    vec2 acc = vec2(0.0);
    uvec4 cnt = uvec4(0u);
    uint alb = 0u;
    for (int y = 0; y < 32; ++y) {
        for (int x = 0; x < 32; ++x) {
            ivec2 p = base + ivec2(x, y);
            if (!all(lessThan(p, size))) {
                continue;
            }
            float depth = ImgFetch(IMG_DEPTH, p).x;
            if (!(depth > 0.0)) {
                cnt.x += 1u;
                continue;
            }
            // The lit image holds half floats: a pixel with a NaN or an infinity would turn the whole sum, and with it the exposure,
            // into a NaN. Such a pixel is left out and counted.
            vec3 lit = ImgFetch(IMG_DIFFUSE, p).rgb;
            if (!IsFiniteBits(lit)) {
                cnt.y += 1u;
                continue;
            }
            float luma = Luma709(lit);
            if (luma >= 60000.0) {
                cnt.z += 1u;
                continue;
            }
            if (luma < 1.0e-4) {
                cnt.w += 1u;
            }
            // A few pixels far brighter than the picture would otherwise set the mean, and so the exposure, for the whole picture.
            const float used = ceiling > 0.0 ? min(luma, ceiling) : luma;
            acc += vec2(max(0.0, used), luma > threshold ? 2049.0 : 1.0);
            vec3 albedo = ImgFetch(IMG_ALBEDO, p).rgb;
            if (IsFiniteBits(albedo)) {
                alb += uint(clamp(Luma709(albedo), 0.0, 1.0) * 1024.0);
            }
        }
    }
    out_partial = acc;
    out_counts = cnt;
    out_albedo = alb;
}
