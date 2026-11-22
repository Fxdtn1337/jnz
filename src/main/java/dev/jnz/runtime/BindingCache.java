package dev.jnz.runtime;

import java.util.HashMap;
import java.util.Map;

/** 每个定义类加载器只装入及授权一次成功绑定的模块。 */
final class BindingCache {
    private static final Map<Integer, NativeBridge> READY = new HashMap<>();

    private BindingCache() {}

    static void bind(Class<?> owner, int classId) throws Exception {
        int group = PackedResources.groupOf(classId);
        NativeBridge bridge = READY.get(group);
        if (bridge == null) {
            bridge = NativeBridge.resolve(group);
            NativeLibrary.link(PlatformTarget.hostTarget(), group);
            LicenseGate.verify(bridge);
            READY.put(group, bridge);
        }
        bridge.register(owner, classId);
    }
}
