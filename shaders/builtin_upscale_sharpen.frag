#version 460

// Pass 1 of the built-in temporal upscaler's full screen draw (builtin_upscale.frag is pass 0): the history the first pass wrote,
// sharpened, drawn over the output. What it does is in builtin_upscale.glsl.

#include "builtin_upscale.glsl"

layout(location = 0) out vec4 out_colour;

void main() {
    out_colour = SharpenPixel(ivec2(gl_FragCoord.xy));
}
