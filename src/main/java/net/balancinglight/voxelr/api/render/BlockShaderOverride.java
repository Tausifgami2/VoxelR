package net.balancinglight.voxelr.api.render;

/**
 * Describes a custom shader override for a specific block ID.
 * Used for emissive blocks, CTM, animated textures, etc.
 */
public final class BlockShaderOverride {

    private final int blockId;
    private final String fragmentShaderPath;
    private final boolean emissive;
    private final boolean animated;
    private final float emissiveStrength;

    private BlockShaderOverride(Builder builder) {
        this.blockId = builder.blockId;
        this.fragmentShaderPath = builder.fragmentShaderPath;
        this.emissive = builder.emissive;
        this.animated = builder.animated;
        this.emissiveStrength = builder.emissiveStrength;
    }

    public int getBlockId() { return blockId; }
    public String getFragmentShaderPath() { return fragmentShaderPath; }
    public boolean isEmissive() { return emissive; }
    public boolean isAnimated() { return animated; }
    public float getEmissiveStrength() { return emissiveStrength; }

    public static Builder builder(int blockId) {
        return new Builder(blockId);
    }

    public static final class Builder {
        private final int blockId;
        private String fragmentShaderPath = null;
        private boolean emissive = false;
        private boolean animated = false;
        private float emissiveStrength = 1.0f;

        private Builder(int blockId) { this.blockId = blockId; }

        public Builder fragmentShader(String path) { this.fragmentShaderPath = path; return this; }
        public Builder emissive(float strength) { this.emissive = true; this.emissiveStrength = strength; return this; }
        public Builder animated() { this.animated = true; return this; }
        public BlockShaderOverride build() { return new BlockShaderOverride(this); }
    }
}
