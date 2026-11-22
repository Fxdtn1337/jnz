package dev.jnz.runtime;

import java.io.IOException;
import java.io.InputStream;

final class ModuleSource {
    private final String target;
    private final int group;
    final ModuleIndex index;

    ModuleSource(String target, int group) throws IOException {
        this.target = target;
        this.group = group;
        this.index = ModuleIndex.read(ResourcePaths.moduleIndex(target, group));
    }

    InputStream chunk(int index) throws IOException {
        return PackedResources.require(ResourcePaths.chunk(target, group, index));
    }

    String label() { return target + "/" + group; }
}
