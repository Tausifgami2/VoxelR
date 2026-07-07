package net.balancinglight.voxelr.api.block;

/**
 * Describes how a specific block should be treated by the VoxelR GPU pipeline.
 */
public final class BlockRenderProfile {

    public enum RenderType {
        /** Standard full-cube, opaque block (default). */
        FULL_CUBE,
        /** Fully transparent — skip in opaque pass, include in transparent pass. */
        TRANSPARENT,
        /** Emissive block — skip light attenuation in shader. */
        EMISSIVE,
        /** Non-cube block — use template geometry profile (Phase 6). */
        CUSTOM_GEOMETRY,
        /** Completely invisible (air, barrier-like blocks). */
        AIR
    }

    private final RenderType renderType;
    private final boolean castsShadow;
    private final float lightEmission;
    private final int textureAtlasIndex;

    private BlockRenderProfile(Builder b) {
        this.renderType = b.renderType;
        this.castsShadow = b.castsShadow;
        this.lightEmission = b.lightEmission;
        this.textureAtlasIndex = b.textureAtlasIndex;
    }

    public RenderType getRenderType() { return renderType; }
    public boolean castsShadow() { return castsShadow; }
    public float getLightEmission() { return lightEmission; }
    public int getTextureAtlasIndex() { return textureAtlasIndex; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private RenderType renderType = RenderType.FULL_CUBE;
        private boolean castsShadow = true;
        private float lightEmission = 0.0f;
        private int textureAtlasIndex = -1;

        public Builder renderType(RenderType t) { this.renderType = t; return this; }
        public Builder noCastShadow() { this.castsShadow = false; return this; }
        public Builder lightEmission(float v) { this.lightEmission = v; return this; }
        public Builder textureAtlasIndex(int idx) { this.textureAtlasIndex = idx; return this; }
        public BlockRenderProfile build() { return new BlockRenderProfile(this); }
    }
}
