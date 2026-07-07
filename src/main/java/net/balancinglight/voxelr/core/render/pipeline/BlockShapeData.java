package net.balancinglight.voxelr.core.render.pipeline;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.balancinglight.voxelr.VoxelRMod;
import net.balancinglight.voxelr.util.GlUtil;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL43.*;

public final class BlockShapeData {

    private int offsetsSsbo = -1;
    private int shapeSsbo = -1;
    private int bufTexOffsets = 0;  // buffer texture wrapping offsetsSsbo (unit 13, R32I)
    private int bufTexShape = 0;    // buffer texture wrapping shapeSsbo (unit 14, R32F)
    private static final int OFFSETS = 65536;
    private static final int DIR_WEST = 4, DIR_EAST = 5, DIR_DOWN = 0, DIR_UP = 1, DIR_NORTH = 2, DIR_SOUTH = 3;

    private Set<String> crossOverrideSet = null;

    @SuppressWarnings("unchecked")
    private Set<String> loadCrossOverrideSet() {
        var set = new HashSet<String>();
        try (var in = VoxelRMod.class.getResourceAsStream("/assets/voxelr/cross_blocks.json")) {
            if (in == null) {
                VoxelRMod.LOGGER.warn("[VoxelR]  cross_blocks.json not found on classpath");
                return set;
            }
            Map<String, Object> root = new Gson().fromJson(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), Map.class);
            var blocks = (List<String>) root.get("blocks");
            if (blocks != null) {
                for (String b : blocks) set.add(b.trim());
            }
            VoxelRMod.LOGGER.info("[VoxelR]  Loaded {} cross-block overrides from cross_blocks.json", set.size());
        } catch (Exception e) {
            VoxelRMod.LOGGER.warn("[VoxelR]  Failed to load cross_blocks.json", e);
        }
        return set;
    }

    public void initialize() {
        long t0 = System.nanoTime();

        // Check total block state count
        int stateCount = 0;
        for (int id = 0; id < OFFSETS; id++) {
            BlockState st = Block.stateById(id);
            if (st == null) break;
            if (!st.isAir()) stateCount++;
        }
        int extraStates = 0;
        for (int id = OFFSETS; id < OFFSETS + 50000; id++) {
            BlockState st = Block.stateById(id);
            if (st == null || st.isAir()) break;
            extraStates++;
        }
        if (extraStates > 0) {
            VoxelRMod.LOGGER.warn("[VoxelR]  WARNING: {} non-air block states beyond 65535! These blocks have ID=0 (invisible).", extraStates);
        }
        VoxelRMod.LOGGER.info("[VoxelR]  Total non-air block states in range 0-65535: {}", stateCount);

        // Use a proper entity collision context so blocks like snow layers return correct shapes
        CollisionContext collisionCtx;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            collisionCtx = CollisionContext.of(mc.player);
        } else {
            collisionCtx = CollisionContext.empty();
        }

        List<float[]> allShapes = new ArrayList<>(65536);
        int[] offsets = new int[OFFSETS];
        int cursor = 0;
        for (int id = 0; id < OFFSETS; id++) {
            BlockState st = Block.stateById(id);
            if (st == null || st.isAir()) { offsets[id] = -1; continue; }

            VoxelShape vs;
            try {
                vs = st.getShape(null, net.minecraft.core.BlockPos.ZERO, CollisionContext.empty());
            } catch (Exception e) {
                offsets[id] = -1;
                continue;
            }

            double offX = 0, offY = 0, offZ = 0;
            if (st.hasOffsetFunction()) {
                net.minecraft.world.phys.Vec3 off = st.getOffset(net.minecraft.core.BlockPos.ZERO);
                offX = off.x; offY = off.y; offZ = off.z;
            }

            List<AABB> rawBoxes = vs.toAabbs();
            if (rawBoxes.isEmpty()) { offsets[id] = -1; continue; }

            // Re-center boxes by subtracting the baked offset
            List<AABB> boxes = new java.util.ArrayList<>(rawBoxes.size());
            for (AABB raw : rawBoxes) {
                boxes.add(raw.move(-offX, -offY, -offZ));
            }

            if (boxes.size() == 1) {
                AABB b = boxes.get(0);
                if (isFullCube(b)) {
                    offsets[id] = -1;
                    continue;
                }
            }

            // Store AABB data for non-full blocks
            offsets[id] = cursor;
            allShapes.add(new float[]{ (float) boxes.size() });
            cursor++;

            for (int i = 0; i < boxes.size(); i++) {
                AABB box = boxes.get(i);
                int mask = 0;
                if (!isInternalFace(box, boxes, i, DIR_WEST))  mask |= 1;
                if (!isInternalFace(box, boxes, i, DIR_EAST))  mask |= 2;
                if (!isInternalFace(box, boxes, i, DIR_DOWN))  mask |= 4;
                if (!isInternalFace(box, boxes, i, DIR_UP))    mask |= 8;
                if (!isInternalFace(box, boxes, i, DIR_NORTH)) mask |= 16;
                if (!isInternalFace(box, boxes, i, DIR_SOUTH)) mask |= 32;
                allShapes.add(new float[]{(float)box.minX, (float)box.minY, (float)box.minZ,
                                          (float)box.maxX, (float)box.maxY, (float)box.maxZ,
                                          (float)mask});
                cursor += 7;
            }
        }

        int triangleCursor = cursor;
        int crossCount2 = 0;
        for (int id = 0; id < OFFSETS; id++) {
            if (offsets[id] != -1) continue; // only blocks with empty/full-cube visual
            BlockState st = Block.stateById(id);
            if (st == null || st.isAir()) continue;
            if (!st.getFluidState().isEmpty()) continue;

            VoxelShape colShape;
            try {
                colShape = st.getCollisionShape(null, net.minecraft.core.BlockPos.ZERO, collisionCtx);
            } catch (Exception e) { colShape = null; }
            if (colShape != null && !colShape.toAabbs().isEmpty()) continue;

            // Verify visual IS actually empty (not a full-cube that was filtered by pass 1)
            VoxelShape visShape;
            try {
                visShape = st.getShape(null, net.minecraft.core.BlockPos.ZERO, CollisionContext.empty());
            } catch (Exception e) { continue; }
            if (!visShape.toAabbs().isEmpty()) continue; // has visual AABB → full cube, not cross

            int dataStart = triangleCursor;
            addCrossTriangles(allShapes);
            triangleCursor += 62;

            offsets[id] = -(dataStart + 2);
            crossCount2++;
            if (crossCount2 <= 20 || (crossCount2 % 50 == 0)) {
                String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                VoxelRMod.LOGGER.info("[VoxelR]  Cross block #{}: id={} path='{}' dataOff={}",
                    crossCount2, id, path, dataStart);
            }
        }

        crossOverrideSet = loadCrossOverrideSet();
        if (!crossOverrideSet.isEmpty()) {
            int overrideCount = 0;
            for (int id = 0; id < OFFSETS; id++) {
                BlockState st = Block.stateById(id);
                if (st == null || st.isAir()) continue;
                if (offsets[id] < -1) continue; // already marked as cross/mesh
                String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                if (!crossOverrideSet.contains(path)) continue;

                int dataStart = triangleCursor;
                addCrossTriangles(allShapes);
                triangleCursor += 62;

                offsets[id] = -(dataStart + 2);
                overrideCount++;
                if (overrideCount <= 10 || (overrideCount % 50 == 0)) {
                    VoxelRMod.LOGGER.info("[VoxelR]  Cross override: id={} path='{}' dataOff={}",
                        id, path, dataStart);
                }
            }
            if (overrideCount > 10) {
                VoxelRMod.LOGGER.info("[VoxelR]  ... and {} more cross overrides (total {})",
                    overrideCount - 10, overrideCount);
            }
            VoxelRMod.LOGGER.info("[VoxelR]  Applied {} cross-block overrides from JSON", overrideCount);
        }

        // Build offset SSBO
        ByteBuffer offBuf = MemoryUtil.memAlloc(OFFSETS * 4);
        IntBuffer ib = offBuf.asIntBuffer();
        for (int i = 0; i < OFFSETS; i++) ib.put(offsets[i]);
        offBuf.position(0).limit(OFFSETS * 4);

        offsetsSsbo = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, offsetsSsbo);
        glBufferData(GL_COPY_WRITE_BUFFER, offBuf, GL_STATIC_DRAW);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        MemoryUtil.memFree(offBuf);

        // Build shape SSBO
        ByteBuffer shapeBuf = MemoryUtil.memAlloc(triangleCursor * 4);
        for (float[] entry : allShapes) {
            for (float v : entry) shapeBuf.putFloat(v);
        }
        shapeBuf.flip();

        shapeSsbo = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, shapeSsbo);
        glBufferData(GL_COPY_WRITE_BUFFER, shapeBuf, GL_STATIC_DRAW);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        MemoryUtil.memFree(shapeBuf);

        long t1 = System.nanoTime();
        int aabbCount = 0, crossCount = 0;
        for (int i = 0; i < OFFSETS; i++) {
            if (offsets[i] >= 0) aabbCount++;
            else if (offsets[i] < -1) crossCount++;
        }
        VoxelRMod.LOGGER.info("[VoxelR] BlockShapeData: {} AABB types, {} cross types (detected {}), {} bytes shape SSBO + {} bytes offset SSBO, took {}ms",
            aabbCount, crossCount, crossCount2, triangleCursor * 4, OFFSETS * 4, (t1 - t0) / 1000000);
    }

    private static boolean isFullCube(AABB b) {
        return b.minX <= 0.001 && b.maxX >= 0.999 && b.minY <= 0.001 && b.maxY >= 0.999 && b.minZ <= 0.001 && b.maxZ >= 0.999;
    }

    private static void addCrossTriangles(List<float[]> allShapes) {
        allShapes.add(new float[]{ -4.0f }); // negative = triangle marker, value = 4 triangles
        allShapes.add(new float[]{ 1.0f });  // flags: 1 = random XZ offset enabled

        // Quad 1 (NW-SE diagonal): (0,0,0)-(1,1,1)
        // Triangle 1: (0,0,0), (1,0,1), (1,1,1)  uv: (0,0), (1,0), (1,1)
        allShapes.add(new float[]{ 0,0,0, 0,0,  1,0,1, 1,0,  1,1,1, 1,1 });
        // Triangle 2: (0,0,0), (1,1,1), (0,1,0)  uv: (0,0), (1,1), (0,1)
        allShapes.add(new float[]{ 0,0,0, 0,0,  1,1,1, 1,1,  0,1,0, 0,1 });

        // Quad 2 (SW-NE diagonal): (1,0,0)-(0,1,1)
        // Triangle 3: (1,0,0), (0,0,1), (0,1,1)  uv: (0,0), (1,0), (1,1)
        allShapes.add(new float[]{ 1,0,0, 0,0,  0,0,1, 1,0,  0,1,1, 1,1 });
        // Triangle 4: (1,0,0), (0,1,1), (1,1,0)  uv: (0,0), (1,1), (0,1)
        allShapes.add(new float[]{ 1,0,0, 0,0,  0,1,1, 1,1,  1,1,0, 0,1 });
    }

    private static boolean isInternalFace(AABB box, List<AABB> all, int index, int dir) {
        for (int j = 0; j < all.size(); j++) {
            if (j == index) continue;
            AABB other = all.get(j);
            boolean adjacent;
            switch (dir) {
                case 4: adjacent = other.maxX == box.minX; break;
                case 5: adjacent = other.minX == box.maxX; break;
                case 0: adjacent = other.maxY == box.minY; break;
                case 1: adjacent = other.minY == box.maxY; break;
                case 2: adjacent = other.maxZ == box.minZ; break;
                case 3: adjacent = other.minZ == box.maxZ; break;
                default: adjacent = false;
            }
            if (!adjacent) continue;
            switch (dir) {
                case 4: case 5:
                    if (other.minY <= box.minY && other.maxY >= box.maxY &&
                        other.minZ <= box.minZ && other.maxZ >= box.maxZ) return true;
                    break;
                case 0: case 1:
                    if (other.minX <= box.minX && other.maxX >= box.maxX &&
                        other.minZ <= box.minZ && other.maxZ >= box.maxZ) return true;
                    break;
                case 2: case 3:
                    if (other.minX <= box.minX && other.maxX >= box.maxX &&
                        other.minY <= box.minY && other.maxY >= box.maxY) return true;
                    break;
            }
        }
        return false;
    }

    public void bindOffsets(int binding) {
        if (GlUtil.SSBO_ENABLED) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, offsetsSsbo);
        }
    }

    public void bindShape(int binding) {
        if (GlUtil.SSBO_ENABLED) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, shapeSsbo);
        }
    }

    public void createBufferTextures() {
        if (offsetsSsbo >= 0 && bufTexOffsets == 0) {
            bufTexOffsets = glGenTextures();
            glActiveTexture(GL_TEXTURE13);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexOffsets);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32I, offsetsSsbo);
        }
        if (shapeSsbo >= 0 && bufTexShape == 0) {
            bufTexShape = glGenTextures();
            glActiveTexture(GL_TEXTURE14);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexShape);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32F, shapeSsbo);
        }
        glActiveTexture(GL_TEXTURE0);
    }

    public void bindBufferTextures() {
        if (bufTexOffsets > 0) {
            glActiveTexture(GL_TEXTURE13);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexOffsets);
        }
        if (bufTexShape > 0) {
            glActiveTexture(GL_TEXTURE14);
            glBindTexture(GL_TEXTURE_BUFFER, bufTexShape);
        }
    }

    public void destroy() {
        if (bufTexOffsets > 0) { glDeleteTextures(bufTexOffsets); bufTexOffsets = 0; }
        if (bufTexShape > 0) { glDeleteTextures(bufTexShape); bufTexShape = 0; }
        if (offsetsSsbo >= 0) { glDeleteBuffers(offsetsSsbo); offsetsSsbo = -1; }
        if (shapeSsbo >= 0) { glDeleteBuffers(shapeSsbo); shapeSsbo = -1; }
    }
}
