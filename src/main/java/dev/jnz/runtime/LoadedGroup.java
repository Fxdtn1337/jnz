package dev.jnz.runtime;

import java.util.HashSet;
import java.util.Set;

/** 只有成功装载的组进入缓存。每个类绑定成功后才记为就绪。 */
final class LoadedGroup {
    private final NativeBridge bridge;
    private final Set<Integer> ready = new HashSet<>();

    LoadedGroup(NativeBridge bridge) {
        this.bridge = bridge;
    }

    void bind(Class<?> owner, int id) throws Exception {
        if (!ready.contains(id)) {
            bridge.register(owner, id);
            ready.add(id);
        }
    }
}
