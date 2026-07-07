package net.balancinglight.voxelr.core.render;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import net.balancinglight.voxelr.VoxelRMod;
import net.balancinglight.voxelr.api.VoxelRAPI;
import net.balancinglight.voxelr.api.event.RenderContext;
import net.balancinglight.voxelr.config.VoxelRConfig;
import net.balancinglight.voxelr.core.render.pipeline.AtlasUVBuffer;
import net.balancinglight.voxelr.core.render.pipeline.BlockShapeData;
import net.balancinglight.voxelr.core.render.pipeline.SSBOManager;
import net.balancinglight.voxelr.core.render.shader.ShaderProgram;
import net.balancinglight.voxelr.core.world.ChunkNode;
import net.balancinglight.voxelr.core.world.VoxelWorld;
import net.balancinglight.voxelr.util.GlUtil;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL40.*;
import static org.lwjgl.opengl.GL43.*;
import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

public final class VoxelRenderer {

    private final VoxelWorld world;

    private final SSBOManager          ssboManager          = new SSBOManager();
    private final AtlasUVBuffer        atlasUVBuffer        = new AtlasUVBuffer();
    private final BlockShapeData        blockShapeData      = new BlockShapeData();
    private static final int MAX_NODE_UPLOADS_PER_FRAME = Integer.MAX_VALUE;
    private ChunkNode[] leftoverDirtyNodes = new ChunkNode[0];

    private ShaderProgram voxelShader;
    private ShaderProgram voxelShaderNoWater;
    private ShaderProgram voxelShaderFallback;
    private boolean useWaterShader = true;
    private boolean useSSBO = true;
    private boolean useBufferTextures = false;
    private int     bufTexSpatialIdx = 0;
    private int     bufTexChunkColors = 0;

    private boolean initialized      = false;
    private boolean initFailed       = false;
    private long    frameIndex       = 0L;
    private int     visibleNodeCount = 0;
    private long    lastAllocatedCount = 0L;
    private boolean metaDumped = false;
    private long    lastDebugLog = 0L;
    private RenderContext cachedRenderContext = null;
    private boolean frameDrawn = false;
    private int     rayMarchVao = -1;
    private int     cachedMainFbo = 0;
    private boolean mainFboCached = false;

    private int chunkColorSsbo = -1;
    private int occupancySsbo = -1;
    private int spatialIndexSsbo = -1;
    private int spatialIndexSizeX = 0;
    private int spatialIndexSizeZ = 0;
    private int spatialIndexSizeY = 0;
    private int spatialOriginCx = 0;
    private int spatialOriginCz = 0;
    private int spatialOriginCy = -4;
    private int lastSpatialCx = Integer.MAX_VALUE;
    private int lastSpatialCz = Integer.MAX_VALUE;
    private boolean spatialIndexDirty = true;
    private final Matrix4f frustumMtx = new Matrix4f();

    private final float[] cachedVpResult = new float[16];
    private final float[] cachedInvVp = new float[16];
    private final float[] cachedInvDirVp = new float[16];
    private final Matrix4f cachedVpMat = new Matrix4f();
    private final Matrix4f cachedInvVpMat = new Matrix4f();
    private final Matrix4f cachedDirVpMat = new Matrix4f();
    private final Matrix4f cachedInvDirVpMat = new Matrix4f();

    public VoxelRenderer(VoxelWorld world) {
        this.world = world;
    }

    private void initialize() {
        if (initialized || initFailed) return;

        VoxelRMod.LOGGER.info("[VoxelR] Initializing GPU renderer…");
        GlUtil.logGpuInfo();

        var support = GlUtil.checkSupport();
        if (!support.canRender()) {
            VoxelRMod.LOGGER.error("[VoxelR] No render path available (SSBO={} BufTex={}) — renderer disabled.",
                support.ssbo, support.bufferTextures);
            initFailed = true;
            return;
        }

        useSSBO = support.ssbo;
        useBufferTextures = support.bufferTextures && !useSSBO;

        GlUtil.SSBO_ENABLED = useSSBO;
        if (!useSSBO) {
            GlUtil.BUFFER_TARGET = GL_COPY_WRITE_BUFFER;
        }

        VoxelRMod.LOGGER.info("[VoxelR] Render path: {}",
            useSSBO ? "SSBO (standard)" : useBufferTextures ? "Buffer Textures (fallback)" : "NONE");

        ssboManager.initialize();
        blockShapeData.initialize();
        if (useBufferTextures) {
            blockShapeData.createBufferTextures();
        }

        rayMarchVao = glGenVertexArrays();

        if (useSSBO) {
            voxelShader = new ShaderProgram("voxel");
            voxelShader.link("voxelr:shaders/program/voxel.vert", "voxelr:shaders/program/voxel.frag");
            voxelShader.use();
            voxelShader.setUniform1i("u_Atlas", 0);
            voxelShader.stop();

            voxelShaderNoWater = new ShaderProgram("voxel_nowater");
            voxelShaderNoWater.link("voxelr:shaders/program/voxel.vert", "voxelr:shaders/program/voxel_nowater.frag");
            voxelShaderNoWater.use();
            voxelShaderNoWater.setUniform1i("u_Atlas", 0);
            voxelShaderNoWater.stop();
        }
        if (useBufferTextures) {
            voxelShaderFallback = new ShaderProgram("voxel_fallback");
            voxelShaderFallback.link(
                "voxelr:shaders/program/voxel_fallback.vert",
                "voxelr:shaders/program/voxel_fallback.frag");
            voxelShaderFallback.use();
            voxelShaderFallback.setUniform1i("u_Atlas", 0);
            voxelShaderFallback.setUniform1i("buf_vd0", 1);
            voxelShaderFallback.setUniform1i("buf_vd1", 2);
            voxelShaderFallback.setUniform1i("buf_vd2", 3);
            voxelShaderFallback.setUniform1i("buf_vd3", 4);
            voxelShaderFallback.setUniform1i("buf_vd4", 5);
            voxelShaderFallback.setUniform1i("buf_vd5", 6);
            voxelShaderFallback.setUniform1i("buf_vd6", 7);
            voxelShaderFallback.setUniform1i("buf_vd7", 8);
            voxelShaderFallback.setUniform1i("buf_spatialIdx", 9);
            voxelShaderFallback.setUniform1i("buf_atlasUVs", 10);
            voxelShaderFallback.setUniform1i("buf_tintType", 11);
            voxelShaderFallback.setUniform1i("buf_chunkColors", 12);
            voxelShaderFallback.setUniform1i("buf_shapeOffsets", 13);
            voxelShaderFallback.setUniform1i("buf_shapeData", 14);
            voxelShaderFallback.setUniform1i("buf_occupancyMasks", 15);
            voxelShaderFallback.stop();
        }

        long capacityNodes = ssboManager.getCapacityNodes();
        chunkColorSsbo = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, chunkColorSsbo);
        glBufferData(GL_COPY_WRITE_BUFFER, capacityNodes * 3L * 4L, GL_DYNAMIC_DRAW);
        if (useSSBO) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 6, chunkColorSsbo);
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        VoxelRMod.LOGGER.info("[VoxelR] ChunkColor SSBO: {} capacity slots x 3 uints = {} KB",
            capacityNodes, capacityNodes * 3L * 4L / 1024);

        occupancySsbo = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, occupancySsbo);
        glBufferData(GL_COPY_WRITE_BUFFER, capacityNodes * 2L * 4L, GL_DYNAMIC_DRAW);
        if (useSSBO) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, occupancySsbo);
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        VoxelRMod.LOGGER.info("[VoxelR] Occupancy SSBO: {} slots x 2 uints = {} KB",
            capacityNodes, capacityNodes * 2L * 4L / 1024);

        if (useBufferTextures) {
            ssboManager.createBufferTextures(occupancySsbo);

            bufTexChunkColors = glGenTextures();
            glActiveTexture(GL_TEXTURE12);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexChunkColors);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, chunkColorSsbo);

        }

        initialized = true;

        if (VoxelRAPI.isAvailable()) {
            VoxelRAPI.get().events().RENDERER_READY.invoker().onRendererReady();
        }

        GlUtil.checkGlError("renderer_init");
        VoxelRMod.LOGGER.info("[VoxelR] GPU renderer initialized.");
    }

    public void buildAtlasUVBuffer() {
        if (!initialized) return;
        if (!atlasUVBuffer.isBuilt()) {
            VoxelRMod.LOGGER.info("[VoxelR] Building atlas UV buffer...");
            atlasUVBuffer.build();
            if (useBufferTextures) {
                atlasUVBuffer.createBufferTextures();
            }
        }
    }

    public void onBeforeChunks(LevelTerrainRenderContext ctx) {
        if (!VoxelRConfig.get().enabled) return;
        if (!initialized && !initFailed) initialize();
        if (!initialized || initFailed) return;

        frameIndex++;
        if (frameDrawn) return;
        frameDrawn = true;

        if (!atlasUVBuffer.isBuilt()) {
            buildAtlasUVBuffer();
        }

        long alloc = ssboManager.getAllocatedNodeCount();
        if (alloc != lastAllocatedCount) {
            lastAllocatedCount = alloc;
            VoxelRMod.LOGGER.info("[VoxelR] Nodes allocated on GPU: {}", alloc);
        }

        world.flushPendingDiffs();
        uploadDirtyNodes();

        buildSpatialIndex();
        useWaterShader = world.hasWaterInView(spatialOriginCx, spatialOriginCz, spatialIndexSizeX, spatialIndexSizeZ);

        if (!metaDumped && ssboManager.getAllocatedNodeCount() > 0) {
            metaDumped = true;
            ssboManager.dumpMetaDiagnostic();
        }

        Camera cam = getCamera();
        float[] vp = extractViewProjection();

        ssboManager.bindForRender();
        atlasUVBuffer.bind();

        long allocNodes = ssboManager.getAllocatedNodeCount();

        if (VoxelRAPI.isAvailable()) {
            RenderContext rctx = buildRenderContext(cam, vp, getTickDelta());
            cachedRenderContext = rctx;
            VoxelRAPI.get().events().BEFORE_OPAQUE_PASS.invoker()
                .onBeforeOpaquePass(rctx);
        }

        drawOpaquePass(cam, vp);

        if (frameIndex - lastDebugLog >= 120) {
            lastDebugLog = frameIndex;
            int nodeCount = (int) ssboManager.getAllocatedNodeCount();
            int opaqueDraws = visibleNodeCount;
            VoxelRMod.LOGGER.info("[VoxelR] frame={} nodes={} opaqueCmds={}",
                frameIndex, nodeCount, opaqueDraws);
        }

        if (VoxelRAPI.isAvailable()) {
            VoxelRAPI.get().events().AFTER_OPAQUE_PASS.invoker()
                .onAfterOpaquePass(cachedRenderContext);
        }
    }

    public void onAfterTranslucent(LevelRenderContext ctx) {
    }

    public void onFrameEnd(LevelRenderContext ctx) {
        if (!initialized || initFailed) return;
        frameDrawn = false;
        ShaderProgram activeShader = useSSBO ? (useWaterShader ? voxelShader : voxelShaderNoWater) : voxelShaderFallback;
        if (activeShader != null) activeShader.stop();
        if (VoxelRAPI.isAvailable() && cachedRenderContext != null) {
            VoxelRAPI.get().events().AFTER_FRAME.invoker().onAfterFrame(cachedRenderContext);
            float dt = (float) cachedRenderContext.getPartialTick();
            for (var pass : VoxelRAPI.get().render().getCustomPasses()) {
                pass.execute(dt);
            }
        }
    }

    private int getMainTargetFbo() {
        if (!mainFboCached) {
            try {
                RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
                GlTextureView colorView = (GlTextureView) target.getColorTextureView();
                cachedMainFbo = colorView.firstFboId;
            } catch (Exception e) {
                cachedMainFbo = 0;
            }
            mainFboCached = true;
        }
        return cachedMainFbo;
    }

    private static float getTickDelta() {
        try {
            return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        } catch (Exception e) {
            return 1.0f;
        }
    }

    private static Camera getCamera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
    }

    private float[] extractViewProjection() {
        var camState = Minecraft.getInstance().gameRenderer.getGameRenderState()
                         .levelRenderState.cameraRenderState;
        var pos = camState.pos;
        cachedVpMat.set(camState.viewRotationMatrix);
        cachedVpMat.translate(-(float)pos.x, -(float)pos.y, -(float)pos.z);
        cachedInvVpMat.set(camState.projectionMatrix).mul(cachedVpMat);
        cachedInvVpMat.get(cachedVpResult);
        return cachedVpResult;
    }



    private void buildSpatialIndex() {
        Camera cam = getCamera();
        int playerCx = ((int)Math.floor(cam.position().x)) >> 4;
        int playerCz = ((int)Math.floor(cam.position().z)) >> 4;

        boolean cameraMoved = playerCx != lastSpatialCx || playerCz != lastSpatialCz;
        if (!cameraMoved && !spatialIndexDirty && spatialIndexSsbo >= 0)
            return;

        lastSpatialCx = playerCx;
        lastSpatialCz = playerCz;
        spatialIndexDirty = false;

        int rd = VoxelRConfig.get().renderDistance;
        int sizeX = rd * 2 + 16;
        int sizeZ = rd * 2 + 16;
        if (sizeX < 64) sizeX = 64;
        if (sizeZ < 64) sizeZ = 64;
        int sizeY = 24;

        if (spatialIndexSsbo < 0 || spatialIndexSizeX != sizeX || spatialIndexSizeZ != sizeZ) {
            destroySpatialIndex();
            spatialIndexSizeX = sizeX;
            spatialIndexSizeZ = sizeZ;
            spatialIndexSizeY = sizeY;
            int total = sizeX * sizeZ * sizeY;
            spatialIndexSsbo = glGenBuffers();
            glBindBuffer(GL_COPY_WRITE_BUFFER, spatialIndexSsbo);
            glBufferData(GL_COPY_WRITE_BUFFER, (long) total * 4L, GL_DYNAMIC_DRAW);
            if (GlUtil.SSBO_ENABLED) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, spatialIndexSsbo);
            }
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        }

        spatialOriginCx = playerCx - sizeX / 2;
        spatialOriginCz = playerCz - sizeZ / 2;
        spatialOriginCy = -4;

        int total = sizeX * sizeZ * sizeY;
        ByteBuffer buf = MemoryUtil.memAlloc(total * 4);
        MemoryUtil.memSet(buf, 255);
        buf.position(0);
        for (ChunkNode node : world.getGrid().getAllNodes()) {
            int dx = node.cx - spatialOriginCx;
            int dz = node.cz - spatialOriginCz;
            int dy = node.cy - spatialOriginCy;
            if (dx < 0 || dx >= sizeX || dz < 0 || dz >= sizeZ || dy < 0 || dy >= sizeY) continue;
            int idx = ((dx * sizeZ + dz) * sizeY + dy);
            int slot = (int)(node.getSsboOffset() / ChunkNode.BYTES_PER_NODE);
            if (slot >= 0) {
                buf.putInt(idx * 4, slot);
            }
        }
        glBindBuffer(GL_COPY_WRITE_BUFFER, spatialIndexSsbo);
        buf.rewind();
        glBufferSubData(GL_COPY_WRITE_BUFFER, 0, buf);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        MemoryUtil.memFree(buf);

        if (useBufferTextures) {
            if (bufTexSpatialIdx > 0) { glDeleteTextures(bufTexSpatialIdx); bufTexSpatialIdx = 0; }
            bufTexSpatialIdx = glGenTextures();
            glActiveTexture(GL_TEXTURE9);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexSpatialIdx);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, spatialIndexSsbo);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    private void destroySpatialIndex() {
        if (bufTexSpatialIdx > 0) { glDeleteTextures(bufTexSpatialIdx); bufTexSpatialIdx = 0; }
        if (spatialIndexSsbo >= 0) { glDeleteBuffers(spatialIndexSsbo); spatialIndexSsbo = -1; }
        spatialIndexSizeX = spatialIndexSizeZ = spatialIndexSizeY = 0;
    }

    private void uploadDirtyNodes() {
        ChunkNode[] pendingFree = world.getGrid().drainPendingFreeSlots();
        ChunkNode[] freshDirty = world.getGrid().drainDirtyNodes();

        ChunkNode[] allDirty;
        if (leftoverDirtyNodes.length > 0) {
            allDirty = new ChunkNode[leftoverDirtyNodes.length + freshDirty.length];
            System.arraycopy(leftoverDirtyNodes, 0, allDirty, 0, leftoverDirtyNodes.length);
            System.arraycopy(freshDirty, 0, allDirty, leftoverDirtyNodes.length, freshDirty.length);
            leftoverDirtyNodes = new ChunkNode[0];
        } else {
            allDirty = freshDirty;
        }

        if (allDirty.length == 0 && pendingFree.length == 0) return;
        spatialIndexDirty = true;
        VoxelRMod.LOGGER.debug("[VoxelR] dirty={} pendingFree={} batch={}", allDirty.length, pendingFree.length, Math.min(allDirty.length, MAX_NODE_UPLOADS_PER_FRAME));

        for (ChunkNode node : pendingFree) {
            if (node.isAllocatedOnGpu()) {
                ssboManager.freeSlot(node);
                if (VoxelRAPI.isAvailable())
                    VoxelRAPI.get().events().CHUNK_NODE_FREED.invoker()
                        .onChunkNodeFreed(node.cx, node.cy, node.cz);
            }
        }

        int batchSize = Math.min(allDirty.length, MAX_NODE_UPLOADS_PER_FRAME);
        ByteBuffer reuseBuf = MemoryUtil.memAlloc(8192);
        for (int i = 0; i < batchSize; i++) {
            ChunkNode node = allDirty[i];
            if (node.isAir()) {
                if (node.isAllocatedOnGpu()) {
                    ssboManager.freeSlot(node);
                    if (VoxelRAPI.isAvailable())
                        VoxelRAPI.get().events().CHUNK_NODE_FREED.invoker()
                            .onChunkNodeFreed(node.cx, node.cy, node.cz);
                }
                world.getGrid().removeNode(node.cx, node.cy, node.cz);
                continue;
            }
            boolean wasNew = !node.isAllocatedOnGpu();
            ByteBuffer blockData = node.getBlockData();
            reuseBuf.clear();
            if (blockData != null) {
                for (int j = 0; j < 4096; j++) {
                    short id = blockData.getShort(j * 2);
                    reuseBuf.putShort(j * 2, id);
                }
            } else if (node.isUniform()) {
                short id = node.getUniformBlockId();
                for (int j = 0; j < 4096; j++) reuseBuf.putShort(j * 2, id);
            }
            reuseBuf.limit(8192);
            reuseBuf.position(0);
            ssboManager.uploadNode(node, reuseBuf, reuseBuf);
            VoxelRMod.LOGGER.debug("[VoxelR] uploadDirtyNodes: node ({},{},{}) allocated={}",
                node.cx, node.cy, node.cz, node.isAllocatedOnGpu());

            if (node.isAllocatedOnGpu()) {
                int slot = (int)(node.getSsboOffset() / ChunkNode.BYTES_PER_NODE);
                uploadChunkColors(slot, node.getFoliageColor(), node.getGrassColor(), node.getWaterColor());
                uploadOccupancyMask(slot, node);
            }

            if (node.isUniform()) {
                node.freeBlockData();
            }

            if (wasNew && node.isAllocatedOnGpu()) {
                if (VoxelRAPI.isAvailable())
                    VoxelRAPI.get().events().CHUNK_NODE_ALLOCATED.invoker()
                        .onChunkNodeAllocated(node.cx, node.cy, node.cz);
            }
        }
        MemoryUtil.memFree(reuseBuf);

        if (batchSize < allDirty.length) {
            leftoverDirtyNodes = new ChunkNode[allDirty.length - batchSize];
            System.arraycopy(allDirty, batchSize, leftoverDirtyNodes, 0, leftoverDirtyNodes.length);
        }

        GlUtil.checkGlError("upload_dirty_nodes");
    }

    private void uploadChunkColors(int slot, int foliageARGB, int grassARGB, int waterARGB) {
        if (chunkColorSsbo < 0) return;
        long base = (long) slot * 3L * 4L;
        ByteBuffer buf = MemoryUtil.memAlloc(12);
        buf.putInt(packRgb565(foliageARGB));
        buf.putInt(packRgb565(grassARGB));
        buf.putInt(packRgb565(waterARGB));
        buf.flip();
        glBindBuffer(GL_COPY_WRITE_BUFFER, chunkColorSsbo);
        glBufferSubData(GL_COPY_WRITE_BUFFER, base, buf);
        MemoryUtil.memFree(buf);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
    }

    private void uploadOccupancyMask(int slot, ChunkNode node) {
        if (occupancySsbo < 0) return;
        long mask = node.computeOccupancyMask();
        long base = (long) slot * 2L * 4L;
        ByteBuffer buf = MemoryUtil.memAlloc(8);
        buf.putInt((int)(mask & 0xFFFFFFFFL));
        buf.putInt((int)(mask >>> 32));
        buf.flip();
        glBindBuffer(GL_COPY_WRITE_BUFFER, occupancySsbo);
        glBufferSubData(GL_COPY_WRITE_BUFFER, base, buf);
        MemoryUtil.memFree(buf);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
    }

    private static int packRgb565(int argb) {
        int r = ((argb >> 16) & 0xFF) >> 3;
        int g = ((argb >>  8) & 0xFF) >> 2;
        int b = ( argb        & 0xFF) >> 3;
        return (r << 11) | (g << 5) | b;
    }

    private void drawOpaquePass(Camera cam, float[] vp) {
        int mainFbo = getMainTargetFbo();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBlendEquation(GL_FUNC_ADD);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);

        if (useBufferTextures) {
            drawFallback(cam, vp, mainFbo);
        } else {
            drawSSBO(cam, vp, mainFbo);
        }

        glCullFace(GL_BACK);
        if (mainFbo > 0) glBindFramebuffer(GL_DRAW_FRAMEBUFFER, mainFbo);

        visibleNodeCount = (int) ssboManager.getAllocatedNodeCount();
    }

    private void drawSSBO(Camera cam, float[] vp, int mainFbo) {
        blockShapeData.bindOffsets(7);
        blockShapeData.bindShape(8);

        ssboManager.bindForRender();
        if (spatialIndexSsbo >= 0) {
            if (GlUtil.SSBO_ENABLED) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, spatialIndexSsbo);
            }
        }
        atlasUVBuffer.bind();
        atlasUVBuffer.bindTintSsbo();

        if (mainFbo != 0) glBindFramebuffer(GL_DRAW_FRAMEBUFFER, mainFbo);
        doRayMarchSSBO(cam, vp, mainFbo);
    }

    private void drawFallback(Camera cam, float[] vp, int mainFbo) {
        blockShapeData.bindBufferTextures();
        atlasUVBuffer.bindBufferTextures();

        ssboManager.bindBufferTextures();
        if (bufTexSpatialIdx > 0) {
            glActiveTexture(GL_TEXTURE9);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexSpatialIdx);
        }
        if (bufTexChunkColors > 0) {
            glActiveTexture(GL_TEXTURE12);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexChunkColors);
        }
        glActiveTexture(GL_TEXTURE0);

        if (mainFbo != 0) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, mainFbo);
        }
        doRayMarchFallback(cam, vp, mainFbo);
    }

    private void setCommonUniforms(ShaderProgram shader, Camera cam, float[] vp, float dt) {
        shader.setUniformMatrix4fv("u_ViewProjection", false, vp);
        cachedVpMat.set(vp);
        cachedInvVpMat.set(cachedVpMat).invert();
        cachedInvVpMat.get(cachedInvVp);
        shader.setUniformMatrix4fv("u_InverseViewProjection", false, cachedInvVp);

        cachedDirVpMat.set(cam.getViewRotationProjectionMatrix(cachedDirVpMat));
        cachedInvDirVpMat.set(cachedDirVpMat).invert();
        cachedInvDirVpMat.get(cachedInvDirVp);
        shader.setUniformMatrix4fv("u_InverseDirProjection", false, cachedInvDirVp);

        shader.setUniform3f("u_CameraPos",
            (float) cam.position().x,
            (float) cam.position().y,
            (float) cam.position().z);
        float fogBlocks = VoxelRConfig.get().renderDistance * 16.0f;
        shader.setUniform1f("u_FogNear", fogBlocks * 0.75f);
        shader.setUniform1f("u_FogFar",  fogBlocks);
        shader.setUniform1i("u_Atlas", 0);
        shader.setUniform1ui("u_MaxSlots", (int) ssboManager.getSlotsPerBuffer());
        shader.setUniform1ui("u_HideFog", VoxelRConfig.get().hideFog ? 1 : 0);
        float ambient = 1.0f;
        float skyDarken = 0.0f;
        var mc = Minecraft.getInstance();
        if (mc.level != null) {
            skyDarken = mc.level.getSkyDarken();
            ambient = 0.3f + 0.7f * (1.0f - skyDarken);
        }
        shader.setUniform1f("u_AmbientLight", ambient);

        shader.setUniform3i("u_SpatialOrigin", spatialOriginCx, spatialOriginCz, spatialOriginCy);
        shader.setUniform3i("u_SpatialSize", spatialIndexSizeX, spatialIndexSizeZ, spatialIndexSizeY);
    }

    private void bindAtlasTexture() {
        atlasUVBuffer.bindAtlasTexture();
    }

    private void doRayMarchSSBO(Camera cam, float[] vp, int targetFbo) {
        ShaderProgram shader = useWaterShader ? voxelShader : voxelShaderNoWater;
        shader.use();
        setCommonUniforms(shader, cam, vp, getTickDelta());

        if (targetFbo > 0) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFbo);
            glClear(GL_DEPTH_BUFFER_BIT);
        }

        bindAtlasTexture();
        glBindVertexArray(rayMarchVao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        shader.stop();
    }

    private void doRayMarchFallback(Camera cam, float[] vp, int targetFbo) {
        voxelShaderFallback.use();
        setCommonUniforms(voxelShaderFallback, cam, vp, getTickDelta());

        if (targetFbo > 0) {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFbo);
            glClear(GL_DEPTH_BUFFER_BIT);
        }

        bindAtlasTexture();
        glBindVertexArray(rayMarchVao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        voxelShaderFallback.stop();
    }

    private RenderContext buildRenderContext(Camera cam, float[] vp, float dt) {
        return new RenderContext(
            frameIndex, visibleNodeCount,
            (int) ssboManager.getAllocatedNodeCount(),
            visibleNodeCount,
            dt,
            (float) cam.position().x,
            (float) cam.position().y,
            (float) cam.position().z
        );
    }

    public void onWorldUnload() {
        world.onWorldUnload();
        ssboManager.reset();
        mainFboCached = false;
        cachedMainFbo = 0;
        VoxelRMod.LOGGER.info("[VoxelR] World unloaded — SSBO reset.");
    }

    public void destroy() {
        if (!initialized) return;
        if (VoxelRAPI.isAvailable())
            VoxelRAPI.get().events().RENDERER_SHUTDOWN.invoker().onRendererShutdown();
        atlasUVBuffer.destroy();
        ssboManager.destroy();
        blockShapeData.destroy();
        if (voxelShader != null) voxelShader.destroy();
        if (voxelShaderNoWater != null) voxelShaderNoWater.destroy();
        if (voxelShaderFallback != null) voxelShaderFallback.destroy();
        if (bufTexChunkColors > 0) { glDeleteTextures(bufTexChunkColors); bufTexChunkColors = 0; }
        if (rayMarchVao >= 0) { glDeleteVertexArrays(rayMarchVao); rayMarchVao = -1; }
        destroySpatialIndex();
        initialized = false;
        VoxelRMod.LOGGER.info("[VoxelR] Renderer destroyed.");
    }

    public SSBOManager     getSsboManager()        { return ssboManager; }
    public AtlasUVBuffer   getAtlasUVBuffer()      { return atlasUVBuffer; }
    public long            getFrameIndex()          { return frameIndex; }
    public int             getVisibleNodeCount()    { return visibleNodeCount; }
    public boolean         isInitialized()          { return initialized; }
    public boolean         isInitFailed()           { return initFailed; }

    /** Returns estimated VRAM usage by VoxelR in bytes (buffers + textures). */
    public long getVramBytes() {
        long ssboDataVram = ssboManager.getCapacityNodes() * ChunkNode.BYTES_PER_NODE;
        long ssboMetaVram = ssboManager.getCapacityNodes() * 4L * 4L;
        long chunkColorVram = ssboManager.getCapacityNodes() * 3L * 4L;
        long occupancyVram = ssboManager.getCapacityNodes() * 2L * 4L;
        long spatialVram = (long) spatialIndexSizeX * spatialIndexSizeZ * spatialIndexSizeY * 4L;
        return ssboDataVram + ssboMetaVram + chunkColorVram + occupancyVram
             + spatialVram;
    }

    /** Returns estimated actual (committed) VRAM — only what's written to, not pre-allocated virtual. */
    public long getActualVramBytes() {
        long nodeCount = ssboManager.getAllocatedNodeCount();
        long nodeData = nodeCount * ChunkNode.BYTES_PER_NODE;
        long nodeMeta = nodeCount * 4L * 4L;
        long chunkColor = nodeCount * 3L * 4L;
        long occupancy = nodeCount * 2L * 4L;
        long spatialVram = (long) spatialIndexSizeX * spatialIndexSizeZ * spatialIndexSizeY * 4L;
        return nodeData + nodeMeta + chunkColor + occupancy + spatialVram;
    }

    public String getDebugInfo() {
        long nodeCount = ssboManager.getAllocatedNodeCount();
        long capacity = ssboManager.getCapacityNodes();
        long actualVram = getActualVramBytes();
        long preallocVram = getVramBytes();
        return String.format(
            "Nodes: %d/%d | VRAM: %dMB/%dMB | Pairs: %d",
            nodeCount, capacity,
            actualVram / (1024*1024), preallocVram / (1024*1024),
            ssboManager.getNumPairs());
    }
}
