#version 460

// Full-res, post-upscale name-tag billboards. Every visible tag's glyph quads (already billboarded to
// face the camera and positioned in the entity's rebased world space by RtNameTagFeature, on the CPU —
// see that class for why: unlike glow, this can't be baked into the entity's own rigid mesh, since the
// billboard rotates with the camera every frame) are merged into one vertex buffer per font-atlas page;
// this shader just finishes the camera-relative transform, mirroring world.rgen's WorldPush fields
// byte-for-byte in meaning (curViewProj/camOffset).
//
// jitterNdc shifts clip.xy by the same subpixel offset the primary ray used (display-NDC units, see
// RtComposite.currentJitterNdcX/Y) so glyph quads land pixel-exact on the reconstructed RT content
// instead of wobbling half a pixel behind it.
layout(push_constant) uniform Push {
    mat4 curViewProj;
    vec3 camOffset;
    vec2 jitterNdc;   // 80, 8B (padded to 16B)
} pc;

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;

layout(location = 0) out vec2 outUv;
layout(location = 1) out vec4 outColor;

void main() {
    vec4 clip = pc.curViewProj * vec4(inPos - pc.camOffset, 1.0);
    clip.xy += pc.jitterNdc * clip.w;
    gl_Position = clip;
    outUv = inUv;
    outColor = inColor;
}
