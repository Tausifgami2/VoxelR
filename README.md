> **⚠️ EARLY EXPERIMENTAL ALPHA**  
> This mod is in active development. Expect bugs, missing features, and breaking changes.

# VoxelR

A GPU ray-marched terrain rendering engine for Minecraft. Replaces the vanilla chunk mesh pipeline with per-pixel ray tracing through a sparse voxel grid on the GPU.

![Mod screenshot](https://dl.dropboxusercontent.com/scl/fi/od1zqd7l6x4skzsm7s8ii/2026-07-07_21.57.19.png?rlkey=zem3yvey42pau2mudbqzr4s9x)

**No LOD trickery. No geometry culling. Just ray marching.** Every pixel traces a ray through compact 16×16×16 voxel chunks stored directly on the GPU — full blocks, slabs, stairs, per-chunk biome tinting, and water transparency, all in the fragment shader. Cross-quad blocks (plants, flowers) use lightweight triangle meshes inside the ray march for the handful of blocks that need them.

**Render paths:**
- **SSBO** (OpenGL 4.3+) — Nvidia, AMD, Intel Arc
- **Buffer-texture fallback** (OpenGL 3.1+) — older GPUs, Intel HD, Apple Silicon

Not compatible with Sodium or Nvidium.

Requires Fabric. Tested on 26.1.2.

---

**Consider donating** —  [Patreon](https://www.patreon.com/c/balancinglight/membership)

---

**AI notice:** This mod was developed with assistance from AI language models. If that bothers you or you consider it slop, feel free to skip it. We believe in transparency.
