#version 460 core
// VoxelR — Full-screen quad vertex shader
// Zero SSBOs — no Cg crash. All work in fragment shader.

out vec2 v_ScreenPos;

void main() {
    uint vid = uint(gl_VertexID);
    float x = float(vid & 1u) * 2.0 - 1.0;
    float y = float(vid & 2u) * 2.0 - 1.0; // 0→-1, 2→1
    // Actually: vid 0→(-1,-1), 1→(1,-1), 2→(-1,1), 3→(1,1) for triangle strip
    if (vid == 0u) { x = -1.0; y = -1.0; }
    if (vid == 1u) { x =  1.0; y = -1.0; }
    if (vid == 2u) { x = -1.0; y =  1.0; }
    if (vid == 3u) { x =  1.0; y =  1.0; }
    gl_Position = vec4(x, y, 0.0, 1.0);
    v_ScreenPos = vec2(x, y);
}
