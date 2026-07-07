package net.balancinglight.voxelr.util;

import net.balancinglight.voxelr.VoxelRMod;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.*;

/**
 * OpenGL utility helpers used across the VoxelR render pipeline.
 */
public final class GlUtil {

    private GlUtil() {}

    /** Buffer allocation target — GL_COPY_WRITE_BUFFER on GL < 4.3 (Apple), GL_SHADER_STORAGE_BUFFER otherwise. */
    public static int BUFFER_TARGET = GL_SHADER_STORAGE_BUFFER;

    /** True if SSBO bindings are available (GL 4.3+ or ARB_extension). Guards glBindBufferBase calls. */
    public static boolean SSBO_ENABLED = true;

    /** Feature support flags returned by checkSupport(). */
    public static final class Support {
        public final boolean ssbo;
        public final boolean compute;
        public final boolean bufferTextures;
        public final boolean multiDrawIndirect;
        /** glslVersion is the numeric version, e.g. 460, 410, 330. */
        public final int glslVersion;

        public Support(boolean ssbo, boolean compute, boolean bufTex, boolean mdi, int glslVer) {
            this.ssbo = ssbo;
            this.compute = compute;
            this.bufferTextures = bufTex;
            this.multiDrawIndirect = mdi;
            this.glslVersion = glslVer;
        }

        public boolean canRender() {
            return ssbo || bufferTextures;
        }

        @Override
        public String toString() {
            return String.format(
                "Support[SSBO=%s Compute=%s BufTex=%s MDI=%s GLSL=%d]",
                ssbo, compute, bufferTextures, multiDrawIndirect, glslVersion);
        }
    }

    public static boolean checkGlError(String context) {
        int error = glGetError();
        if (error == GL_NO_ERROR) return false;
        VoxelRMod.LOGGER.error("[VoxelR] GL error in '{}': {} (0x{})",
            context, errorName(error), Integer.toHexString(error));
        return true;
    }

    public static String errorName(int error) {
        return switch (error) {
            case GL_NO_ERROR          -> "GL_NO_ERROR";
            case GL_INVALID_ENUM      -> "GL_INVALID_ENUM";
            case GL_INVALID_VALUE     -> "GL_INVALID_VALUE";
            case GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL_OUT_OF_MEMORY     -> "GL_OUT_OF_MEMORY";
            case GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            default -> "UNKNOWN(0x" + Integer.toHexString(error) + ")";
        };
    }

    public static String getGpuString(int name) {
        String s = glGetString(name);
        return s != null ? s : "(unavailable)";
    }

    public static void logGpuInfo() {
        VoxelRMod.LOGGER.info("[VoxelR] GPU Vendor:   {}", getGpuString(GL_VENDOR));
        VoxelRMod.LOGGER.info("[VoxelR] GPU Renderer: {}", getGpuString(GL_RENDERER));
        VoxelRMod.LOGGER.info("[VoxelR] GL Version:   {}", getGpuString(GL_VERSION));
        VoxelRMod.LOGGER.info("[VoxelR] GLSL Version: {}", getGpuString(GL_SHADING_LANGUAGE_VERSION));
        int[] maxSsboSize = new int[1];
        glGetIntegerv(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, maxSsboSize);
        VoxelRMod.LOGGER.info("[VoxelR] Max SSBO Size: {} MB", maxSsboSize[0] / 1024 / 1024);
        int[] maxTexBufSize = new int[1];
        glGetIntegerv(GL_MAX_TEXTURE_BUFFER_SIZE, maxTexBufSize);
        VoxelRMod.LOGGER.info("[VoxelR] Max TexBuf Size: {} texels ({} MB)",
            maxTexBufSize[0], (long)maxTexBufSize[0] * 4 / (1024*1024));
    }

    /**
     * Detect which rendering features are available.
     *
     * <p>Minecraft creates a GL 3.2/3.3 core context by default, but modern
     * drivers expose all 4.x features as ARB extensions even in a 3.x context.
     * Checking the version string ("3.3.0") is therefore wrong — we must check
     * the actual capability flags instead.
     *
     * <p>Apple M1 Metal wrapper caps at GL 4.1 without SSBO or compute shader support,
     * but buffer textures (GL_ARB_texture_buffer_object) are available since GL 3.1
     * and work on Apple, providing the fallback path.
     */
    public static Support checkSupport() {
        GLCapabilities caps = GL.getCapabilities();
        if (caps == null) {
            VoxelRMod.LOGGER.error("[VoxelR] Could not retrieve GLCapabilities — aborting.");
            return new Support(false, false, false, false, 0);
        }

        // GL 4.3 core makes these native; ARB extensions expose them in 3.x contexts.
        boolean hasSSBO    = caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object;
        boolean hasCompute = caps.OpenGL43 || caps.GL_ARB_compute_shader;
        boolean hasMDI     = caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect;
        // Buffer textures: core since GL 3.1, ARB extension for older.
        boolean hasBufTex  = caps.OpenGL31 || caps.GL_ARB_texture_buffer_object;
        // Max texels in a buffer texture (0 if not supported)
        int[] maxTexBufSize = new int[1];
        if (hasBufTex) {
            glGetIntegerv(GL_MAX_TEXTURE_BUFFER_SIZE, maxTexBufSize);
            if (maxTexBufSize[0] < 65536) hasBufTex = false; // not useful below 256K
        }

        // Parse GLSL version from string (e.g. "4.60" → 460)
        int glslVer = 0;
        String glslStr = getGpuString(GL_SHADING_LANGUAGE_VERSION);
        if (glslStr != null && !glslStr.startsWith("(")) {
            String[] parts = glslStr.split(" ");
            for (String p : parts) {
                if (p.matches("\\d+\\.\\d+")) {
                    String[] ver = p.split("\\.");
                    glslVer = Integer.parseInt(ver[0]) * 100 + Integer.parseInt(ver[1]);
                    break;
                }
            }
        }

        Support sup = new Support(hasSSBO, hasCompute, hasBufTex, hasMDI, glslVer);

        VoxelRMod.LOGGER.info(
            "[VoxelR] GL support: SSBO={} Compute={} BufTex={}(size={}) MDI={} GLSL={}",
            hasSSBO, hasCompute, hasBufTex, maxTexBufSize[0], hasMDI, glslVer);

        return sup;
    }
}
