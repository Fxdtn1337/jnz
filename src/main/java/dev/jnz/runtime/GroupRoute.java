package dev.jnz.runtime;

import java.io.DataInputStream;
import java.io.IOException;

final class GroupRoute {
    private GroupRoute() {}

    static BridgeNames resolve(int id) throws IOException {
        String path = ResourcePaths.groupRoute(id);
        try (DataInputStream input = MetadataCatalog.open(path)) {
            int seed = input.readInt();
            String encoded = input.readUTF();
            BinaryStream.end(input, path);
            return BridgeNames.decode(StringMask.reveal(encoded, seed));
        } catch (IllegalArgumentException error) {
            throw new IOException("无法恢复 JNI 桥接路由", error);
        }
    }
}
