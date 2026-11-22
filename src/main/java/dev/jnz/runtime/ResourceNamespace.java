package dev.jnz.runtime;

/** 每个输出 JAR 从两个构建参数推导独立资源命名空间。 */
public final class ResourceNamespace {
    private ResourceNamespace() {}

    static String current() { return derive(left(), right()); }

    public static String derive(long left, long right) {
        long first = Long.rotateLeft(left + 0x9e3779b97f4a7c15L, 27) ^ right;
        long second = Long.rotateRight(right ^ 0xd6e8feb86659fd93L, 19) + left;
        return "META-INF/jnz/" + Long.toUnsignedString(first, 36) + "-" + Long.toUnsignedString(second, 36) + "/";
    }

    // 这两个返回值由 ASM 在注入构建版本时替换。
    private static long left() { return 0x62e972d53be892d1L; }
    private static long right() { return 0x359ce5f531f214e3L; }
}
