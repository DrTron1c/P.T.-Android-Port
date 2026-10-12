#version 460

// The built-in temporal upscaler as a full screen draw (src/engine/render/upscale/builtin_backend.cpp): the same picture as
// builtin_upscale.comp, written through colour attachments instead of storage images. This is pass 0: the resolved picture goes to
// the output (location 0) and the history for the next frame (location 1). builtin_upscale_sharpen.frag is pass 1. What each pass
// does is in builtin_upscale.glsl.

#include "builtin_upscale.glsl"

layout(location = 0) out vec4 out_colour;
layout(location = 1) out vec4 out_history;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    out_history = vec4(0.0);
    if (pc.b.w > 0.5) {
        out_colour = DebugPixel(px);
    } else {
        out_colour = ResolvePixel(px, out_history);
    }
}
