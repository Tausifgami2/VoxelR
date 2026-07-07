#version 460 core

in vec2 v_ScreenPos;

layout(location = 0) out vec4 fragColor;

uniform mat4 u_ViewProjection;
uniform mat4 u_InverseViewProjection;
uniform mat4 u_InverseDirProjection;
uniform vec3 u_CameraPos;
uniform sampler2D u_Atlas;
uniform uint u_MaxSlots;

layout(std430, binding = 0) readonly buffer VoxelData0 { uint vd0[]; };
layout(std430, binding = 2) readonly buffer VoxelData1 { uint vd1[]; };
layout(std430, binding = 10) readonly buffer VoxelData2  { uint vd2[]; };
layout(std430, binding = 11) readonly buffer VoxelData3  { uint vd3[]; };
layout(std430, binding = 12) readonly buffer VoxelData4  { uint vd4[]; };
layout(std430, binding = 13) readonly buffer VoxelData5  { uint vd5[]; };
layout(std430, binding = 14) readonly buffer VoxelData6  { uint vd6[]; };
layout(std430, binding = 15) readonly buffer VoxelData7  { uint vd7[]; };
layout(std430, binding = 3) readonly buffer SpatialIndex {
    uint spatialIdx[];
};
layout(std430, binding = 4) readonly buffer AtlasUV {
    vec4 atlasUVs[];
};
layout(std430, binding = 5) readonly buffer TintType {
    uint tintType[];
};
layout(std430, binding = 6) readonly buffer ChunkColors {
    uint chunkColors[];
};
layout(std430, binding = 7) readonly buffer ShapeOffsets {
    int shapeOffsets[];
};
layout(std430, binding = 8) readonly buffer ShapeData {
    float shapeData[];
};
layout(std430, binding = 9) readonly buffer OccupancyMasks {
    uint occupancyMasks[];
};

uniform ivec3 u_SpatialOrigin;
uniform ivec3 u_SpatialSize;

const uint EMPTY_KEY   = 0xFFFFFFFFu;

const float FACE_SHADE[6] = float[6](0.8, 0.8, 1.0, 0.5, 0.6, 0.6);

uniform float u_FogFar;
uniform float u_FogNear;
uniform uint  u_HideFog;
uniform float u_AmbientLight;

uint getSlot(ivec3 cell) {
    int cx = cell.x >> 4;
    int cy = cell.y >> 4;
    int cz = cell.z >> 4;
    uint cyBits = uint(cy) & 0x3Fu;
    if (cyBits >= 20u && cyBits <= 59u) return EMPTY_KEY;
    int dx = cx - u_SpatialOrigin.x;
    int dz = cz - u_SpatialOrigin.y;
    int dy = cy - u_SpatialOrigin.z;
    if (dx < 0 || dx >= u_SpatialSize.x || dz < 0 || dz >= u_SpatialSize.y || dy < 0 || dy >= u_SpatialSize.z)
        return EMPTY_KEY;
    uint idx = uint((dx * u_SpatialSize.y + dz) * u_SpatialSize.z + dy);
    uint slot = spatialIdx[idx];
    return slot == 0xFFFFFFFFu ? EMPTY_KEY : slot;
}

uint readBlockId(uint slot, uint i) {
    uint pairIdx  = slot / u_MaxSlots;
    uint slotInPair = slot % u_MaxSlots;
    uint base = slotInPair * 2048u + (i >> 1u);
    uint word;
    if (pairIdx == 0u)      word = vd0[base];
    else if (pairIdx == 1u) word = vd1[base];
    else if (pairIdx == 2u) word = vd2[base];
    else if (pairIdx == 3u) word = vd3[base];
    else if (pairIdx == 4u) word = vd4[base];
    else if (pairIdx == 5u) word = vd5[base];
    else if (pairIdx == 6u) word = vd6[base];
    else if (pairIdx == 7u) word = vd7[base];
    else return 0u;
    return (i & 1u) == 0u ? (word & 0xFFFFu) : (word >> 16u);
}

bool hitTriangle(vec3 ro, vec3 rd, vec3 v0, vec3 v1, vec3 v2, out float t, out vec2 uv) {
    vec3 e1 = v1 - v0;
    vec3 e2 = v2 - v0;
    vec3 p = cross(rd, e2);
    float det = dot(e1, p);
    if (abs(det) < 1e-10) return false;
    float invDet = 1.0 / det;
    vec3 s = ro - v0;
    float u = dot(s, p) * invDet;
    if (u < 0.0 || u > 1.0) return false;
    vec3 q = cross(s, e1);
    float v = dot(rd, q) * invDet;
    if (v < 0.0 || u + v > 1.0) return false;
    t = dot(e2, q) * invDet;
    uv = vec2(u, v);
    return t > 0.0;
}

void main() {
    vec4 clipNear = u_InverseDirProjection * vec4(v_ScreenPos, -1.0, 1.0);
    vec3 worldNear = clipNear.xyz / clipNear.w;
    vec3 rayDir = normalize(worldNear);
    vec3 pos = u_CameraPos;

    ivec3 cell = ivec3(floor(pos));

    ivec3 step = ivec3(sign(rayDir));
    vec3 tDelta = abs(1.0 / rayDir);
    vec3 tMax;
    tMax.x = (step.x > 0 ? (float(cell.x) + 1.0 - pos.x) : (pos.x - float(cell.x))) * tDelta.x;
    tMax.y = (step.y > 0 ? (float(cell.y) + 1.0 - pos.y) : (pos.y - float(cell.y))) * tDelta.y;
    tMax.z = (step.z > 0 ? (float(cell.z) + 1.0 - pos.z) : (pos.z - float(cell.z))) * tDelta.z;

    float t = 0.0;
    uint lastFace = 0u;

    float maxDist = u_FogFar;
    uint maxSteps = uint(max(maxDist * 2.0, 64.0));
    if (rayDir.y > 0.0) {
        float yDist = max(320.0, u_CameraPos.y + 64.0) - u_CameraPos.y;
        if (yDist > 0.0) {
            uint ySteps = uint(yDist * 1.732 / max(rayDir.y, 0.0001) + 16.0);
            if (ySteps < maxSteps) maxSteps = ySteps;
        }
    } else if (rayDir.y < 0.0) {
        float yDist = u_CameraPos.y + 64.0;
        if (yDist > 0.0) {
            uint ySteps = uint(yDist * 1.732 / max(-rayDir.y, 0.0001) + 16.0);
            if (ySteps < maxSteps) maxSteps = ySteps;
        }
    }
    ivec3 curChunk = ivec3(-999);
    uint curSlot = EMPTY_KEY;
    uint curMaskLo = 0u, curMaskHi = 0u;

    for (uint i = 0u; i < maxSteps; i++) {
        if (t > maxDist) break;
        if (cell.y > max(320, int(u_CameraPos.y) + 64) || cell.y < -64) break;

        ivec3 chunkId = cell >> 4;
        if (any(notEqual(chunkId, curChunk))) {
            curChunk = chunkId;
            curSlot = getSlot(cell);
            if (curSlot != EMPTY_KEY) {
                curMaskLo = occupancyMasks[curSlot * 2u];
                curMaskHi = occupancyMasks[curSlot * 2u + 1u];
            }
        }

        if (curSlot == EMPTY_KEY) {
            ivec3 co = cell & ~15;
            ivec3 ce = co + 16;

            float dx = step.x > 0 ? float(ce.x - cell.x - 1) : float(cell.x - co.x);
            float dy = step.y > 0 ? float(ce.y - cell.y - 1) : float(cell.y - co.y);
            float dz = step.z > 0 ? float(ce.z - cell.z - 1) : float(cell.z - co.z);

            float tExitX = tMax.x + tDelta.x * dx;
            float tExitY = tMax.y + tDelta.y * dy;
            float tExitZ = tMax.z + tDelta.z * dz;

            float tExit = min(min(tExitX, tExitY), tExitZ);
            t = tExit + 1e-4;
            vec3 newPos = pos + rayDir * t;
            cell = ivec3(floor(newPos));

            tMax.x = (step.x > 0 ? (float(cell.x) + 1.0 - newPos.x) : (newPos.x - float(cell.x))) * tDelta.x + t;
            tMax.y = (step.y > 0 ? (float(cell.y) + 1.0 - newPos.y) : (newPos.y - float(cell.y))) * tDelta.y + t;
            tMax.z = (step.z > 0 ? (float(cell.z) + 1.0 - newPos.z) : (newPos.z - float(cell.z))) * tDelta.z + t;

            if (tExitX <= tExitY && tExitX <= tExitZ) lastFace = step.x > 0 ? 1u : 0u;
            else if (tExitY <= tExitZ)                lastFace = step.y > 0 ? 3u : 2u;
            else                                       lastFace = step.z > 0 ? 5u : 4u;

            curChunk = ivec3(-999);
            continue;
        }

        // 4^3 empty skip
        {
            int sx = (cell.x & 15) / 4;
            int sy = (cell.y & 15) / 4;
            int sz = (cell.z & 15) / 4;
            uint subIdx = uint(sy * 16 + sz * 4 + sx);
            uint word = subIdx < 32u ? curMaskLo : curMaskHi;
            uint bit = word >> (subIdx & 31u);
            if ((bit & 1u) == 0u) {
                ivec3 subStart = cell & ~3;
                ivec3 subEnd = subStart + 4;

                float dx = step.x > 0 ? float(subEnd.x - cell.x - 1) : float(cell.x - subStart.x);
                float dy = step.y > 0 ? float(subEnd.y - cell.y - 1) : float(cell.y - subStart.y);
                float dz = step.z > 0 ? float(subEnd.z - cell.z - 1) : float(cell.z - subStart.z);

                float tExitX = tMax.x + tDelta.x * dx;
                float tExitY = tMax.y + tDelta.y * dy;
                float tExitZ = tMax.z + tDelta.z * dz;

                float tExit = min(min(tExitX, tExitY), tExitZ);
                t = tExit + 1e-4;
                vec3 newPos = pos + rayDir * t;
                cell = ivec3(floor(newPos));

                tMax.x = (step.x > 0 ? (float(cell.x) + 1.0 - newPos.x) : (newPos.x - float(cell.x))) * tDelta.x + t;
                tMax.y = (step.y > 0 ? (float(cell.y) + 1.0 - newPos.y) : (newPos.y - float(cell.y))) * tDelta.y + t;
                tMax.z = (step.z > 0 ? (float(cell.z) + 1.0 - newPos.z) : (newPos.z - float(cell.z))) * tDelta.z + t;

                if (tExitX <= tExitY && tExitX <= tExitZ) lastFace = step.x > 0 ? 1u : 0u;
                else if (tExitY <= tExitZ)                lastFace = step.y > 0 ? 3u : 2u;
                else                                       lastFace = step.z > 0 ? 5u : 4u;

                curChunk = ivec3(-999);
                continue;
            }
        }

        uint bi = 0u;
        {
            int lx = cell.x & 15;
            int ly = cell.y & 15;
            int lz = cell.z & 15;
            uint idx = uint((ly << 8) | (lz << 4) | lx);
            bi = readBlockId(curSlot, idx);
        }

        if (bi != 0u && shapeOffsets[bi] == -1) {
            uint tt = tintType[bi];
            uint atlasFace = lastFace ^ 1u;
            vec4 uvRect = atlasUVs[bi * 6u + atlasFace];
            vec3 hitPos = pos + rayDir * t;
            vec3 fp = fract(hitPos);
            vec2 uv;
            if (lastFace == 0u || lastFace == 1u) {
                uv = vec2(fp.z, fp.y);
            } else if (lastFace == 2u || lastFace == 3u) {
                uv = vec2(fp.x, fp.z);
            } else {
                uv = vec2(fp.x, fp.y);
            }
            vec2 atlasUv = vec2(mix(uvRect.x, uvRect.y, uv.x),
                (lastFace == 0u || lastFace == 1u || lastFace == 4u || lastFace == 5u)
                ? mix(uvRect.w, uvRect.z, uv.y)
                : mix(uvRect.z, uvRect.w, uv.y));
            vec4 texColor = texture(u_Atlas, atlasUv);

            if (texColor.a >= 0.5) {
                vec3 depthPos = pos + rayDir * t;
                vec4 clipPos = u_ViewProjection * vec4(depthPos, 1.0);
                gl_FragDepth = clipPos.z / clipPos.w * 0.5 + 0.5;

                float shade = FACE_SHADE[lastFace];
                vec3 col = texColor.rgb * shade;

                if (tt != 0u) {
                    uint base = curSlot * 3u;
                    uint pk;
                    if (tt == 1u)      pk = chunkColors[base + 1u];
                    else if (tt == 2u) pk = chunkColors[base];
                    else               pk = chunkColors[base + 2u];
                    vec3 tintColor = vec3(
                        float((pk >> 11) & 0x1Fu) / 31.0,
                        float((pk >> 5)  & 0x3Fu) / 63.0,
                        float( pk        & 0x1Fu) / 31.0
                    );
                    if (tt == 1u && (lastFace == 0u || lastFace == 1u || lastFace == 4u || lastFace == 5u)) {
                        float t = smoothstep(0.7, 0.95, uv.y);
                        col *= mix(vec3(1.0), tintColor, t);
                    } else {
                        col *= tintColor;
                    }
                }

                float dist = length(hitPos - u_CameraPos);
                if (u_HideFog == 0u) {
                    float fog = clamp((dist - u_FogNear) / max(u_FogFar - u_FogNear, 1.0), 0.0, 1.0);
                    vec3 fogColor = mix(vec3(0.05, 0.05, 0.15), vec3(0.63, 0.73, 0.92), u_AmbientLight);
                    col = mix(col, fogColor, fog * fog);
                }

                col *= clamp(u_AmbientLight, 0.1, 1.0);
                fragColor = vec4(col, 1.0);
                return;
            }
        }
        else if (shapeOffsets[bi] >= 0) { // Slabs, stairs
            int shapeOff = shapeOffsets[bi];
            float numAABBs_f = shapeData[shapeOff];
            int numAABBs = int(numAABBs_f + 0.5);
            int dataOff = shapeOff + 1;

            float tExit = min(min(tMax.x, tMax.y), tMax.z);
            vec3 ro = pos + rayDir * t;
            vec3 rd = rayDir;
            vec3 voxOrigin = vec3(float(cell.x), float(cell.y), float(cell.z));
            vec3 invDir = 1.0 / max(abs(rd), 1e-10) * vec3(
                rd.x >= 0.0 ? 1.0 : -1.0,
                rd.y >= 0.0 ? 1.0 : -1.0,
                rd.z >= 0.0 ? 1.0 : -1.0
            );

            float bestT = 1e10;
            int bestFace = -1;
            vec2 bestUV = vec2(0.0);
            vec3 bestHitWorld = vec3(0.0);

            for (int ai = 0; ai < numAABBs; ai++) {
                vec3 boxMin = vec3(shapeData[dataOff], shapeData[dataOff+1], shapeData[dataOff+2]);
                vec3 boxMax = vec3(shapeData[dataOff+3], shapeData[dataOff+4], shapeData[dataOff+5]);
                int faceMask = int(shapeData[dataOff+6] + 0.5);
                dataOff += 7;

                vec3 aMin = voxOrigin + boxMin;
                vec3 aMax = voxOrigin + boxMax;

                vec3 t1 = (aMin - ro) * invDir;
                vec3 t2 = (aMax - ro) * invDir;
                vec3 tminV = min(t1, t2);
                vec3 tmaxV = max(t1, t2);
                float tNear = max(max(tminV.x, tminV.y), tminV.z);
                float tFar = min(min(tmaxV.x, tmaxV.y), tmaxV.z);

                if (tNear < tFar && tFar > 0.0 && tNear < tExit && tNear < bestT) {
                    float tHit = max(tNear, 0.0);

                    int axis = 0;
                    float bestDist = abs(tNear - tminV.x);
                    if (abs(tNear - tminV.y) < bestDist) { axis = 1; bestDist = abs(tNear - tminV.y); }
                    if (abs(tNear - tminV.z) < bestDist) { axis = 2; }

                    int face = rd[axis] > 0.0 ? (axis * 2) : (axis * 2 + 1);

                    if ((faceMask & (1 << face)) == 0) continue;

                    vec3 hitWorld = ro + rd * tHit;
                    vec3 hitLocal = hitWorld - voxOrigin;

                    vec2 uv;
                    if (face == 0 || face == 1) {
                        uv = vec2(hitLocal.z, hitLocal.y);
                    } else if (face == 2 || face == 3) {
                        uv = vec2(hitLocal.x, hitLocal.z);
                    } else {
                        uv = vec2(hitLocal.x, hitLocal.y);
                    }

                    bestT = tHit;
                    bestFace = face;
                    bestUV = uv;
                    bestHitWorld = hitWorld;
                }
            }

            if (bestFace >= 0) {
                uint atlasFace = uint(bestFace);
                vec4 uvRect = atlasUVs[bi * 6u + atlasFace];
                vec2 atlasUv = vec2(mix(uvRect.x, uvRect.y, bestUV.x),
                                    (bestFace == 0 || bestFace == 1 || bestFace == 4 || bestFace == 5)
                                    ? mix(uvRect.w, uvRect.z, bestUV.y)
                                    : mix(uvRect.z, uvRect.w, bestUV.y));
                vec4 texColor = texture(u_Atlas, atlasUv);

                if (texColor.a >= 0.5) {
                    vec4 clipPos = u_ViewProjection * vec4(bestHitWorld, 1.0);
                    gl_FragDepth = clipPos.z / clipPos.w * 0.5 + 0.5;

                    float shade = FACE_SHADE[bestFace ^ 1];
                    vec3 col = texColor.rgb * shade;

                    uint tt = tintType[bi];
                    if (tt != 0u) {
                        uint base = curSlot * 3u;
                        uint pk;
                        if (tt == 1u)      pk = chunkColors[base + 1u];
                        else if (tt == 2u) pk = chunkColors[base];
                        else               pk = chunkColors[base + 2u];
                        vec3 tintColor = vec3(
                            float((pk >> 11) & 0x1Fu) / 31.0,
                            float((pk >> 5)  & 0x3Fu) / 63.0,
                            float( pk        & 0x1Fu) / 31.0
                        );
                        col *= tintColor;
                    }

                    float dist = length(bestHitWorld - u_CameraPos);
                    if (u_HideFog == 0u) {
                        float fog = clamp((dist - u_FogNear) / max(u_FogFar - u_FogNear, 1.0), 0.0, 1.0);
                        vec3 fogColor = mix(vec3(0.05, 0.05, 0.15), vec3(0.63, 0.73, 0.92), u_AmbientLight);
                        col = mix(col, fogColor, fog * fog);
                    }

                    col *= clamp(u_AmbientLight, 0.1, 1.0);
                    fragColor = vec4(col, 1.0);
                    return;
                }
            }
        }
        else if (shapeOffsets[bi] < -1) { // Mesh blocks (plants)
            int triOff = (-shapeOffsets[bi]) - 2;
            float numTris_f = shapeData[triOff];
            int numTris = int(-numTris_f + 0.5);
            float flags = shapeData[triOff + 1];
            bool useOffset = flags >= 0.5;
            int dataOff = triOff + 2;

            float tExit = min(min(tMax.x, tMax.y), tMax.z);
            vec3 ro = pos + rayDir * t;
            vec3 rd = rayDir;
            vec3 voxOrigin = vec3(float(cell.x), float(cell.y), float(cell.z));

            if (useOffset) {
                uint seed = uint(cell.x) * 374761393u + uint(cell.y) * 668265263u + uint(cell.z) * 1274126177u;
                seed = (seed ^ (seed >> 13u)) * 1274126177u;
                vec2 off = vec2(float(seed & 0xFFu), float((seed >> 8u) & 0xFFu)) / 255.0 * 0.24 - 0.12;
                voxOrigin += vec3(off.x, 0.0, off.y);
            }

            vec3 localRo = ro - voxOrigin;

            float bestT = 1e10;
            vec2 bestUV = vec2(0.0);
            vec3 bestHitWorld = vec3(0.0);
            vec4 texColor = vec4(0.0);
            bool gotHit = false;

            for (int ti = 0; ti < numTris; ti++) {
                vec3 v0 = vec3(shapeData[dataOff], shapeData[dataOff+1], shapeData[dataOff+2]);
                vec2 uv0 = vec2(shapeData[dataOff+3], shapeData[dataOff+4]);
                vec3 v1 = vec3(shapeData[dataOff+5], shapeData[dataOff+6], shapeData[dataOff+7]);
                vec2 uv1 = vec2(shapeData[dataOff+8], shapeData[dataOff+9]);
                vec3 v2 = vec3(shapeData[dataOff+10], shapeData[dataOff+11], shapeData[dataOff+12]);
                vec2 uv2 = vec2(shapeData[dataOff+13], shapeData[dataOff+14]);
                dataOff += 15;

                float tTri;
                vec2 bary;
                if (hitTriangle(localRo, rd, v0, v1, v2, tTri, bary) && tTri < tExit && tTri < bestT) {
                    vec2 baryUv = uv0 + (uv1 - uv0) * bary.x + (uv2 - uv0) * bary.y;
                    vec4 texel;
                    if (useOffset) {
                        vec4 uvRect = atlasUVs[bi * 6u];
                        vec2 atlasUv = vec2(mix(uvRect.x, uvRect.y, baryUv.x),
                                            mix(uvRect.w, uvRect.z, baryUv.y));
                        texel = texture(u_Atlas, atlasUv);
                    } else {
                        texel = texture(u_Atlas, baryUv);
                    }
                    if (texel.a >= 0.5) {
                        bestT = tTri;
                        bestUV = baryUv;
                        bestHitWorld = ro + rd * tTri;
                        texColor = texel;
                        gotHit = true;
                    }
                }
            }

            if (gotHit) {
                vec4 clipPos = u_ViewProjection * vec4(bestHitWorld, 1.0);
                gl_FragDepth = clipPos.z / clipPos.w * 0.5 + 0.5;

                float shade = 0.8;
                vec3 col = texColor.rgb * shade;

                uint tt = tintType[bi];
                if (tt != 0u) {
                    uint base = curSlot * 3u;
                    uint pk;
                    if (tt == 1u)      pk = chunkColors[base + 1u];
                    else if (tt == 2u) pk = chunkColors[base];
                    else               pk = chunkColors[base + 2u];
                    vec3 tintColor = vec3(
                        float((pk >> 11) & 0x1Fu) / 31.0,
                        float((pk >> 5)  & 0x3Fu) / 63.0,
                        float( pk        & 0x1Fu) / 31.0
                    );
                    col *= tintColor;
                }

                float dist = length(bestHitWorld - u_CameraPos);
                if (u_HideFog == 0u) {
                    float fog = clamp((dist - u_FogNear) / max(u_FogFar - u_FogNear, 1.0), 0.0, 1.0);
                    vec3 fogColor = mix(vec3(0.05, 0.05, 0.15), vec3(0.63, 0.73, 0.92), u_AmbientLight);
                    col = mix(col, fogColor, fog * fog);
                }

                col *= clamp(u_AmbientLight, 0.1, 1.0);
                fragColor = vec4(col, 1.0);
                return;
            }
        }



        if (tMax.x < tMax.y) {
            if (tMax.x < tMax.z) {
                t = tMax.x;
                cell.x += step.x;
                tMax.x += tDelta.x;
                lastFace = step.x > 0 ? 1u : 0u;
            } else {
                t = tMax.z;
                cell.z += step.z;
                tMax.z += tDelta.z;
                lastFace = step.z > 0 ? 5u : 4u;
            }
        } else {
            if (tMax.y < tMax.z) {
                t = tMax.y;
                cell.y += step.y;
                tMax.y += tDelta.y;
                lastFace = step.y > 0 ? 3u : 2u;
            } else {
                t = tMax.z;
                cell.z += step.z;
                tMax.z += tDelta.z;
                lastFace = step.z > 0 ? 5u : 4u;
            }
        }
    }

    gl_FragDepth = 1.0;
    vec3 fogColor = mix(vec3(0.02, 0.02, 0.08), vec3(0.63, 0.73, 0.92), clamp(u_AmbientLight * 1.5, 0.0, 1.0));
    fragColor = vec4(fogColor, 0.0);
}
