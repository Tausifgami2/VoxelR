package net.balancinglight.voxelr.core.render.shader;

import net.balancinglight.voxelr.VoxelRMod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

/**
 * Thin wrapper around an OpenGL shader program. Handles loading GLSL sources
 * from the mod's resource pack, compilation, linking, and uniform caching.
 */
public final class ShaderProgram {

    private int programId = -1;
    private final String name;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    public ShaderProgram(String name) {
        this.name = name;
    }

    public void link(String vertPath, String fragPath) {
        int vert = compileShader(GL_VERTEX_SHADER,   loadSource(vertPath), vertPath);
        int frag = compileShader(GL_FRAGMENT_SHADER, loadSource(fragPath), fragPath);
        programId = linkProgram(vert, frag);
        glDeleteShader(vert);
        glDeleteShader(frag);
        VoxelRMod.LOGGER.debug("[VoxelR] ShaderProgram '{}' linked (id={})", name, programId);
    }

    public void use() {
        glUseProgram(programId);
    }

    public void stop() {
        glUseProgram(0);
    }

    public void setUniform1i(String name, int value) {
        glUniform1i(getUniformLocation(name), value);
    }

    public void setUniform1f(String name, float value) {
        glUniform1f(getUniformLocation(name), value);
    }

    public void setUniform1ui(String name, int value) {
        glUniform1ui(getUniformLocation(name), value);
    }

    public void setUniform2f(String name, float x, float y) {
        glUniform2f(getUniformLocation(name), x, y);
    }

    public void setUniform3f(String name, float x, float y, float z) {
        glUniform3f(getUniformLocation(name), x, y, z);
    }

    public void setUniform3i(String name, int x, int y, int z) {
        glUniform3i(getUniformLocation(name), x, y, z);
    }

    public void setUniformMatrix4fv(String name, boolean transpose, float[] matrix) {
        glUniformMatrix4fv(getUniformLocation(name), transpose, matrix);
    }

    public int getUniformLocation(String uName) {
        return uniformCache.computeIfAbsent(uName, k -> glGetUniformLocation(programId, k));
    }

    public void destroy() {
        if (programId >= 0) {
            glDeleteProgram(programId);
            programId = -1;
        }
    }

    private int compileShader(int type, String source, String path) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(id);
            glDeleteShader(id);
            throw new RuntimeException("[VoxelR] Shader compile error in '" + path + "':\n" + log);
        }
        return id;
    }

    private int linkProgram(int vert, int frag) {
        int id = glCreateProgram();
        glAttachShader(id, vert);
        glAttachShader(id, frag);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(id);
            glDeleteProgram(id);
            throw new RuntimeException("[VoxelR] Program link error in '" + name + "':\n" + log);
        }
        return id;
    }

    private String loadSource(String resourcePath) {
        String[] parts = resourcePath.split(":");
        String ns = parts[0];
        String path = parts[1];
        String fullPath = "/assets/" + ns + "/" + path;
        try (InputStream is = ShaderProgram.class.getResourceAsStream(fullPath)) {
            if (is == null) throw new IOException("Resource not found: " + fullPath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("[VoxelR] Failed to load shader source: " + fullPath, e);
        }
    }
}
