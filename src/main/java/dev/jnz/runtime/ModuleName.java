package dev.jnz.runtime;

/** 用平台、分组与块号生成模块内可逆编号，不依赖 ZIP 条目顺序。 */
final class ModuleName {
    private ModuleName() {}

    static String chunkToken(String target, int group, int index) {
        int mask = Integer.rotateLeft(target.hashCode(), 13) ^ group * 0x9e3779b9;
        return Integer.toUnsignedString(Integer.rotateLeft(index ^ mask, 9), 36);
    }
}
