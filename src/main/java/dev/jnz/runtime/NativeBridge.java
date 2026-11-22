package dev.jnz.runtime;

import java.lang.reflect.Method;

/** 经过声明检查的 JNI 注册桥。 */
final class NativeBridge {
    private final Method registration;

    private NativeBridge(Method registration) {
        this.registration = registration;
    }

    static NativeBridge resolve(int group) throws Exception {
        return new NativeBridge(BridgeResolver.resolve(GroupRoute.resolve(group)));
    }

    void register(Class<?> owner, int classId) throws Exception {
        registration.invoke(null, owner, classId);
    }

}
