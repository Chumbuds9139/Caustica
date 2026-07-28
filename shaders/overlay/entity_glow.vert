#version 460

// Entity-glow mask: re-rasterizes glowing entities' body meshes (RtEntities already keeps this frame's
// posed CPU-side vertex data around for BLAS refit) into a full-res, depth-less mask image at the exact
// same camera projection the RT world trace used (curViewProj/camOffset mirror world.rgen's WorldPush
// fields byte-for-byte in meaning), so the silhouette lands pixel-exact on the ray-traced entity. No
// depth test/attachment at all — like vanilla's Glowing outline, the mask (and therefore the outline
// RtGlowOutline derives from it) is meant to show through walls.
//
// jitterNdc shifts clip.xy by the same subpixel offset the primary ray was jittered by (display-NDC
// units, see RtComposite.currentJitterNdcX/Y), so the mask lands on the same sample DLSS-RR
// reconstructed to instead of a half-pixel off the entity silhouette.
layout(push_constant) uniform Push {
    mat4 curViewProj; // forward camera-relative view-projection (= RtComposite's frameProjection*frameViewRotation)
    vec3 camOffset;   // camera position in the same rebased space inPos is captured in
    vec4 color;       // this entity's vanilla outline colour (opaque team colour, or white)
    vec2 jitterNdc;   // 96, 8B (padded to 16B)
} pc;

layout(location = 0) in vec3 inPos;

void main() {
    vec4 clip = pc.curViewProj * vec4(inPos - pc.camOffset, 1.0);
    clip.xy += pc.jitterNdc * clip.w;
    gl_Position = clip;
}
