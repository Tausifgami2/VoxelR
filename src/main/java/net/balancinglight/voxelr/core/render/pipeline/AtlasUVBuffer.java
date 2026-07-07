package net.balancinglight.voxelr.core.render.pipeline;

import com.mojang.blaze3d.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.balancinglight.voxelr.VoxelRMod;
import net.balancinglight.voxelr.core.world.BlockStateToId;
import net.balancinglight.voxelr.util.GlUtil;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.*;

public final class AtlasUVBuffer {

    public static final int BINDING = 4;

    private static final int MAX_BLOCK_IDS    = 65536;
    private static final int FACES            = 6;
    private static final int BYTES_PER_ENTRY  = 4 * Float.BYTES;
    private static final long TOTAL_BYTES     = (long) MAX_BLOCK_IDS * FACES * BYTES_PER_ENTRY;

    private static final Direction[] FACE_DIRS = {
        Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH
    };

    private static final RandomSource RNG = RandomSource.create(42L);

    private int     ssboId = -1;
    private int     tintSsboId = -1;
    private int     bufTexId = 0;      // buffer texture wrapping ssboId (unit 10, RGBA32F)
    private int     bufTexTintId = 0;  // buffer texture wrapping tintSsboId (unit 11, R32UI)
    private boolean built  = false;

    private static int atlasGlId = -1;

    public void build() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getModelManager() == null) {
            VoxelRMod.LOGGER.warn("[VoxelR] AtlasUVBuffer.build() too early — skipping.");
            return;
        }

        VoxelRMod.LOGGER.info("[VoxelR] Building AtlasUVBuffer ({} KB)...", TOTAL_BYTES / 1024);

        ByteBuffer buf = MemoryUtil.memAlloc((int) TOTAL_BYTES);
        MemoryUtil.memSet(buf, 0);

        int mapped = 0, skipped = 0;

        BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();

        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                short id = BlockStateToId.toId(state);
                if (id <= 0) { skipped++; continue; }

                BlockStateModel model;
                try {
                    model = modelSet.get(state);
                } catch (Exception e) { skipped++; continue; }

                if (model == null) { skipped++; continue; }

                boolean any = false;
                for (int fi = 0; fi < FACES; fi++) {
                    TextureAtlasSprite sprite = getSpriteForFace(model, FACE_DIRS[fi]);
                    if (sprite == null) continue;
                    int off = ((id & 0xFFFF) * FACES + fi) * BYTES_PER_ENTRY;
                    buf.putFloat(off,      sprite.getU0());
                    buf.putFloat(off +  4, sprite.getU1());
                    buf.putFloat(off +  8, sprite.getV0());
                    buf.putFloat(off + 12, sprite.getV1());
                    any = true;
                }
                if (any) mapped++; else skipped++;
            }
        }

        if (ssboId == -1) ssboId = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, ssboId);
        glBufferData(GL_COPY_WRITE_BUFFER, buf, GL_STATIC_DRAW);
        if (GlUtil.SSBO_ENABLED) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssboId);
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        MemoryUtil.memFree(buf);

        built = true;
        VoxelRMod.LOGGER.info("[VoxelR] AtlasUVBuffer: {} mapped, {} skipped.", mapped, skipped);

        buildTintSsbo();
    }

    private void buildTintSsbo() {
        Minecraft mc = Minecraft.getInstance();
        BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
        ByteBuffer tintBuf = MemoryUtil.memAlloc(MAX_BLOCK_IDS * 4);
        MemoryUtil.memSet(tintBuf, 0);
        int mapped = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
            String path = blockId.getPath();
            int tintType = 0;
            if (path.contains("leaves") || path.equals("vine") || path.equals("twisting_vines") ||
                path.equals("weeping_vines") || path.equals("cave_vines") || path.equals("glow_lichen") ||
                path.equals("moss_block") || path.equals("moss_carpet") || path.equals("big_dripleaf") ||
                path.equals("small_dripleaf")) {
                tintType = 2; // foliage
            } else if (path.equals("water") || path.equals("flowing_water")) {
                tintType = 3; // water
            } else if (path.equals("grass_block") || path.contains("grass") || path.contains("fern") ||
                path.contains("sugar_cane") || path.contains("pumpkin_stem") || path.contains("melon_stem")) {
                tintType = 1; // grass
            }
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                short id = BlockStateToId.toId(state);
                if (id <= 0) continue;
                tintBuf.putInt((id & 0xFFFF) * 4, tintType);
                mapped++;
            }
        }
        if (tintSsboId == -1) tintSsboId = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, tintSsboId);
        glBufferData(GL_COPY_WRITE_BUFFER, tintBuf, GL_STATIC_DRAW);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        MemoryUtil.memFree(tintBuf);
        VoxelRMod.LOGGER.info("[VoxelR] Tint SSBO built: {} state mappings.", mapped);
    }

    public void bind() {
        if (GlUtil.SSBO_ENABLED && ssboId >= 0) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssboId);
        }
    }

    public void bindTintSsbo() {
        if (GlUtil.SSBO_ENABLED && tintSsboId >= 0) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, tintSsboId);
        }
    }

    public void bindAtlasTexture() {
        glActiveTexture(GL_TEXTURE0);
        if (atlasGlId < 0) atlasGlId = findAtlasGlId();
        if (atlasGlId >= 0) {
            glBindTexture(GL_TEXTURE_2D, atlasGlId);
        } else {
            VoxelRMod.LOGGER.warn("[VoxelR] bindAtlasTexture: no GL id, texture NOT bound");
        }
    }

    public boolean isBuilt() { return built; }

    private static int findAtlasGlId() {
        try {
            var mc = Minecraft.getInstance();
            var loc = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png");
            var atlas = (AbstractTexture) mc.getTextureManager().getTexture(loc);
            if (atlas == null) return -1;
            var gpuTex = atlas.getTexture();
            if (gpuTex instanceof GlTexture glTex) {
                int id = glTex.id;
                VoxelRMod.LOGGER.info("[VoxelR] findAtlasGlId: {} (direct access)", id);
                return id;
            }
            VoxelRMod.LOGGER.warn("[VoxelR] findAtlasGlId: gpuTex not GlTexture: {}", gpuTex.getClass().getName());
        } catch (Exception e) {
            VoxelRMod.LOGGER.warn("[VoxelR] findAtlasGlId failed: {}", e.getMessage());
        }
        return -1;
    }

    public void createBufferTextures() {
        if (ssboId >= 0 && bufTexId == 0) {
            bufTexId = glGenTextures();
            glActiveTexture(GL_TEXTURE10);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexId);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, ssboId);
        }
        if (tintSsboId >= 0 && bufTexTintId == 0) {
            bufTexTintId = glGenTextures();
            glActiveTexture(GL_TEXTURE11);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexTintId);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, tintSsboId);
        }
        glActiveTexture(GL_TEXTURE0);
    }

    public void bindBufferTextures() {
        if (bufTexId > 0) {
            glActiveTexture(GL_TEXTURE10);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexId);
        }
        if (bufTexTintId > 0) {
            glActiveTexture(GL_TEXTURE11);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexTintId);
        }
    }

    public void destroy() {
        if (bufTexId > 0) { glDeleteTextures(bufTexId); bufTexId = 0; }
        if (bufTexTintId > 0) { glDeleteTextures(bufTexTintId); bufTexTintId = 0; }
        if (ssboId >= 0) { glDeleteBuffers(ssboId); ssboId = -1; }
        built = false;
    }

    private static TextureAtlasSprite getSpriteForFace(BlockStateModel model, Direction dir) {
        try {
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RNG, parts);

            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(dir);
                if (!quads.isEmpty()) {
                    return quads.get(0).materialInfo().sprite();
                }
            }
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(null);
                if (!quads.isEmpty()) {
                    return quads.get(0).materialInfo().sprite();
                }
            }
            if (!parts.isEmpty()) {
                return parts.get(0).particleMaterial().sprite();
            }
        } catch (Exception e) { /* fall through */ }
        return null;
    }
}
