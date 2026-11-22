package dev.jnz.runtime;

/** 资源格式的显式大小边界。 */
public final class ResourceLimits {
    public static final int CATALOG_BYTES = 16 * 1024 * 1024;
    public static final int METADATA_BYTES = 128 * 1024;
    public static final int DIGEST_BYTES = 32;
    public static final int CATALOG_RECORDS = 100_000;
    public static final int MODULE_BLOCKS = 262_144;
    public static final long MODULE_BYTES = 2L * 1024 * 1024 * 1024;
    private ResourceLimits() {}
}
