#version 410
// VoxelR — Full-screen quad vertex shader (fallback path)
out vec2 v_ScreenPos;

void main() {
    uint vid = uint(gl_VertexID);
    float x = float(vid & 1u) * 2.0 - 1.0;
    float y = float(vid & 2u) * 2.0 - 1.0;
    if (vid == 0u) { x = -1.0; y = -1.0; }
    if (vid == 1u) { x =  1.0; y = -1.0; }
    if (vid == 2u) { x = -1.0; y =  1.0; }
    if (vid == 3u) { x =  1.0; y =  1.0; }
    gl_Position = vec4(x, y, 0.0, 1.0);
    v_ScreenPos = vec2(x, y);
}
