package dev.jnz.runtime;

/** 打包端和运行时共享的大端模块格式常量。 */
public final class ModuleFormat {
    public static final int INDEX_MAGIC = 0x4a4e5a32; // JNZ2
    public static final int FRAME_MAGIC = 0x4a4e4631; // JNF1
    public static final int RAW = 0;
    public static final int DEFLATE = 1;
    public static final int FRAME_HEADER_BYTES = 49;
    public static final int FRAME_BYTES = 64 * 1024 * 1024;
    public static final int STORED_BYTES = FRAME_BYTES + 1024 * 1024;

    private ModuleFormat() {}
}
