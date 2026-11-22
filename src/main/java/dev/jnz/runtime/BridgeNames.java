package dev.jnz.runtime;

import java.io.IOException;

final class BridgeNames {
    final String owner;
    final String method;

    private BridgeNames(String owner, String method) {
        this.owner = owner;
        this.method = method;
    }

    static BridgeNames decode(String route) throws IOException {
        int split = route.indexOf('|');
        if (split != 25 || !route.matches("R[0-9a-f]{24}[|]m[0-9a-f]{24}"))
            throw new IOException("JNI 桥接路由格式无效");
        return new BridgeNames(route.substring(0, split), route.substring(split + 1));
    }
}
