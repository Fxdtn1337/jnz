package dev.jnz.runtime;

/** 生成编译端和运行时共用的相对资源路径。 */
public final class ResourcePaths {
    private ResourcePaths() {}

    public static String classRoute(int id) { return "classes/" + id; }
    public static String groupRoute(int group) { return "groups/" + group; }
    public static String catalog() { return "catalog.bin"; }
    public static String moduleIndex(String target, int group) { return target + "/" + group + "/index.bin"; }

    public static String chunk(String target, int group, int index) {
        return target + "/" + group + "/" + ModuleName.chunkToken(target, group, index) + ".part";
    }
}
