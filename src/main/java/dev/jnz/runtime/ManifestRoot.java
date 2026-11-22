package dev.jnz.runtime;

/** 运行时解出的目录根摘要由打包器写入 class 常量池。 */
final class ManifestRoot {
    private ManifestRoot() {}

    static byte[] digest() { return DigestCodec.unhex(encoded()); }
    private static String encoded() { return "JN3_CATALOG_DIGEST"; }
}
