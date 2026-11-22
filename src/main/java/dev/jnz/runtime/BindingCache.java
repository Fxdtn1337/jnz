package dev.jnz.runtime;

import java.util.HashMap;
import java.util.Map;

/** 每个定义类加载器只装入一次已完成校验的模块。 */
final class BindingCache {
    private static final Map<Integer, LoadedGroup> READY = new HashMap<>();

    private BindingCache() {}

    static void bind(Class<?> owner, int classId) throws Exception {
        int group = ClassRoute.resolve(owner, classId);
        LoadedGroup state = READY.get(group);
        if (state == null) {
            NativeBridge bridge = NativeBridge.resolve(group);
            NativeLibrary.link(PlatformTarget.hostTarget(), group);
            state = new LoadedGroup(bridge);
            READY.put(group, state);
        }
        state.bind(owner, classId);
    }
}
