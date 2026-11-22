package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.IOException;

final class ClassRoute {
    private ClassRoute() {}

    static int resolve(Class<?> owner, int id) throws IOException {
        if (id < 0) throw new IOException("类绑定编号无效");
        String path = ResourcePaths.classRoute(id);
        try (DataInputStream input = MetadataCatalog.open(path)) {
            int group = input.readInt();
            String expected = input.readUTF();
            BinaryStream.end(input, path);
            if (group < 0 || !owner.getName().equals(expected)) throw new IOException("类绑定路由不匹配");
            return group;
        }
    }
}
